#!/usr/bin/env bash
# Scenario 01: two emulators sync encrypted notes through a local folder.
#
# The sync folder lives in each app's own external files directory. Two emulators share no file
# system, so this script plays the part of a USB stick: it copies the folder from one device to
# the other with adb. Every file has exactly one writer, so merging the two folders is a plain copy.
#
# Usage: tests/journey-two-emulators.sh <serial-a> <serial-b> [local|drive] [google-account]
#   local (default): sync through a folder, carried between devices by this script.
#   drive: sync through Google Drive. Both devices must be signed in to one Google account that
#          has already allowed the app (tap "Use Google Drive" once). Each run uses a fresh root.
# The sample debug APK must already be installed.
# Exit code 0 only if every check passed.
set -u
export MSYS_NO_PATHCONV=1
A="${1:?serial of device A}"
B="${2:?serial of device B}"
BACKEND="${3:-local}"
ROOT="drivesync-journey-$(date +%s)/"
PAUSE=2; [ "$BACKEND" = drive ] && PAUSE=7
# For drive: the Google account to use, so that no account chooser is shown. Not stored anywhere.
ACCOUNT="${4:-}"
PKG=io.github.pdrakeweb.drivesync.sample
DIR=/sdcard/Android/data/$PKG/files/drivesync/v1
PASSPHRASE=correct-horse-battery
WORK="$(mktemp -d)"
# adb.exe on Windows needs a Windows path, not a Git Bash one.
command -v cygpath >/dev/null 2>&1 && WORK="$(cygpath -m "$WORK")"
FAILS=0

# Everything on screen that is part of the fixed interface rather than a note.
CHROME='drivesync sample|Synced|Not synced yet|Syncing|Sync now|New note|Add|Rename|Delete|No notes yet.*'
CHROME="$CHROME"'|.*waiting to upload|Nothing waiting to upload|End-to-end encrypted|Add another device'
CHROME="$CHROME"'|Sync is off on this device|Choose a passphrase.*|Passphrase|Turn on|Working'
CHROME="$CHROME"'|Storage: .*|Use Google Drive|Google Drive was not connected.|Could not reach.*'
CHROME="$CHROME"'|Saved in your password manager as|Sync passphrase|Suggest one'
CHROME="$CHROME"'|Change passphrase|Scan a code from another device|Passphrase changed.*|Set this device up again'
CHROME="$CHROME"'|This device.s copy was restored.*|That join code.*'
CHROME="$CHROME"'|.*will upload once sync is on|That passphrase does not match.*|Use at least 8 characters.'

