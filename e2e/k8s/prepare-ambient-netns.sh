#!/bin/sh

# Run in the Kubernetes node's mount namespace, not on the workstation.
set -eu

action="${1:---check}"
case "$action" in
    --check | --prepare) ;;
    *)
        echo 'Usage: prepare-ambient-netns.sh [--check|--prepare]' >&2
        exit 1
        ;;
esac

netns_dir=/var/run/netns
if [ ! -d "$netns_dir" ]; then
    if [ "$action" = --check ]; then
        echo "netns=$netns_dir propagation=missing"
        exit 2
    fi
    mkdir -p "$netns_dir"
fi
netns_dir="$(cd "$netns_dir" && pwd -P)"

mount_state() {
    awk -v target="$netns_dir" '
        ($5 == "/" || $5 == target || index(target, $5 "/") == 1) && length($5) > length(point) {
            point = $5
            propagation = "private"
            for (i = 7; i <= NF && $i != "-"; i++) {
                if ($i ~ /^master:/) propagation = "slave"
                if ($i ~ /^shared:/) { propagation = "shared"; break }
            }
        }
        END {
            if (point == "") exit 1
            printf "%s|%s", point, propagation
        }
    ' /proc/self/mountinfo
}

state="$(mount_state)"
propagation="${state##*|}"
echo "netns=$netns_dir source=${state%|*} propagation=$propagation"
case "$propagation" in
    shared | slave) exit 0 ;;
esac
if [ "$action" = --check ]; then
    exit 2
fi

# Recursive bind preserves any namespace mounts already inside the directory.
mount --rbind "$netns_dir" "$netns_dir"
mount --make-rshared "$netns_dir"
state="$(mount_state)"
echo "netns=$netns_dir source=${state%|*} propagation=${state##*|}"
[ "${state##*|}" = shared ]
