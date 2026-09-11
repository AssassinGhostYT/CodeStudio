ALPINE_DIR=$PREFIX/local/alpine

mkdir -p $ALPINE_DIR

# The rootfs is extracted by the app (TerminalEngine.ensureReady → assets/alpine/*.tar.gz.rootfs →
# $ALPINE_DIR) BEFORE this script is ever run. The old ReTerminal line that tar-extracted
# "$PREFIX/files/alpine.tar.gz" here could never work on modern installs (no file is ever placed
# at that path — the tarballs are APK assets with an arch suffix), so it's removed. If the app-side
# extraction hasn't happened, surface it visibly instead of letting proot fail on an empty -r root.
if [ ! -x "$ALPINE_DIR/bin/ash" ]; then
    echo "ALPINE_ROOTFS_MISSING: $ALPINE_DIR/bin/ash no existe — cerrá y reabrí la terminal desde la IDE (el rootfs se extrae al arrancar)." >&2
    ls -la "$ALPINE_DIR" >&2
    sleep 2
fi

# stat/vmstat are tiny placeholders proot binds as /proc/stat + /proc/vmstat (Alpine's procps can't
# synthesize them). Create them if missing so proot stops warning about unavailable bind sources.
if [ ! -e "$PREFIX/local/stat" ]; then : > "$PREFIX/local/stat"; fi
if [ ! -e "$PREFIX/local/vmstat" ]; then : > "$PREFIX/local/vmstat"; fi

if [ -f "$BIN/rm" ]; then
    rm -f "$ALPINE_DIR/bin/rm"
    cp "$BIN/rm" "$ALPINE_DIR/bin/rm"
    chmod +x "$ALPINE_DIR/bin/rm"
fi

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

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi


ARGS="$ARGS -b $PREFIX"
ARGS="$ARGS -b /sys"

if [ ! -d "$PREFIX/local/alpine/tmp" ]; then
 mkdir -p "$PREFIX/local/alpine/tmp"
 chmod 1777 "$PREFIX/local/alpine/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/alpine/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX/local/alpine"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$PROOT $ARGS sh $PREFIX/local/bin/init "$@"
