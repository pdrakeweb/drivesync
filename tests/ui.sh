#!/usr/bin/env bash
# ui.sh <serial> screen | tap <text> | reset | start
export MSYS_NO_PATHCONV=1
command -v adb >/dev/null 2>&1 || export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
S="$1"; CMD="$2"; ARG="${3:-}"
PKG=io.github.pdrakeweb.drivesync.sample
DIR=/sdcard/Android/data/$PKG/files/drivesync/v1
dump() { adb -s "$S" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml"; }
case "$CMD" in
  screen) dump | grep -o -E '(text|content-desc)="[^"]+"' ;;
  nodes) dump | sed 's/<node/\n<node/g' | grep -E 'text="[^"]+"|content-desc="[^"]+"' | sed -E 's/.*text="([^"]*)".*class="([^"]*)".*content-desc="([^"]*)".*bounds="([^"]*)".*/\1 | \3 | \2 | \4/' ;;
  tap)
    line=$(dump | sed 's/<node/\n<node/g' | grep -E "(text|content-desc)=\"$ARG\"" | head -1)
    b=$(echo "$line" | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
    set -- $b
    [ $# -eq 4 ] || { echo "not found: $ARG"; exit 1; }
    x=$(( ($1 + $3) / 2 )); y=$(( ($2 + $4) / 2 ))
    adb -s "$S" shell input tap $x $y; echo "tapped $ARG at $x,$y" ;;
  reset) adb -s "$S" shell pm clear $PKG; adb -s "$S" shell rm -rf "$DIR" ;;
  start) adb -s "$S" shell "am start -n $PKG/.MainActivity $ARG" >/dev/null; sleep 2 ;;
esac
