# Device scenarios

Scenarios that need real Android (emulators or hardware). Everything that can run on the JVM is a
Gradle test instead (`./gradlew build`).

## Harness

- Two emulators or devices, any Android 8.0+ image. The sample app's debug build installed on both:
  `./gradlew :sample:assembleDebug`, then `adb -s <serial> install -r sample/build/outputs/apk/debug/sample-debug.apk`.
- `adb` on the PATH (`<Android SDK>/platform-tools`).
- The debug build accepts test commands as intent extras, so a script never depends on screen
  coordinates: `--es add <title>`, `--es rename <title> --es to <new title>`, `--es delete <title>`,
  `--ez sync true`. Release builds ignore them.
- Results are read from the screen with `uiautomator dump` (Android's accessibility snapshot of
  what is displayed), so a check passes only if the person would actually see the data.

The emulators used for the recorded run were two copies of the stock "Medium Phone" definition
(Android 17, API 37, x86_64), started headless:

```
emulator -avd drivesync_a -port 5556 -no-window -no-audio -no-snapshot
emulator -avd drivesync_b -port 5558 -no-window -no-audio -no-snapshot
```

## 01 · Two emulators sync through a local folder

`tests/journey-two-emulators.sh <serial-a> <serial-b>` — exit code 0 only if every check passes.

Each app syncs to a folder in its own storage. Two emulators share no file system, so the script
plays the part of a USB stick and copies the folder across with `adb pull` / `adb push`. Because
every file has exactly one writer, merging two copies of the folder is a plain copy.

| Step | Expected |
|---|---|
| Fresh install | "No notes yet"; nothing invented |
| Add a note on A | "2 changes waiting to upload" (title and body), then "Nothing waiting to upload" after sync |
| Carry the folder to B, sync | B shows the note |
| A and B each add a note, B renames A's note, while apart | Both show all three, with the rename |
| A deletes a note while B renames the same note | The note is gone on both (deletes win) |
| A adds a note and the app is force-stopped before any sync | After restart and exchange, B has it |
| Stored file names | Only replica ids, sequence numbers and hashes |
| Crash log | Empty on both |

Recorded run: `RESULTS-01-two-emulators.txt` (11 Nov 2026, all PASS), final screen of device B in
`01-device-b-final.png`.

## Not yet covered on devices

- Sync through Google Drive (milestone S3; needs an OAuth client and a test account).
- Background sync firing on its own schedule (WorkManager is covered by Robolectric tests in
  `:android`; the journey triggers cycles explicitly so that it is deterministic).
- A tablet-sized screen.
