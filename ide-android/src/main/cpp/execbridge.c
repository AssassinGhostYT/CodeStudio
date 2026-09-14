/*
 * execbridge.c — LD_PRELOAD shim that rewrites every exec of a binary/script living under
 * the CodeStudio Termux userland prefix to run through /system/bin/linker64.
 *
 * Android's W^X policy forbids exec of ELF binaries in app data (execute_no_trans denied),
 * but the system dynamic linker is always executable.  Running "<linker64> <elf> [args]"
 * is the canonical workaround — the linker loads and runs the binary natively.
 *
 * The bootstrap ships the OLD AndroidIDE termux-exec which only wraps ELFs in PROOT
 * (ptrace blocked here), so we implement the rewrite ourselves.
 *
 * CS_PREFIX must be set to the prefix dir (with trailing slash).
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern int *__errno(void);
int *__errno_location(void) { return __errno(); }

#ifdef __ANDROID__
#include <linux/wait.h>
#include <sys/syscall.h>
#endif

/* ------------------------------------------------------------------ */
/* Utilities                                                          */
/* ------------------------------------------------------------------ */

static const char *LINKER(void) {
#ifdef __LP64__
    return "/system/bin/linker64";
#else
    return "/system/bin/linker";
#endif
}

static inline bool starts_with(const char *s, const char *pfx) {
    return s != NULL && pfx != NULL && strncmp(s, pfx, strlen(pfx)) == 0;
}

static inline bool is_linker(const char *p) {
    return p != NULL && (
        strcmp(p, "/system/bin/linker64") == 0 ||
        strcmp(p, "/system/bin/linker") == 0
    );
}

/* ------------------------------------------------------------------ */
/* Script/ELF detection                                               */
/* ------------------------------------------------------------------ */

static int classify_exec(const char *path) {
    /* 1 = ELF, 2 = script "#!" …, 0 = unsure/ENOENT */
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char h[8] = {0};
    ssize_t n = read(fd, h, sizeof(h));
    close(fd);
    if (n >= 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return 1;
    if (n >= 2 && h[0] == '#' && h[1] == '!') return 2;
    return 0;
}

/* ------------------------------------------------------------------ */
/* Parse "#!<interp> [arg]" → fill interp/arg (caller frees)          */
/* ------------------------------------------------------------------ */

static bool parse_shebang(const char *path, const char **pinterp, const char **parg) {
    *pinterp = *parg = NULL;
    char buf[512];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n < 3) return false;
    buf[n] = '\0';
    char *nl = strchr(buf, '\n'); if (nl) *nl = '\0'; else buf[n] = '\0';
    char *s = buf + 2;
    while (*s == ' ' || *s == '\t') s++;
    char *end = s; while (*end && *end != ' ' && *end != '\t') end++;
    if (end == s) return false;
    bool has_arg = (*end == ' ' || *end == '\t');
    *end = '\0';
    char *interp = strdup(s);
    if (!interp) return false;
    *pinterp = interp;
    if (!has_arg) return true; /* no arg on the shebang line — don't read on past the line NUL */
    s = end + 1; while (*s == ' ' || *s == '\t') s++;
    *parg = *s ? strdup(s) : NULL;
    return true;
}

/* ------------------------------------------------------------------ */
/* Core dispatch: rewrite if path is under prefix                     */
/* ------------------------------------------------------------------ */

static int exec_prefix(const char *path, char *const argv[], char *const envp[],
                       int (*real)(const char *, char *const[], char *const[])) {
    const char *prefix = getenv("CS_PREFIX");
    if (prefix == NULL || !starts_with(path, prefix) || is_linker(path))
        return real(path, argv, envp);

    int kind = classify_exec(path);
    if (kind == 0) return real(path, argv, envp);

    if (kind == 2) {
        const char *interp = NULL, *arg = NULL;
        if (!parse_shebang(path, &interp, &arg)) return real(path, argv, envp);
        /* Interpreter outside /data/ → kernel exec is fine (linker unnecessary). */
        if (!starts_with(interp, "/data/")) {
            free((char *)interp); free((char *)arg);
            return real(path, argv, envp);
        }
        /* Route script through linker: argv = [linker, interp, arg?, script, argv[1]...] */
        int argc = 0; while (argv[argc]) argc++;
        int slots = 1 + 1 + (arg ? 1 : 0) + 1 + argc; /* linker + interp + arg? + script + rest + NULL */
        char **nv = (char **)malloc(sizeof(char *) * (size_t)slots);
        if (!nv) { free((char *)interp); free((char *)arg); errno = ENOMEM; return -1; }
        int i = 0;
        nv[i++] = (char *)LINKER();
        nv[i++] = (char *)interp;
        if (arg) nv[i++] = (char *)arg;
        nv[i++] = (char *)path;        /* script, visible as $0 for interp */
        for (int j = 1; argv[j]; j++) nv[i++] = argv[j];
        nv[i] = NULL;
        int ret = real(LINKER(), nv, envp);
        free(nv); free((char *)interp); free((char *)arg);
        return ret;
    }

    /* ELF: argv = [linker, elf, argv[1]...] */
    int argc = 0; while (argv[argc]) argc++;
    int slots = 1 + 1 + argc;  /* linker + elf + rest + NULL */
    char **nv = (char **)malloc(sizeof(char *) * (size_t)slots);
    if (!nv) { errno = ENOMEM; return -1; }
    nv[0] = (char *)LINKER();
    nv[1] = (char *)path;
    for (int j = 1; argv[j]; j++) nv[1 + j] = argv[j];
    nv[argc + 1] = NULL;
    int ret = real(LINKER(), nv, envp);
    free(nv);
    return ret;
}

/* ------------------------------------------------------------------ */
/* Override the execve family                                          */
/* ------------------------------------------------------------------ */

static int (*pexecve)(const char *, char *const[], char *const[]) = NULL;

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (!pexecve) pexecve = (void *)dlsym(RTLD_NEXT, "execve");
    return exec_prefix(path, argv, envp, pexecve);
}

int execvp(const char *path, char *const argv[]) {
    /* Minimal execvp shim: try with existing PATH, fall back to libc. */
    const char *pathenv = getenv("PATH");
    if (pathenv == NULL || path[0] == '/') {
        static int (*pe)(const char *, char *const[]) = NULL;
        if (!pe) pe = (void *)dlsym(RTLD_NEXT, "execvp");
        return pe(path, argv);
    }
    char *copy = strdup(pathenv);
    char *save = NULL;
    char *dir;
    for (dir = strtok_r(copy, ":", &save); dir; dir = strtok_r(NULL, ":", &save)) {
        char full[512];
        snprintf(full, sizeof(full), "%s/%s", dir, path);
        if (classify_exec(full) != 0) {
            static int (*pe2)(const char *, char *const[]) = NULL;
            if (!pe2) pe2 = (void *)dlsym(RTLD_NEXT, "execvp");
            return pe2(full, argv);
        }
    }
    free(copy);
    static int (*pe3)(const char *, char *const[]) = NULL;
    if (!pe3) pe3 = (void *)dlsym(RTLD_NEXT, "execvp");
    return pe3(path, argv);
}
