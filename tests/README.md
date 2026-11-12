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

## 01 · Two emulators sync encrypted notes through a local folder

`tests/journey-two-emulators.sh <serial-a> <serial-b>` — exit code 0 only if every check passes.

Each app syncs to a folder in its own storage. Two emulators share no file system, so the script
plays the part of a USB stick and copies the folder across with `adb pull` / `adb push`. Because
every file has exactly one writer, merging two copies of the folder is a plain copy.

| Step | Expected |
|---|---|
| Fresh install | "No notes yet"; "Sync is off on this device" |
| Add a note on A before any passphrase | "2 changes will upload once sync is on" |
| Set a passphrase on A | "End-to-end encrypted", "Nothing waiting to upload" |
| Carry the folder to B, enter a wrong passphrase | Refused with a reason; no notes shown |
| Enter the right passphrase on B | B shows the note |
| A and B each add a note, B renames A's note, while apart | Both show all three, with the rename |
| A deletes a note while B renames the same note | The note is gone on both (deletes win) |
| A adds a note and the app is force-stopped before any sync | After restart no passphrase is asked for again; after exchange B has it |
| Stored files | Names are ids, numbers and tags only; no note text, table or column name in any file; `meta.json` has only the wrapped key and derivation parameters |
| Crash log | Empty on both |

Recorded run: `RESULTS-01-two-emulators.txt` (11 Nov 2026, 22 checks, all PASS), final screen of device B in
`01-device-b-final.png`.

## 02 · Two emulators sync through a real Google Drive account

`tests/journey-two-emulators.sh <serial-a> <serial-b> drive <google-account>`

The same steps and checks as scenario 01, with Google Drive's app-data folder as the storage and
nothing carried between the devices. Each run uses a fresh root name inside the app-data folder,
so earlier runs never interfere.

One-time setup, by a person:

1. A Google Cloud project with the Drive API enabled, a consent screen in Testing mode with the
   test account listed under test users, and an OAuth client of type **Android** for package
   `io.github.pdrakeweb.drivesync.sample` and the SHA-1 of the certificate that signs the debug
   build (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`). There is no
   client secret and nothing to put in the repository.
2. On each emulator: sign in to the test Google account (Settings, Accounts), open the sample, tap
   **Use Google Drive**, and approve the consent screen once. An emulator keeps its accounts
   between runs. A second emulator can be made by copying the first one's `.avd` folder while both
   are shut down, which copies the signed-in account too.

The account is passed on the command line so that Google does not show its account chooser, which
a script cannot answer; it is not stored anywhere. What differs from scenario 01: stored objects
are listed by the app itself (the app-data folder cannot be read from outside it), so file names
are checked but file contents are not; the local-folder run covers contents.

Recorded run: `RESULTS-02-google-drive.txt` (11 Nov 2026, 22 checks, all PASS), final screen of
device B in `02-device-b-drive-final.png`.

## Not yet covered on devices

- The `BlobStore` contract suite against real Drive (`./gradlew :backend-drive:test -PdriveToken=<token>`)
  has not been run: it needs a raw access token, which the journey never exposes.
- Scanning a join QR code (the sample draws one; no scanner is built).
- Android 8.0 and 8.1 (API 26, 27): they ship an old Bouncy Castle under the same package name as the one used for Argon2id; this must be run on such an image before those versions are claimed.
- Background sync firing on its own schedule (WorkManager is covered by Robolectric tests in
  `:android`; the journey triggers cycles explicitly so that it is deterministic).
- A tablet-sized screen.
