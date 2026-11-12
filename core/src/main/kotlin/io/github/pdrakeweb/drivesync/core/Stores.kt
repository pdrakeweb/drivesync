package io.github.pdrakeweb.drivesync.core

/** One listed object. */
public data class BlobInfo(val path: String, val size: Long)

public data class Page<T>(val items: List<T>, val nextPageToken: String?)

public enum class PutResult { Created, AlreadyExists }

/**
 * Storage for encrypted objects, addressed by a relative path such as `batches/<replica>/<name>`.
 * Implementations only ever see ciphertext and report failures by throwing [SyncException].
 *
 * Listings may lag behind writes; callers never treat an unlisted object as permanently missing.
 */
public interface BlobStore {
    public suspend fun list(prefix: String, pageToken: String? = null): Page<BlobInfo>

    /** Null when the object does not exist (or is not visible yet). */
    public suspend fun get(path: String): ByteArray?

    /** Creates the object unless one already exists at [path]. Never overwrites. */
    public suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult

    /** Creates or replaces. Only for files this replica alone writes. */
    public suspend fun putOwned(path: String, bytes: ByteArray)

    /** Only for files this replica alone writes. Deleting a missing object is not an error. */
    public suspend fun delete(path: String)
}

/**
 * The app's database as the sync engine sees it: an outbox of local changes and a place to merge
 * remote ones. Implementations capture each local change into the outbox in the same transaction
 * as the app's write, and stamp it with a timestamp from their own [HlcClock].
 */
public interface LocalStore {
    /**
     * The oldest unacknowledged local changes, in timestamp order, at most [max] (more only to
     * avoid splitting changes that share a timestamp).
     *
     * Stable until acknowledged: once a call has returned a set, every later call returns exactly
     * that set until [ackOutbox] covers it, even across process death and even if more changes
     * have been captured since. A retried upload therefore produces the identical batch.
     */
    public suspend fun drainOutbox(max: Int): List<Change>

    /** Removes outbox changes whose [Hlc.time] is at most [upTo]. */
    public suspend fun ackOutbox(upTo: Long)

    /** Local changes not yet acknowledged (invariant C-3: pending changes are counted and visible). */
    public suspend fun pendingCount(): Int

    /** Merges remote changes in one transaction, using [MergeEngine]. An empty list is allowed. */
    public suspend fun apply(changes: List<Change>, registry: MergeRegistry): ApplyReport

    /** The full merged state as changes. */
    public suspend fun snapshot(): Sequence<Change>

    /** Highest batch sequence applied per replica; this replica's own entry is its last pushed batch. */
    public suspend fun cursors(): Map<ReplicaId, Long>

    /** Never moves a cursor backwards: saving a lower value than the stored one is a no-op. */
    public suspend fun saveCursor(replica: ReplicaId, seq: Long)

    /** The highest schema version passed to [noteSchemaSeen], 0 if none. Persisted. */
    public suspend fun highestSchemaSeen(): Int

    /** Records that data written with [version] exists; lower values than the stored one are ignored. */
    public suspend fun noteSchemaSeen(version: Int)
}

public enum class KeyState { Ready, NeedsPassphrase, NeedsJoin }

/** The data key wrapped by a passphrase-derived key, as stored in `meta.json`. */
public class WrappedKey(
    public val kdfAlgorithm: String,
    public val kdfParams: Map<String, String>,
    public val salt: ByteArray,
    public val wrappedKey: ByteArray,
)

public interface KeyManager {
    public suspend fun state(): KeyState
    public suspend fun create(passphrase: CharArray): WrappedKey
    /**
     * Throws [SyncException] with [SyncError.KeyMissing] when the passphrase does not open [wrapped];
     * the manager then holds no key at all.
     */
    public suspend fun unlock(passphrase: CharArray, wrapped: WrappedKey)

    /**
     * Wraps the key this device already holds under a new passphrase, for a person who has
     * forgotten the old one or wants to change it. The data key itself does not change, so every
     * stored object and every other device is unaffected.
     * Throws [SyncException] with [SyncError.KeyMissing] when this device holds no key.
     */
    public suspend fun rewrap(passphrase: CharArray): WrappedKey

    /** Authenticated encryption bound to [path], so an object moved to another path is rejected. */
    public suspend fun encrypt(path: String, plain: ByteArray): ByteArray

    /**
     * A keyed fingerprint of [plain] as 16 lower-case hex digits, used in file names. The same input
     * always gives the same result on every device holding the key; without the key it reveals
     * nothing, so a file name cannot be used to confirm a guess at its content (invariant C-2).
     */
    public suspend fun fingerprint(plain: ByteArray): String

    /** Throws [SyncException] with [SyncError.Corrupt] when the object is tampered or misplaced. */
    public suspend fun decrypt(path: String, cipher: ByteArray): ByteArray
}
