#!/bin/sh
set -eu
action=$1
key=$2
host_if="e_${key}_a"
jail_if="e_${key}_b"
bridge="bridge_isas"
case "$action" in
  prepare)
    ifconfig "$bridge" >/dev/null 2>&1 || ifconfig "$bridge" create
    ifconfig "$bridge" up
    ifconfig "$host_if" >/dev/null 2>&1 && exit 0
    created=$(ifconfig epair create)
    peer="${created%a}b"
    ifconfig "$created" name "$host_if"
    ifconfig "$peer" name "$jail_if"
    ifconfig "$bridge" addm "$host_if"
    ifconfig "$host_if" up
    ;;
  address)
    jail_name=$3
    address=$4
    jexec "$jail_name" ifconfig lo0 up
    jexec "$jail_name" ifconfig "$jail_if" inet "$address/24" up
    ;;
  destroy) ifconfig "$host_if" destroy 2>/dev/null || true ;;
  *) exit 64 ;;
esac
