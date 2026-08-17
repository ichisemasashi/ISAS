#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || { echo "Linux host required" >&2; exit 64; }
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
[ -r /etc/os-release ] || { echo "/etc/os-release is required" >&2; exit 65; }
. /etc/os-release
case "$ID:$VERSION_ID" in debian:13|ubuntu:24.04) ;; *) echo "unsupported distribution: $ID $VERSION_ID" >&2; exit 65 ;; esac
case "$(uname -m)" in x86_64|aarch64) ;; *) echo "unsupported architecture" >&2; exit 65 ;; esac
systemd_major=$(systemd --version | awk 'NR == 1 { print $2 }')
case "$ID" in debian) minimum_systemd=257 ;; ubuntu) minimum_systemd=255 ;; esac
[ "$systemd_major" -ge "$minimum_systemd" ] || { echo "systemd $minimum_systemd or newer is required" >&2; exit 65; }
[ -r /sys/fs/cgroup/cgroup.controllers ] || { echo "cgroup v2 is required" >&2; exit 65; }
[ "$(nproc)" -ge 8 ] || { echo "at least 8 CPU cores are required" >&2; exit 65; }
memory_kib=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)
[ "$memory_kib" -ge 33554432 ] || { echo "at least 32 GiB memory is required" >&2; exit 65; }
: "${ISAS_DATA_MOUNT:=/var/lib/isas}"
[ -d "$ISAS_DATA_MOUNT" ] || { echo "data mount must exist: $ISAS_DATA_MOUNT" >&2; exit 65; }
available_kib=$(df -Pk "$ISAS_DATA_MOUNT" | awk 'NR == 2 { print $4 }')
[ "$available_kib" -ge 1073741824 ] || { echo "at least 1 TiB free data storage is required" >&2; exit 65; }
: "${ISAS_LUKS_DEVICE:?LUKS2 source device is required}"
: "${ISAS_LUKS_MAPPER:?mounted LUKS2 mapper device is required}"
cryptsetup isLuks --type luks2 "$ISAS_LUKS_DEVICE" || { echo "LUKS2 data encryption is required" >&2; exit 65; }
mounted_source=$(findmnt -n -o SOURCE --target "$ISAS_DATA_MOUNT")
[ -b "$ISAS_LUKS_MAPPER" ] || { echo "LUKS2 mapper is not a block device" >&2; exit 65; }
[ "$(readlink -f "$mounted_source")" = "$(readlink -f "$ISAS_LUKS_MAPPER")" ] || { echo "data mount is not backed by the approved LUKS2 mapper" >&2; exit 65; }
aa-enabled || { echo "AppArmor must be enabled" >&2; exit 65; }
[ "$(timedatectl show -p NTPSynchronized --value)" = "yes" ] || { echo "NTP synchronization is required" >&2; exit 65; }
: "${ISAS_UPS_MODE:?UPS mode must be upsd or datacenter-backed}"
case "$ISAS_UPS_MODE" in
  upsd) systemctl is-active --quiet nut-monitor.service || systemctl is-active --quiet upsmon.service || { echo "UPS monitor is not active" >&2; exit 65; } ;;
  datacenter-backed) : "${ISAS_UPS_EVIDENCE:?datacenter power evidence is required}"; [ -s "$ISAS_UPS_EVIDENCE" ] ;;
  *) echo "UPS mode must be upsd or datacenter-backed" >&2; exit 65 ;;
esac
: "${ISAS_SUPPORT_MATRIX_EVIDENCE:?distribution security support evidence is required}"
[ -s "$ISAS_SUPPORT_MATRIX_EVIDENCE" ] || { echo "support matrix evidence is empty" >&2; exit 65; }
echo "Linux Production preflight: PASS $ID $VERSION_ID $(uname -m)"
