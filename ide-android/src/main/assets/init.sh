set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
# HOME stays on the app-INTERNAL rootfs (/root), NOT the external files dir. npm caches, pip, and
# tool installers (opencode -> ~/.opencode/bin) write to HERE: the internal dir is a real fs dir
# where atomic rename() works, whereas the external /storage mount fails rename() under proot/FUSE
# with ENOENT (observed with `npm install -g opencode-ai`). PUBLIC_HOME (the app's external files
# dir, where `projects/` lives) is used ONLY as the initial working directory so `cd <proyecto>`
# still just works; nothing gets persisted to /storage. Falls back to the in-rootfs /root.
export HOME=/root
# opencode's installer drops its binary in ~/.opencode/bin and pastes the export into ~/.bashrc;
# login shells read /etc/profile, not .bashrc, so also put it on PATH to make `opencode` resolvable
# right away.
export PATH="$HOME/.opencode/bin:$PATH"

if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi


export PS1='\[\033[01;32m\]\u@reterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
# shellcheck disable=SC2034
export PIP_BREAK_SYSTEM_PACKAGES=1

#fix linker warning
if [[ ! -f /linkerconfig/ld.config.txt ]];then
    mkdir -p /linkerconfig
    touch /linkerconfig/ld.config.txt
fi

if [ "$#" -eq 0 ]; then
    source /etc/profile 2>/dev/null || true
    # /etc/profile redefines PATH, so re-append this to keep `opencode` resolvable.
    export PATH="$HOME/.opencode/bin:$PATH"
    export PS1='\[\033[01;32m\]\u@reterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
    # $HOME is always the internal rootfs /root (see above). Just land in the external projects dir
    # if it's reachable; if storage isn't granted yet / SD card absent, fall back to $HOME rather
    # than dying — under `set -e` a failed mkdir/cd would kill the shell instantly and the UI would
    # sit on "Waiting for shell…" forever with no diagnostics.
    mkdir -p "$HOME" 2>/dev/null || true
    if [ -d "$PUBLIC_HOME" ]; then
        cd "$PUBLIC_HOME" 2>/dev/null || cd "$HOME"
    else
        cd "$HOME"
    fi
    if [ -f /initrc ]; then
        source /initrc
    fi
    /bin/ash
else
    exec "$@"
fi