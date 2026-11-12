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

    /**
     * Sets a new passphrase from a device that holds the key: for a forgotten passphrase, or to
     * change one. Devices already set up are unaffected and are not asked for anything; a device
     * joining later needs the new passphrase, and the old one stops working. The data key stays
     * the same, so this does not shut out someone who already has the key or a join code.
     *
     * Also restores `meta.json` if storage has lost it. If two devices do this at the same time,
     * whichever wrote last decides the passphrase.
     *
     * Throws [SyncException] with [SyncError.KeyMissing] when this device holds no key (storage is
     * then untouched), or with a storage error when the write fails.
     */
    public suspend fun changePassphrase(passphrase: CharArray) {
        val wrapped = keys.rewrap(passphrase)
        val created = blobs.get(Paths.META)?.let { runCatching { WireCodec.decodeMeta(it).created }.getOrNull() } ?: wallClock()
        val meta = MetaFile(
            created = created,
            kdf = MetaFile.Kdf(wrapped.kdfAlgorithm, wrapped.kdfParams, encode(wrapped.salt)),
            wrappedKey = encode(wrapped.wrappedKey),
        )
        // The one deliberate exception to "meta.json is written once" (DESIGN.md section 6).
        blobs.putOwned(Paths.META, WireCodec.encodeMeta(meta))
    }

    /**
     * Whether the key this device holds opens what is in storage. Use it after taking a key from a
     * join code, which unlike a passphrase is not checked against `meta.json`: a well-formed code
     * from some other sync set would otherwise leave the device unable to read anything.
     *
     * True when storage holds nothing to check against. False when no device file can be opened;
     * the app should then discard the key and say the code belongs elsewhere.
     * Throws [SyncException] when storage cannot be reached or this device holds no key.
     */
    public suspend fun keyOpensStorage(): Boolean {
        if (keys.state() != KeyState.Ready) throw SyncException(SyncError.KeyMissing)
        val files = blobs.list(Paths.REPLICAS).items.filter { Paths.parseReplicaFile(it.path) != null }.take(KEY_CHECK_FILES)
        var checked = 0
        for (file in files) {
            val bytes = blobs.get(file.path) ?: continue
            checked++
            try {
                keys.decrypt(file.path, bytes)
                return true
            } catch (e: SyncException) {
                if (e.error !is SyncError.Corrupt) throw e
            }
        }
        return checked == 0
    }

    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(text: String) = Base64.getDecoder().decode(text)

    private companion object {
        /** One damaged file must not condemn a good key, so a few are tried. */
        const val KEY_CHECK_FILES = 3
    }
}
