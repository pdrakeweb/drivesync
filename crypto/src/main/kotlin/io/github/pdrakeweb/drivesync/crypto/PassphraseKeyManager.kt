package io.github.pdrakeweb.drivesync.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import io.github.pdrakeweb.drivesync.core.KeyManager
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.Paths
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.WrappedKey
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Where a device keeps the unwrapped data key between launches. On Android this is backed by the
 * Keystore (`:android` module); the passphrase is then needed only once per device.
 */
public interface KeyStorage {
    public fun load(): ByteArray?
    public fun store(key: ByteArray)
    public fun clear()
}

/** Keeps the key in memory only: for tests and tools. */
public class InMemoryKeyStorage : KeyStorage {
    private var key: ByteArray? = null
    override fun load(): ByteArray? = key?.copyOf()
    override fun store(key: ByteArray) {
        this.key = key.copyOf()
    }
    override fun clear() {
        key = null
    }
}

/** Cost of turning a passphrase into a key. Higher memory makes guessing passphrases slower. */
public data class Argon2Cost(val memoryKib: Int, val iterations: Int, val parallelism: Int) {
    init {
        require(memoryKib in 8..MAX_MEMORY_KIB && iterations in 1..MAX_ITERATIONS && parallelism in 1..MAX_PARALLELISM) {
            "Argon2 cost out of range"
        }
    }

    public companion object {
        internal const val MAX_MEMORY_KIB = 1 shl 20 // 1 GiB
        internal const val MAX_ITERATIONS = 16
        internal const val MAX_PARALLELISM = 8

        /** 64 MiB, 3 passes: about a second on a mid-range phone. */
        public val Default: Argon2Cost = Argon2Cost(memoryKib = 64 * 1024, iterations = 3, parallelism = 1)
    }
}

/**
 * The [KeyManager] for real use (DESIGN.md section 7).
 *
 * - One random 256-bit **data key** encrypts every object with XChaCha20-Poly1305 (authenticated
 *   encryption, random 192-bit nonce), bound to the object's path so an object moved elsewhere is
 *   rejected.
 * - `meta.json` stores that key **wrapped** by a key derived from the passphrase with Argon2id.
 * - File-name fingerprints are HMAC-SHA-256 under a key derived from the data key.
 */
