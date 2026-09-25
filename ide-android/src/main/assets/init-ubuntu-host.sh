UBUNTU_DIR=$PREFIX/local/ubuntu

# Deterministic run log for the app: env, prereqs, proot ARGS and the proot exit code. The pty buffer
# can be lost when the session dies mid-write (black screen), but this file always lands on disk — the
# app reads its tail to surface the real failure reason instead of a generic "Shell salió".
DEBUG_LOG="$PREFIX/local/init-ubuntu-run.log"
{
    echo "=== $(date +%H:%M:%S) pid $$ ==="
    env | grep -E '^(PREFIX|BIN|PROOT|PROOT_LOADER|PROOT_LOADER_32|NATIVE_LIB_DIR|PUBLIC_HOME|LD_LIBRARY_PATH|TMPDIR|LINKER)=' || true
    echo "--- prereqs ---"
    ls -la "$UBUNTU_DIR/bin/bash" "$UBUNTU_DIR/usr/bin/dash" "$PROOT" "$PROOT_LOADER" 2>&1 || true
    if [ -x "$UBUNTU_DIR/bin/bash" ] && [ -f "$PROOT" ] && [ -f "$PROOT_LOADER" ]; then
        echo "prereqs: ok"
    else
        echo "prereqs: MISSING"
    fi
} > "$DEBUG_LOG" 2>&1

mkdir -p $UBUNTU_DIR

# The rootfs is downloaded + extracted by the app (UbuntuRuntime.ensureReady → ubuntu-base tar.gz →
# $UBUNTU_DIR) BEFORE this script is ever run. If the app-side extraction hasn't happened, surface it
# visibly instead of letting proot fail on an empty -r root.
if [ ! -x "$UBUNTU_DIR/bin/bash" ]; then
    echo "UBUNTU_ROOTFS_MISSING: $UBUNTU_DIR/bin/bash no existe — cerrá y reabrí la terminal desde la IDE (el rootfs se descarga y extrae al arrancar)." >&2
    ls -la "$UBUNTU_DIR" >&2
    echo "UBUNTU_ROOTFS_MISSING" >> "$DEBUG_LOG"
    sleep 2
fi

# GNU glibc binaries in the rootfs live under usr/bin and usr/sbin (bin/ and sbin/ are symlinks to
# them in ubuntu-base). proot resolves those through the guest /usr/lib/aarch64-linux-gnu/
# ld-linux-aarch64.so.1 via its loader, so no host binary ever needs to be copied into the rootfs
# (the Alpine engine overrides bin/rm with a host wrapper; Ubuntu must NOT — host bionic ELFs won't
# run under the glibc loader and /bin/rm is the GNU coreutils one).

if [ ! -e "$PREFIX/local/stat" ]; then : > "$PREFIX/local/stat"; fi
if [ ! -e "$PREFIX/local/vmstat" ]; then : > "$PREFIX/local/vmstat"; fi

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b $PREFIX/local/stat:/proc/stat"
ARGS="$ARGS -b $PREFIX/local/vmstat:/proc/vmstat"

if [ -e "/proc/self/fd" ]; then
 ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

# See run-ubuntu-host.sh: the per-fd /dev/std* binds are dropped on purpose (proot cannot sanitize a pipe/pty
# source and warned about it on every run). /proc plus /dev/fd already expose fd 0..2 inside the guest.



ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/ubuntu/tmp" ]; then
 mkdir -p "$PREFIX/local/ubuntu/tmp"
 chmod 1777 "$PREFIX/local/ubuntu/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/ubuntu/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/ubuntu"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

echo "--- ARGS ---" >> "$DEBUG_LOG"
echo "$ARGS" >> "$DEBUG_LOG"

$PROOT $ARGS /bin/bash $BIN/init-ubuntu "$@"
RC=$?
echo "proot exit=$RC" >> "$DEBUG_LOG"
exit $RC