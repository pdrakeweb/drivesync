#!/usr/bin/env bash
# Scenario 01: two emulators sync notes through a local folder.
#
# The sync folder lives in each app's own external files directory. Two emulators share no file
# system, so this script plays the part of a USB stick: it copies the folder from one device to
# the other with adb. Every file has exactly one writer, so merging the two folders is a plain copy.
#
# Usage: tests/journey-two-emulators.sh <serial-a> <serial-b>   (sample debug APK already installed)
# Exit code 0 only if every check passed.
set -u
export MSYS_NO_PATHCONV=1
A="${1:?serial of device A}"
B="${2:?serial of device B}"
PKG=io.github.pdrakeweb.drivesync.sample
DIR=/sdcard/Android/data/$PKG/files/drivesync/v1
WORK="$(mktemp -d)"
# adb.exe on Windows needs a Windows path, not a Git Bash one.
command -v cygpath >/dev/null 2>&1 && WORK="$(cygpath -m "$WORK")"
FAILS=0

# app <serial> [extras as one string, quoted for the device shell]
app() { adb -s "$1" shell "am start -n $PKG/.MainActivity ${2:-}" >/dev/null 2>&1; sleep 2; }
sync_now() { app "$1" "--ez sync true"; sleep 2; }
# All text on screen, one item per line.
screen() { adb -s "$1" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//' | grep -v '^$'; }
# Note titles: every line that is not part of the fixed interface.
notes() { screen "$1" | grep -v -x -E 'drivesync sample|Milestone S2 build.*|Synced|Not synced yet|Syncing|Sync now|New note|Add|Rename|Delete|No notes yet.*|.*waiting to upload|Nothing waiting to upload' | sort | paste -sd, -; }
carry() { # carry <from> <to>: copy the sync folder across, as a USB stick would
  rm -rf "$WORK/stick"; mkdir -p "$WORK/stick"
  adb -s "$1" pull "$DIR" "$WORK/stick" >/dev/null 2>&1
  adb -s "$2" shell mkdir -p "$DIR"
  adb -s "$2" push "$WORK/stick/v1/." "$DIR/" >/dev/null 2>&1
}
exchange() { sync_now "$A"; sync_now "$B"; carry "$A" "$B"; carry "$B" "$A"; sync_now "$A"; sync_now "$B"; carry "$A" "$B"; carry "$B" "$A"; sync_now "$A"; sync_now "$B"; }
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1: expected [$2] but saw [$3]"; FAILS=$((FAILS + 1)); fi
}
contains() { if screen "$2" | grep -q -x -E "$3"; then echo "PASS  $1"; else echo "FAIL  $1: [$3] not on screen of $2"; FAILS=$((FAILS + 1)); fi; }

echo "== fresh installs"
for d in "$A" "$B"; do adb -s "$d" shell pm clear "$PKG" >/dev/null; adb -s "$d" shell rm -rf "$DIR"; app "$d"; done
contains "empty state says it is empty (A)" "$A" 'No notes yet.*'

echo "== 1. a note written on A reaches B"
app "$A" "--es add Milk"
contains "pending change is counted and visible (A)" "$A" '2 changes waiting to upload'
sync_now "$A"
contains "pending cleared after sync (A)" "$A" 'Nothing waiting to upload'
carry "$A" "$B"; sync_now "$B"
check "B shows the note from A" "Milk" "$(notes "$B")"

echo "== 2. both devices edit while apart"
app "$B" "--es add Bread"
app "$B" "--es rename Milk --es to 'Oat milk'"
app "$A" "--es add Eggs"
exchange
check "A has all three" "Bread,Eggs,Oat milk" "$(notes "$A")"
check "B has all three" "Bread,Eggs,Oat milk" "$(notes "$B")"

echo "== 3. delete on one device beats a concurrent edit on the other"
app "$A" "--es delete Bread"
app "$B" "--es rename Bread --es to 'Rye bread'"
exchange
check "A: deleted note stays deleted" "Eggs,Oat milk" "$(notes "$A")"
check "B: deleted note stays deleted" "Eggs,Oat milk" "$(notes "$B")"

echo "== 4. the app survives being killed with unsynced changes"
app "$A" "--es add Butter"
adb -s "$A" shell am force-stop "$PKG"
app "$A"
exchange
check "B received the change made before the kill" "Butter,Eggs,Oat milk" "$(notes "$B")"
contains "both end in the synced state (B)" "$B" 'Synced'

echo "== storage: names carry no content"
BAD=$(adb -s "$A" shell "cd $DIR && find . -type f" | tr -d '\r' | grep -v -E '^\./(replicas/[0-9a-f-]{36}\.json|batches/[0-9a-f-]{36}/[0-9]{12}-[0-9a-f]{16}\.bin)$')
check "every stored file name is ids, numbers and hashes only" "" "$BAD"

for d in "$A" "$B"; do
  if adb -s "$d" logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then echo "FAIL  crash logged on $d"; FAILS=$((FAILS + 1)); else echo "PASS  no crash on $d"; fi
done
rm -rf "$WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($FAILS)"; fi
exit "$FAILS"
