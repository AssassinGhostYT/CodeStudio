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

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

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

ARGS="$ARGS -r $PREFIX/local/ubuntu"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

$PROOT $ARGS /bin/bash -c "$@"
