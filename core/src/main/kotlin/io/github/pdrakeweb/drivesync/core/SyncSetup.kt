package io.github.pdrakeweb.drivesync.core

import java.util.Base64

/**
 * Turning sync on for a device: creating the shared key on the first device, or opening it with
 * the passphrase on a later one. Separate from [SyncEngine], which only runs once a key is held.
 */
public class SyncSetup(
    private val blobs: BlobStore,
    private val keys: KeyManager,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    public enum class Status {
        /** No `meta.json` in storage: no device has set up sync here yet. */
        NotSetUp,

        /** Sync exists in storage, but this device does not hold the key. */
        NeedsPassphrase,

        /** This device holds a key. */
        Ready,
    }

    public enum class Result {
        /** This device now holds the key. */
        Ready,

        /** The passphrase does not open the key in storage. Nothing changed. */
        WrongPassphrase,

        /** There is nothing in storage to join; create it first. */
        NotSetUp,
    }

    /** Throws [SyncException] when storage cannot be reached. */
    public suspend fun status(): Status = when {
        keys.state() == KeyState.Ready -> Status.Ready
        blobs.get(Paths.META) == null -> Status.NotSetUp
        else -> Status.NeedsPassphrase
    }

    /**
     * First device: makes a new data key, wrapped by [passphrase], and publishes `meta.json`.
     * If another device got there first, joins that one instead, with the same passphrase.
     */
    public suspend fun create(passphrase: CharArray): Result {
        if (blobs.get(Paths.META) != null) return join(passphrase)
        val wrapped = keys.create(passphrase)
        val meta = MetaFile(
            created = wallClock(),
            kdf = MetaFile.Kdf(wrapped.kdfAlgorithm, wrapped.kdfParams, encode(wrapped.salt)),
            wrappedKey = encode(wrapped.wrappedKey),
        )
        return when (blobs.putIfAbsent(Paths.META, WireCodec.encodeMeta(meta))) {
            PutResult.Created -> Result.Ready
            // Lost the race: the key just made was never used for anything. Take the winner's.
            PutResult.AlreadyExists -> join(passphrase)
        }
    }

    /** Later device: opens the key in storage with [passphrase]. */
    public suspend fun join(passphrase: CharArray): Result {
        val bytes = blobs.get(Paths.META) ?: return Result.NotSetUp
        val meta = WireCodec.decodeMeta(bytes)
        val wrapped = try {
            WrappedKey(meta.kdf.alg, meta.kdf.params, decode(meta.kdf.salt), decode(meta.wrappedKey))
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncError.Corrupt(Paths.META), e)
        }
        return try {
            keys.unlock(passphrase, wrapped)
            Result.Ready
        } catch (e: SyncException) {
            if (e.error == SyncError.KeyMissing) Result.WrongPassphrase else throw e
        }
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)
}
