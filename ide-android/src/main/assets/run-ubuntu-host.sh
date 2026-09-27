UBUNTU_DIR=$PREFIX/local/ubuntu

# Optional caller-supplied bind mounts, as a space-separated list of proot <src>[:<dst>] pairs.
#
# Parsed before anything else and prepended to ARGS, and that order is load-bearing rather than cosmetic.
# The project's android/gradlew lives on app-specific EXTERNAL storage, whose filesystem stores regular files
# without POSIX permission bits, so the tool's direct spawn of the wrapper fails with EACCES/EPERM. The host
# stages an executable copy on internal storage and asks us to map it over the wrapper. proot resolves a path
# against the first binding that matches it, so a generic `-b /sdcard` listed ahead of this more specific
# file binding claims the wrapper first and the executable copy is never consulted — the wrapper keeps its
# real mode 0644 inside the prefix and the build dies with a bare "Permission denied", which Flutter then
# reports as a storage-permissions problem and hides the errno behind. A specific mapping has to precede
# the broad one it refines, or it is silently dead.
EXTRA_ARGS=""
if [ -n "$EXTRA_BINDS" ]; then
 extra_bind_count=0
 for extra_bind in $EXTRA_BINDS; do
  if [ ! -e "${extra_bind%%:*}" ]; then
   echo "host: bind omitido, no existe el origen ${extra_bind%%:*}" >&2
   continue
  fi
  EXTRA_ARGS="$EXTRA_ARGS -b $extra_bind"
  extra_bind_count=$((extra_bind_count + 1))
 done
 [ "$extra_bind_count" -gt 0 ] && echo "host: $extra_bind_count bind(s) extra aplicado(s)"
 unset extra_bind_count
fi
unset extra_bind

ARGS="--kill-on-exit"
ARGS="$ARGS$EXTRA_ARGS"
ARGS="$ARGS -w /"
unset EXTRA_ARGS

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

unset extra_bind

ARGS="$ARGS -r $PREFIX/local/ubuntu"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$PROOT $ARGS /bin/bash -c "$@"
