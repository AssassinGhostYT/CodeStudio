UBUNTU_DIR=$PREFIX/local/ubuntu

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

# No per-fd binds (/proc/self/fd/N -> /dev/std*): proot re-resolves every source while sanitizing, and a pipe or a
# pty has no stable path there, so each one failed with
#   proot warning: can't sanitize binding "/proc/self/fd/0": No such file or directory
# Nothing is lost by dropping them: /proc is bound above, so the guest still reaches the real descriptors at
# /proc/self/fd/0..2 (proot inherits them) and at /dev/fd/0..2. Processes read fd 0/1/2 directly, not /dev/stdin.


export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export HOME=/root

# Android's /etc is read-only. Keep DNS in the app-private prefix and bind it into Ubuntu.
RESOLV_CONF="$PREFIX/local/resolv.conf"
mkdir -p "$PREFIX/local"
# Prefer the DNS servers supplied by the Android network before public fallbacks.
{
  for dns_key in net.dns1 net.dns2 net.dns3 net.dns4; do
    dns_value=$(getprop "$dns_key" 2>/dev/null || true)
    case "$dns_value" in
      ''|0.0.0.0|::) ;;
      *) printf 'nameserver %s\n' "$dns_value" ;;
    esac
  done
  printf '%s\n' "nameserver 8.8.8.8" "nameserver 1.1.1.1"
} > "$RESOLV_CONF"
ARGS="$ARGS -b $RESOLV_CONF:/etc/resolv.conf"

if [ ! -d "$PREFIX/local/ubuntu/tmp" ]; then
 mkdir -p "$PREFIX/local/ubuntu/tmp"
 chmod 1777 "$PREFIX/local/ubuntu/tmp"
fi
ARGS="$ARGS -b $PREFIX/local/ubuntu/tmp:/dev/shm"

# Optional caller-supplied bind mounts, as a space-separated list of proot <src>[:<dst>] pairs.
# The Flutter build host uses this to map an executable copy of the project's android/gradlew over the
# original: projects live in app-specific EXTERNAL storage, whose filesystem does not keep the
# executable bit, so the tool's direct spawn of the wrapper fails with EACCES/EPERM. The copy lives in
# the prefix (internal storage, where the mode bit sticks) and proot resolves the path to it, while
# argv[0] keeps the project path so the wrapper still finds its gradle-wrapper.jar next to it.
if [ -n "$EXTRA_BINDS" ]; then
 extra_bind_count=0
 for extra_bind in $EXTRA_BINDS; do
  if [ ! -e "${extra_bind%%:*}" ]; then
   echo "host: bind omitido, no existe el origen ${extra_bind%%:*}" >&2
   continue
  fi
  ARGS="$ARGS -b $extra_bind"
  extra_bind_count=$((extra_bind_count + 1))
 done
 [ "$extra_bind_count" -gt 0 ] && echo "host: $extra_bind_count bind(s) extra aplicado(s)"
 unset extra_bind_count
fi
unset extra_bind

ARGS="$ARGS -r $PREFIX/local/ubuntu"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$PROOT $ARGS /bin/bash -c "$@"
