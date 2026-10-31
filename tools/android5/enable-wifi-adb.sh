#!/system/bin/sh
# Run ON the tablet in Termux with /system/bin/sh, not on the host computer.
set -eu
PATH=/system/bin:/system/xbin
LD_PRELOAD=
export PATH LD_PRELOAD
mode=${1:---check}
case "$mode" in --check|--apply) ;; *) printf 'Usage: /system/bin/sh enable-wifi-adb.sh [--check|--apply]\n' >&2; exit 2;; esac
[ "$#" -le 1 ] || exit 2
model=$(getprop ro.product.model)
build=$(getprop ro.build.version.incremental)
api=$(getprop ro.build.version.sdk)
secure=$(getprop ro.adb.secure)
printf 'Model: %s\nBuild: %s\nAPI: %s\nADB authentication: %s\n' "$model" "$build" "$api" "$secure"
id
for prop in sys.adb.debug service.adb.tcp.port persist.adb.tcp.port init.svc.adbd; do
    printf '%s=%s\n' "$prop" "$(getprop "$prop")"
done
[ "$mode" = --apply ] || exit 0
if [ "$model" != MalataSamsungRoyalWolfSinglePartition ] || [ "$build" != MGRSP_20210806 ] || [ "$api" != 22 ] || [ "$secure" != 1 ]; then
    printf 'Refusing changes: this is not the tested model/build/API/authentication profile.\n' >&2
    exit 1
fi
# Keep the first snapshot; do not overwrite it when rerunning this script.
backup=${STRIDE_ADB_BACKUP:-${HOME:?Run this inside Termux}/stride-adb-before.txt}
if [ ! -e "$backup" ]; then
    (set -C; umask 077
     printf 'service.adb.tcp.port=%s\npersist.adb.tcp.port=%s\n' \
         "$(getprop service.adb.tcp.port)" "$(getprop persist.adb.tcp.port)" > "$backup")
fi
printf 'Original port values saved at: %s\n' "$backup"
setprop persist.adb.tcp.port 5555
[ "$(getprop persist.adb.tcp.port)" = 5555 ] || { printf 'Persistent port write failed; stopping.\n' >&2; exit 1; }
setprop service.adb.tcp.port 5555
[ "$(getprop service.adb.tcp.port)" = 5555 ] || { printf 'Runtime port write failed; stopping.\n' >&2; exit 1; }
setprop ctl.restart adbd
printf 'Requested authenticated ADB on TCP 5555. Connect from the host and approve its RSA prompt.\n'
printf 'No su installed, no packages disabled, no reboot requested, no motor commands sent.\n'
