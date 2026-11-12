# The sync passphrase and password managers

For apps built on drivesync. The passphrase is the one thing a person must reproduce on every
device, and the one thing that cannot be recovered if lost (DESIGN.md section 7). An app should
not make them remember it or type it twice. The person's password manager (Google Password
Manager, or whichever they have chosen) can keep it, and Android's autofill framework lets it do
so through an ordinary password field, with no extra buttons in the app.

## What to build: a plain password form the system recognises

Three things make the native save and fill offers appear. All three were needed in the sample;
each was found by watching the password manager refuse without it.

1. **Mark the passphrase field.** In Compose,
   `Modifier.semantics { contentType = ContentType.NewPassword }` on the first device (nothing in
   storage yet: `SyncSetup.status() == NotSetUp`) and `ContentType.Password` on later devices.
   With Views, `android:autofillHints="newPassword"` or `"password"`.
2. **Put a username beside it.** Google Password Manager returned nothing at all for a password
   field on its own. A read-only field holding a fixed name, marked `ContentType.Username`, was
   enough. The name is what the entry is filed under, so choose one the person will recognise
   and never change it.
3. **Report the submission while the form is still on screen**, and only when the passphrase was
   accepted: `getSystemService(AutofillManager::class.java).commit()`. Do not clear the field
   first; the password manager reads it at that moment. Then let the form go away.

```kotlin
OutlinedTextField(
    "Sync passphrase", {}, readOnly = true,
    modifier = Modifier.semantics { contentType = ContentType.Username },
)
OutlinedTextField(
    passphrase, { passphrase = it },
    modifier = Modifier.semantics {
        contentType = if (firstDevice) ContentType.NewPassword else ContentType.Password
    },
    visualTransformation = PasswordVisualTransformation(),
)

// On submit, in the activity's or view model's scope, not the form's:
if (setup.create(passphrase.toCharArray()) == SyncSetup.Result.Ready) {
    getSystemService(AutofillManager::class.java)?.commit()   // the system now offers to save
    // ...then update state so the form leaves the screen, and run the first sync
}
```

## Two things that go wrong

- **Run the submit work in a scope that outlives the form.** The form leaves the screen the
  moment sync turns on. Work started from the form's own coroutine scope is cancelled at that
  instant, which cancels the first sync. The sample had exactly this bug.
- **Never commit a passphrase that was refused**, or a typo is offered for saving.

## What the system did and did not do (Android 17 emulator, Google Password Manager)

| | Result |
|---|---|
| Offer to save on submit | **Yes.** "Save password to Google Password Manager", username "Sync passphrase" |
| Offer the saved passphrase on another device | The password manager returned a fill entry for the form; choosing it was not tried (it needs an entry to have been saved by a person) |
| Suggest a strong passphrase | **No.** Nothing was offered for the field, marked as a new password or not |
| A password field with no username beside it | Ignored entirely: no fill, no save |

So native handling covers saving and filling, but not generating, at least with this password
manager on this version. Other password managers and older Android versions were not tried.

## Generating one: a single button, only when it helps

Because the system did not offer to generate, the sample shows a **Suggest one** button, under
two conditions:

- only on the first device (`SyncSetup.status() == NotSetUp`). On a later device the person must
  enter the passphrase that already exists; a freshly generated one would be refused;
- only while the field is empty, so it never competes with what the person is typing.

It fills the field with `PassphraseVault.suggest()` and shows it in the clear, since the person
has never seen it. If they empty the field and type their own, it is hidden again. Saving the
generated passphrase is then the password manager's ordinary offer on submit: no second button.

```kotlin
if (firstDevice && passphrase.isEmpty()) {
    TextButton(onClick = { passphrase = PassphraseVault.suggest(); revealed = true }) { Text("Suggest one") }
}
```

## If the app needs more: `PassphraseVault`

`PassphraseVault` (in the `:android` module) is an explicit alternative built on Credential
Manager, for an app that wants its own controls rather than relying on what the system offers:

- `PassphraseVault.suggest()` generates a passphrase on the device (about 118 bits, in readable
  groups). This is the piece with no native equivalent above.
- `save(activity, passphrase)` and `fetch(activity)` show the system's Credential Manager
  sheets and return what the person chose.

The sample uses only `suggest()`. Prefer the plain form for saving and filling; reach for
`save` and `fetch` when the form cannot have a username field.

## What the person is trading

Tell them, in the app, before they choose:

- Saved in a password manager, the passphrase is as private as that password manager. If it is
  Google's and the data is in Google Drive, the encrypted data and the means to read it are in
  one account. End-to-end encryption then protects against someone who gets at the Drive files
  alone, not against someone who controls the whole account.
- Not saved, the passphrase exists only in the person's memory or wherever they wrote it. Lose
  it and the synced copy cannot be read, by anyone.

The system's prompt lets them decline, which is the right shape: an offer, never a silent save.
