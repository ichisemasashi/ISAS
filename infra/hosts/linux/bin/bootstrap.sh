#!/bin/sh
set -eu

[ "$(uname -s)" = "Linux" ] || { echo "Linux host required" >&2; exit 64; }
[ "$(id -u)" -eq 0 ] || { echo "root required" >&2; exit 77; }
[ -r /etc/os-release ] || exit 65
. /etc/os-release
case "$ID:$VERSION_ID" in debian:13|ubuntu:24.04) ;; *) echo "unsupported distribution: $ID $VERSION_ID" >&2; exit 65 ;; esac
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y apparmor apparmor-utils cryptsetup curl nftables openssl unattended-upgrades
install -d -m 0700 /etc/isas/credentials

if [ "${ISAS_PROVISION_STORAGE:-NO}" = "YES" ]; then
  : "${ISAS_EMPTY_DATA_DEVICE:?exact empty block device is required}"
  : "${ISAS_LUKS_KEY_FILE:?LUKS key file is required}"
  [ -b "$ISAS_EMPTY_DATA_DEVICE" ] || { echo "data device is not a block device" >&2; exit 65; }
  [ -f "$ISAS_LUKS_KEY_FILE" ] || { echo "LUKS key file is missing" >&2; exit 65; }
  case "$(stat -c %a "$ISAS_LUKS_KEY_FILE")" in 400|600) ;; *) echo "LUKS key file mode must be 400 or 600" >&2; exit 65 ;; esac
  [ -z "$(lsblk -n -o MOUNTPOINTS "$ISAS_EMPTY_DATA_DEVICE" | tr -d '[:space:]')" ] || { echo "refusing to format a mounted device" >&2; exit 65; }
  [ -z "$(blkid "$ISAS_EMPTY_DATA_DEVICE" 2>/dev/null)" ] || { echo "refusing to format a device with an existing signature" >&2; exit 65; }
  [ "${ISAS_CONFIRM_LUKS_FORMAT:-NO}" = "YES" ] || { echo "set ISAS_CONFIRM_LUKS_FORMAT=YES to format the validated empty device" >&2; exit 65; }
  cryptsetup luksFormat --type luks2 --batch-mode --key-file "$ISAS_LUKS_KEY_FILE" "$ISAS_EMPTY_DATA_DEVICE"
  cryptsetup open --key-file "$ISAS_LUKS_KEY_FILE" "$ISAS_EMPTY_DATA_DEVICE" isas-data
  mkfs.ext4 -L isas-data /dev/mapper/isas-data
  install -d -m 0711 /var/lib/isas
  mount /dev/mapper/isas-data /var/lib/isas
  echo "Encrypted data storage mounted. Configure an approved systemd-cryptsetup unlock policy before reboot."
else
  install -d -m 0711 /var/lib/isas
  echo "Base packages installed. Supply an existing LUKS2 mount or rerun with explicit empty-device provisioning variables."
fi
