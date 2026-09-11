set -e  # Exit immediately on Failure

export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/share/bin:/usr/share/sbin:/usr/local/bin:/usr/local/sbin:/system/bin:/system/xbin
# Default to the app's external home (`files/`, reachable inside the chroot via the -b /storage) so the
# terminal opens where `projects/` lives and `cd <proyecto>` just works. Falls back to the in-rootfs /root.
export HOME="${PUBLIC_HOME:-/root}"

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
    export PS1='\[\033[01;32m\]\u@reterm\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
    # PUBLIC_HOME is the app's external home (bind-mounted from /storage). If it's unreachable
    # (storage not granted yet, SD card absent) fall back to the in-rootfs /root rather than dying —
    # under `set -e` a failed mkdir/cd would kill the shell instantly and the UI would sit on
    # "Waiting for shell…" forever with no diagnostics.
    mkdir -p "$HOME" 2>/dev/null || export HOME=/root
    cd "$HOME" 2>/dev/null || export HOME=/root
    cd "$HOME"
    if [ -f /initrc ]; then
        source /initrc
    fi
    /bin/ash
else
    exec "$@"
fi