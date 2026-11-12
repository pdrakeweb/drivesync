# The sync passphrase and password managers

For apps built on drivesync. The passphrase is the one thing a person must reproduce on every
device, and the one thing that cannot be recovered if lost (DESIGN.md section 7). An app should
not make them invent it, remember it, or type it twice. Android's Credential Manager lets the
person's password manager (Google Password Manager, or whichever they have chosen) do all three.

## What to build

1. **Suggest a strong passphrase.** `PassphraseVault.suggest()` returns one generated on the
   device, about 118 bits, in readable groups (`k7m2-9xqp-...`). Show it in the clear when you
   fill it in: the person has never seen it.
2. **Offer to save it, after it worked.** Once `SyncSetup.create` returns `Ready`, call
   `PassphraseVault().save(activity, passphrase)`. The system shows its own prompt; the result
   is `Saved`, `Declined` or `Unavailable`. Say which happened. Never offer to save a passphrase
   that has not just been proven correct, or a typo ends up in the vault.
3. **Offer to fetch it on the next device.** `PassphraseVault().fetch(activity)` shows the
   system's chooser and returns `Found(passphrase)`, `None` or `Unavailable`. Pass a found
   passphrase straight to `SyncSetup.join`.
4. **Mark the field for autofill**, so a password manager can fill it even without the buttons.
   In Compose: `Modifier.semantics { contentType = ContentType.Password }`. With Views:
   `android:autofillHints="password"`.
5. **Keep manual entry.** Not everyone has a password manager, and some will decline.

```kotlin
val vault = PassphraseVault(label = "Sync passphrase")   // the entry's name in the password manager

// First device, or any device where the person typed or generated the passphrase:
if (setup.create(passphrase.toCharArray()) == SyncSetup.Result.Ready) {
    when (vault.save(activity, passphrase)) {
        PassphraseVault.SaveResult.Saved -> show("Saved to your password manager.")
        PassphraseVault.SaveResult.Declined -> show("Not saved. Keep it somewhere safe.")
        is PassphraseVault.SaveResult.Unavailable -> show("No password manager on this device.")
    }
}

// A later device:
when (val fetched = vault.fetch(activity)) {
    is PassphraseVault.FetchResult.Found -> setup.join(fetched.passphrase.toCharArray())
    PassphraseVault.FetchResult.None -> show("No saved passphrase was chosen.")
    is PassphraseVault.FetchResult.Unavailable -> show("No password manager on this device.")
}
```

`PassphraseVault` is in the `:android` module. It needs an `Activity`, because the system draws
its prompt on top of one.

## Two things that go wrong

- **Run this work in a scope that outlives the form.** The passphrase form usually leaves the
  screen the moment sync turns on. Work started from the form's own coroutine scope is cancelled
  at that instant, which cancels the first sync and the save offer. Start it from the activity
  or a view model. The sample had exactly this bug.
- **Keep the label stable.** The password manager files the entry under the app and the label.
  Change the label and earlier entries are still there but look like a different account.

## What the person is trading

Tell them, in the app, before they choose:

- Saved in a password manager, the passphrase is as private as that password manager. If it is
  Google's and the data is in Google Drive, the encrypted data and the means to read it are in
  one account. End-to-end encryption then protects against someone who gets at the Drive files
  alone, not against someone who controls the whole account.
- Not saved, the passphrase exists only in the person's memory or wherever they wrote it. Lose
  it and the synced copy cannot be read, by anyone.

Neither is wrong. For most people losing the passphrase is the likelier accident, so offering to
save is the right default; saving without asking is not.

## What was and was not tested

On an Android 17 emulator with a Google account: the generator; the save prompt appearing after a
successful setup ("Save password to sign in to ...?", entry "Sync passphrase"); the declined path
and its message; the fetch chooser opening. **Not completed:** saving an entry and fetching it
back, which needs a person to approve Google's prompts on their own account. Older Android
versions were not tested; `credentials-play-services-auth` is included so that Credential Manager
reaches Google Password Manager on Android 13 and earlier.