# app <serial> [extras as one string, quoted for the device shell]
app() { adb -s "$1" shell "am start -n $PKG/.MainActivity ${2:-}" >/dev/null 2>&1; sleep 2; }
sync_now() { app "$1" "--ez sync true"; sleep $PAUSE; }
# All text on screen, one item per line.
screen() { adb -s "$1" shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" | grep -o 'text="[^"]*"' | sed 's/^text="//; s/"$//' | grep -v '^$'; }
notes() { screen "$1" | grep -v -x -E "$CHROME" | sort | paste -sd, -; }
carry() { # carry <from> <to>: copy the sync folder across, as a USB stick would
  [ "$BACKEND" = drive ] && return 0   # Drive is the shared storage; nothing to carry
  rm -rf "$WORK/stick"; mkdir -p "$WORK/stick"
  adb -s "$1" pull "$DIR" "$WORK/stick" >/dev/null 2>&1
  adb -s "$2" shell mkdir -p "$DIR"
  adb -s "$2" push "$WORK/stick/v1/." "$DIR/" >/dev/null 2>&1
}
exchange() { sync_now "$A"; sync_now "$B"; carry "$A" "$B"; carry "$B" "$A"; sync_now "$A"; sync_now "$B"; carry "$A" "$B"; carry "$B" "$A"; sync_now "$A"; sync_now "$B"; }
check() { # check <name> <expected> <actual>
  if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1: expected [$2] but saw [$3]"; FAILS=$((FAILS + 1)); fi
}
contains() { # contains <name> <serial> <regex matching one whole line of screen text>
  if screen "$2" | grep -q -x -E "$3"; then echo "PASS  $1"; else echo "FAIL  $1: [$3] not on screen of $2"; FAILS=$((FAILS + 1)); fi
}

echo "== fresh installs"
for d in "$A" "$B"; do adb -s "$d" shell pm clear "$PKG" >/dev/null; adb -s "$d" shell rm -rf "$DIR"; adb -s "$d" logcat -c -b crash; app "$d"; done
sleep 2
contains "empty state says it is empty (A)" "$A" 'No notes yet.*'
contains "sync is off before a passphrase is set (A)" "$A" 'Sync is off on this device'
if [ "$BACKEND" = drive ]; then
  for d in "$A" "$B"; do app "$d" "--es backend drive --es root $ROOT ${ACCOUNT:+--es account $ACCOUNT}"; done
  sleep 6
  contains "storage is Google Drive (A)" "$A" "Storage: Google Drive"
  contains "storage is Google Drive (B)" "$B" "Storage: Google Drive"
fi

echo "== 0. passphrase: set on A, wrong one refused on B, right one joins"
app "$A" "--es add Milk"
contains "a change made before setup is counted, not lost (A)" "$A" '2 changes will upload once sync is on'
app "$A" "--es passphrase $PASSPHRASE"; sleep $((PAUSE * 2 + 1))
contains "A reports end-to-end encryption" "$A" 'End-to-end encrypted'
contains "the earlier change uploaded (A)" "$A" 'Nothing waiting to upload'
carry "$A" "$B"
app "$B" "--es passphrase a-wrong-guess-here"; sleep $((PAUSE * 2 + 1))
contains "B refuses a wrong passphrase and says why" "$B" 'That passphrase does not match.*'
check "B shows nothing without the key" "" "$(notes "$B")"
app "$B" "--es passphrase $PASSPHRASE"; sleep $((PAUSE * 2 + 1))
contains "B joined with the right passphrase" "$B" 'End-to-end encrypted'

echo "== 1. a note written on A reaches B"
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

echo "== 4. killed with unsynced changes; the key survives the restart"
app "$A" "--es add Butter"
adb -s "$A" shell am force-stop "$PKG"
app "$A"; sleep 2
contains "no passphrase needed again after a restart (A)" "$A" 'End-to-end encrypted'
exchange
check "B received the change made before the kill" "Butter,Eggs,Oat milk" "$(notes "$B")"
contains "both end in the synced state (B)" "$B" 'Synced'

echo "== storage: invariant C-2"
if [ "$BACKEND" = drive ]; then
  # The app-data folder cannot be read from outside the app, so the app lists it for us.
  adb -s "$A" logcat -c
  app "$A" "--ez dump true"; sleep $PAUSE
  STORED=$(adb -s "$A" logcat -d -s drivesync-sample:I | grep -o 'STORED .*' | tail -n 1 | tr -d '\r')
  COUNT=$(echo "$STORED" | cut -d' ' -f2)
  check "objects really are in Google Drive" "yes" "$([ "${COUNT:-0}" -ge 8 ] && echo yes || echo "no, only ${COUNT:-0}")"
  BAD=$(echo "$STORED" | cut -d' ' -f3- | tr ' ' '\n' | grep -v -E '^(meta\.json|replicas/[0-9a-f-]{36}\.json|batches/[0-9a-f-]{36}/[0-9]{12}-[0-9a-f]{16}\.bin)$')
  check "file names in Drive are ids, numbers and tags only" "" "$BAD"
  echo "SKIP  content of stored files not inspected (not reachable from outside the app; covered by the local-folder run)"
else
BAD=$(adb -s "$A" shell "cd $DIR && find . -type f" | tr -d '\r' | grep -v -E '^\./(meta\.json|replicas/[0-9a-f-]{36}\.json|batches/[0-9a-f-]{36}/[0-9]{12}-[0-9a-f]{16}\.bin)$')
check "file names are ids, numbers and tags only" "" "$BAD"
rm -rf "$WORK/final"; mkdir -p "$WORK/final"; adb -s "$A" pull "$DIR" "$WORK/final" >/dev/null 2>&1
COUNT=$(find "$WORK/final" -type f | wc -l | tr -d ' ')
check "stored files were inspected" "yes" "$([ "$COUNT" -ge 8 ] && echo yes || echo "no, only $COUNT")"
LEAKS=$(grep -r -l -a -E 'Milk|Eggs|Butter|Bread|Oat|Rye|notes|title|body' "$WORK/final" | wc -l | tr -d ' ')
check "no note text, table or column name in any stored file" "0" "$LEAKS"
KEYS=$(grep -o '"[a-zA-Z]*":' "$WORK/final/v1/meta.json" | tr -d '":' | sort | paste -sd, -)
check "meta.json holds only the wrapped key and derivation parameters" "alg,created,format,iterations,kdf,memoryKib,parallelism,params,salt,version,wrappedKey" "$KEYS"
fi

for d in "$A" "$B"; do
  if adb -s "$d" logcat -d -b crash 2>/dev/null | grep -q "$PKG"; then echo "FAIL  crash logged on $d"; FAILS=$((FAILS + 1)); else echo "PASS  no crash on $d"; fi
done
rm -rf "$WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "RESULT: PASS"; else echo "RESULT: FAIL ($FAILS)"; fi
exit "$FAILS"
