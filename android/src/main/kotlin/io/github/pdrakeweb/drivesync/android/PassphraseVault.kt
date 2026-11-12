package io.github.pdrakeweb.drivesync.android

import android.app.Activity
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import java.security.SecureRandom

/**
 * Keeps the sync passphrase in the person's password manager (Google Password Manager, or any
 * other they have chosen), through Android's Credential Manager.
 *
 * With it, the person never has to invent, remember or retype the passphrase: the app can suggest
 * a strong one, offer to save it, and fetch it again on their next device. Every call here shows
 * system UI and does nothing unless the person agrees.
 *
 * **What it costs.** A passphrase saved in a password manager is only as private as that password
 * manager. If it is Google's and the encrypted data is in Google Drive, both halves sit in one
 * account. Offer this as a choice and say so; do not save silently. See
 * `docs/PASSPHRASE_AND_PASSWORD_MANAGERS.md`.
 *
 * @param label What the saved entry is called in the password manager, in the "username" slot.
 *   Use something the person will recognise, and keep it the same across versions of the app:
 *   it is how the entry is found again.
 */
public class PassphraseVault(private val label: String = "Sync passphrase") {
    public sealed interface SaveResult {
        public data object Saved : SaveResult

        /** The person closed the prompt without saving. */
        public data object Declined : SaveResult

        /** No password manager is set up on this device, or it refused. */
        public data class Unavailable(val reason: String) : SaveResult
    }

    public sealed interface FetchResult {
        public class Found(public val passphrase: String) : FetchResult

        /** Nothing is saved for this app, or the person closed the prompt. */
        public data object None : FetchResult

        public data class Unavailable(val reason: String) : FetchResult
    }

    /** Offers to save [passphrase]. Needs an [Activity], because the system shows a prompt on top of it. */
    public suspend fun save(activity: Activity, passphrase: String): SaveResult = try {
        CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(label, passphrase))
        SaveResult.Saved
    } catch (e: CreateCredentialCancellationException) {
        SaveResult.Declined
    } catch (e: CreateCredentialException) {
        SaveResult.Unavailable(e.type)
    }

    /** Asks the password manager for a passphrase saved for this app. */
    public suspend fun fetch(activity: Activity): FetchResult = try {
        val response = CredentialManager.create(activity)
            .getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
        when (val credential = response.credential) {
            is PasswordCredential -> FetchResult.Found(credential.password)
            else -> FetchResult.None
        }
    } catch (e: NoCredentialException) {
        FetchResult.None
    } catch (e: GetCredentialCancellationException) {
        FetchResult.None
    } catch (e: GetCredentialException) {
        FetchResult.Unavailable(e.type)
    }

    public companion object {
        // No 0/O, 1/l/I: the passphrase may still be read aloud or copied by hand once.
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val GROUPS = 6
        private const val GROUP_SIZE = 4

        /**
         * A random passphrase strong enough that guessing is hopeless (about 118 bits), in a shape
         * a person can still read: `k7m2-9xqp-...`. Generated on the device; nothing is sent anywhere.
         */
        public fun suggest(random: SecureRandom = SecureRandom()): String =
            (0 until GROUPS).joinToString("-") {
                (0 until GROUP_SIZE).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            }
    }
}