public class PassphraseKeyManager(
    private val storage: KeyStorage,
    private val cost: Argon2Cost = Argon2Cost.Default,
    private val random: SecureRandom = SecureRandom(),
) : KeyManager {
    @Volatile
    private var cached: Keys? = null

    private class Keys(dataKey: ByteArray) {
        val aead = XChaCha20Poly1305(dataKey)
        val fingerprintKey = SecretKeySpec(hmac(SecretKeySpec(dataKey, HMAC), FINGERPRINT_LABEL), HMAC)
    }

    override suspend fun state(): KeyState = if (keysOrNull() != null) KeyState.Ready else KeyState.NeedsPassphrase

    override suspend fun create(passphrase: CharArray): WrappedKey {
        val dataKey = ByteArray(KEY_BYTES).also(random::nextBytes)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val wrapped = XChaCha20Poly1305(derive(passphrase, salt, cost)).encrypt(dataKey, WRAP_CONTEXT)
        adopt(dataKey)
        return WrappedKey(ARGON2ID, costParams(cost), salt, wrapped)
    }

    override suspend fun unlock(passphrase: CharArray, wrapped: WrappedKey) {
        val dataKey = try {
            if (wrapped.kdfAlgorithm != ARGON2ID) throw SyncException(SyncError.UnsupportedFormat(Paths.META, 0))
            // meta.json is not authenticated before this point: refuse absurd costs rather than
            // let a tampered file exhaust memory.
            val storedCost = try {
                Argon2Cost(
                    wrapped.kdfParams.getValue("memoryKib").toInt(),
                    wrapped.kdfParams.getValue("iterations").toInt(),
                    wrapped.kdfParams.getValue("parallelism").toInt(),
                )
            } catch (e: RuntimeException) {
                throw SyncException(SyncError.Corrupt(Paths.META), e)
            }
            if (wrapped.salt.size !in 8..64) throw SyncException(SyncError.Corrupt(Paths.META))
            XChaCha20Poly1305(derive(passphrase, wrapped.salt, storedCost)).decrypt(wrapped.wrappedKey, WRAP_CONTEXT)
        } catch (e: GeneralSecurityException) {
            forget()
            throw SyncException(SyncError.KeyMissing, e)
        } catch (e: SyncException) {
            forget()
            throw e
        }
        if (dataKey.size != KEY_BYTES) {
            forget()
            throw SyncException(SyncError.Corrupt(Paths.META))
        }
        adopt(dataKey)
    }

    override suspend fun encrypt(path: String, plain: ByteArray): ByteArray {
        val sealed = keys().aead.encrypt(plain, path.encodeToByteArray())
        return ByteArray(1 + sealed.size).also {
            it[0] = OBJECT_VERSION
            sealed.copyInto(it, 1)
        }
    }

    override suspend fun decrypt(path: String, cipher: ByteArray): ByteArray {
        val keys = keys()
        if (cipher.isEmpty() || cipher[0] != OBJECT_VERSION) throw SyncException(SyncError.Corrupt(path))
        return try {
            keys.aead.decrypt(cipher.copyOfRange(1, cipher.size), path.encodeToByteArray())
        } catch (e: GeneralSecurityException) {
            throw SyncException(SyncError.Corrupt(path), e)
        }
    }

    override suspend fun fingerprint(plain: ByteArray): String =
        hmac(keys().fingerprintKey, plain).take(8).joinToString("") { "%02x".format(it) }

    /**
     * The data key as text, for showing as a QR code on a device that already has it, so another
     * device can join without typing the passphrase. Anyone who sees it can read the synced data.
     */
    public fun exportJoinCode(): String {
        val key = storage.load() ?: throw SyncException(SyncError.KeyMissing)
        return JOIN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(key + checksum(key))
    }

    /** Takes the key from a join code. Returns false, changing nothing, if the code is not valid. */
    public fun importJoinCode(code: String): Boolean {
        val trimmed = code.trim()
        if (!trimmed.startsWith(JOIN_PREFIX)) return false
        val bytes = try {
            Base64.getUrlDecoder().decode(trimmed.removePrefix(JOIN_PREFIX))
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (bytes.size != KEY_BYTES + CHECK_BYTES) return false
        val key = bytes.copyOf(KEY_BYTES)
        if (!MessageDigest.isEqual(checksum(key), bytes.copyOfRange(KEY_BYTES, bytes.size))) return false
        adopt(key)
        return true
    }

    /** Removes the key from this device. The cloud copy and other devices are unaffected. */
    public fun forget() {
        storage.clear()
        cached = null
    }

    private fun adopt(dataKey: ByteArray) {
        storage.store(dataKey)
        cached = Keys(dataKey)
    }

    private fun keysOrNull(): Keys? = cached ?: storage.load()?.let { key -> Keys(key).also { cached = it } }

    private fun keys(): Keys = keysOrNull() ?: throw SyncException(SyncError.KeyMissing)

    private fun derive(passphrase: CharArray, salt: ByteArray, cost: Argon2Cost): ByteArray {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(encoded.remaining()).also { ByteBuffer.wrap(it).put(encoded) }
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(cost.memoryKib)
            .withIterations(cost.iterations)
            .withParallelism(cost.parallelism)
            .build()
        val out = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, out)
        password.fill(0)
        return out
    }

    private fun costParams(cost: Argon2Cost) = mapOf(
        "memoryKib" to cost.memoryKib.toString(),
        "iterations" to cost.iterations.toString(),
        "parallelism" to cost.parallelism.toString(),
        "version" to "19",
    )

    private fun checksum(key: ByteArray) = MessageDigest.getInstance("SHA-256").digest(key).copyOf(CHECK_BYTES)

    private companion object {
        const val ARGON2ID = "argon2id"
        const val HMAC = "HmacSHA256"
        const val KEY_BYTES = 32
        const val SALT_BYTES = 16
        const val CHECK_BYTES = 4
        const val OBJECT_VERSION: Byte = 1
        const val JOIN_PREFIX = "DS1."
        val WRAP_CONTEXT = "drivesync/meta/v1".encodeToByteArray()
        val FINGERPRINT_LABEL = "drivesync/fingerprint/v1".encodeToByteArray()

        fun hmac(key: SecretKeySpec, data: ByteArray): ByteArray = Mac.getInstance(HMAC).apply { init(key) }.doFinal(data)
    }
}
