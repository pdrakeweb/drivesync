#!/usr/bin/env bash
# Scenario 03: join by code, change of passphrase, and a database restored from an older copy,
# on one emulator, through the real controls. Usage: tests/journey-setup-features.sh <serial>
# The sample debug APK must be installed. Exit code 0 only if every check passed.
FAILS=0
export MSYS_NO_PATHCONV=1
command -v adb >/dev/null 2>&1 || export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
S="$1"
HERE="$(dirname "$0")"
U="bash $HERE/ui.sh $S"
PKG=io.github.pdrakeweb.drivesync.sample
DIR=/sdcard/Android/data/$PKG/files/drivesync/v1
STICK="$(mktemp -d)"; command -v cygpath >/dev/null 2>&1 && STICK="$(cygpath -m "$STICK")"
has() { if $U screen | grep -q -E "$1"; then echo "PASS  $2"; else echo "FAIL  $2 [$1 not on screen]"; FAILS=$((FAILS + 1)); fi; }
hasnot() { if $U screen | grep -q -E "$1"; then echo "FAIL  $2 [$1 on screen]"; else echo "PASS  $2"; fi; }
save() { rm -rf "$STICK"; mkdir -p "$STICK"; adb -s $S pull "$DIR" "$STICK" >/dev/null 2>&1; }
fresh_later_device() { $U reset >/dev/null; adb -s $S shell mkdir -p "$DIR"; adb -s $S push "$STICK/v1/." "$DIR/" >/dev/null 2>&1; $U start; sleep 2; }

echo "== set up a first device"
$U reset >/dev/null; $U start; sleep 3; $U start "--es add Milk"; $U start "--es passphrase first-passphrase-1"; sleep 9
has 'End-to-end encrypted' "sync is on"
$U tap "Add another device" >/dev/null; sleep 1
CODE=$($U screen | grep -o 'DS1\.[A-Za-z0-9_-]*')
[ -n "$CODE" ] && echo "PASS  the join code is shown as text (${#CODE} characters)" || { echo "FAIL  no join code text"; FAILS=$((FAILS + 1)); }
$U tap "Done" >/dev/null
save

echo "== a later device joins with the code typed into the passphrase box"
fresh_later_device
has 'Scan a code from another device' "a later device offers to scan"
hasnot 'Suggest one' "a later device does not offer to generate"
$U tap "Passphrase" >/dev/null; adb -s $S shell input text "DS1.not-a-real-code"; $U tap "Turn on" >/dev/null; sleep 2
has 'That join code is incomplete or mistyped' "a damaged code is refused with a reason"
adb -s $S shell input keyevent --longpress $(printf 'KEYCODE_DEL %.0s' $(seq 1 25)) >/dev/null 2>&1
$U start; sleep 1
fresh_later_device
$U tap "Passphrase" >/dev/null; adb -s $S shell input text "$CODE"; $U tap "Turn on" >/dev/null; sleep 6
has 'End-to-end encrypted' "joined with the code"
has 'text="Milk"' "the note from the first device arrived"

echo "== change the passphrase"
$U tap "Change passphrase" >/dev/null; sleep 1
$U tap "Suggest one" >/dev/null; sleep 1
NEW=$($U screen | grep -o -E '[a-z0-9]{4}(-[a-z0-9]{4}){5}' | head -1)
[ -n "$NEW" ] && echo "PASS  a new passphrase was suggested and shown" || { echo "FAIL  no suggested passphrase"; FAILS=$((FAILS + 1)); }
$U tap "Change" >/dev/null; sleep 5
echo "== after changing the passphrase"
$U tap "Not now" >/dev/null; sleep 1
has 'Passphrase changed' "the change is confirmed on screen"
$U start "--es add Bread" 2>/dev/null; $U start "--ez sync true" 2>/dev/null; sleep 4
has 'Nothing waiting to upload' "this device keeps syncing without being asked for anything"
save
fresh_later_device
$U start "--es passphrase first-passphrase-1" 2>/dev/null; sleep 6
has 'That passphrase does not match' "the old passphrase no longer joins"
$U start "--es passphrase $NEW" 2>/dev/null; sleep 8
has 'End-to-end encrypted' "the new passphrase joins"
has 'text="Bread"' "and the notes arrive"

echo "== a database restored from an older copy"
DB=/data/data/$PKG/databases
adb -s $S shell am force-stop $PKG
adb -s $S shell "run-as $PKG sh -c 'mkdir -p files/bak && cp databases/sample.db* files/bak/'"
$U start; sleep 2
$U start "--es add Eggs" 2>/dev/null; $U start "--ez sync true" 2>/dev/null; sleep 4
has 'Nothing waiting to upload' "a later change is uploaded (Eggs)"
adb -s $S shell am force-stop $PKG
adb -s $S shell "run-as $PKG sh -c 'rm databases/sample.db*; cp files/bak/sample.db* databases/'"
$U start; sleep 2
$U start "--es add Jam" 2>/dev/null; $U start "--ez sync true" 2>/dev/null; sleep 4
has 'ReplicaStateBehind' "the restored copy is detected and says so"
has 'Set this device up again' "the fix is offered as a button"
$U tap "Set this device up again" >/dev/null; sleep 6
has 'text="Synced"' "after the fix, sync works"
has 'Nothing waiting to upload' "the note written after the restore is uploaded"
for n in Milk Bread Eggs Jam; do has "text=\"$n\"" "note present: $n"; done
echo "$FAILS failed"; [ "$FAILS" -eq 0 ]
