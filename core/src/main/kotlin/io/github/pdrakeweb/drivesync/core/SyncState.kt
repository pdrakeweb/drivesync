package io.github.pdrakeweb.drivesync.core

/** Every way a sync cycle can fail, typed so an app can show an action (invariant C-3). */
public sealed interface SyncError {
    /** The storage account refused the app's credentials. */
    public data object AuthExpired : SyncError

    /** The person's storage is full. */
    public data object QuotaExceeded : SyncError

    /** No network. Shown as [SyncState.Offline], not as an error. */
    public data object NetworkUnavailable : SyncError

    /** The storage provider failed or is rate limiting; retrying later may work. */
    public data class StorageUnavailable(val detail: String) : SyncError

    /** This device has no usable data key. */
    public data object KeyMissing : SyncError

    /** Incoming changes are set aside: an unknown table or a validator rejection. */
    public data class Quarantined(val table: String, val count: Int) : SyncError

    /** An object was written by a newer version of the library. */
    public data class UnsupportedFormat(val path: String, val format: Int) : SyncError

    /** An object failed authentication, is at the wrong path, or does not parse. */
    public data class Corrupt(val path: String) : SyncError

    /**
     * This device's sync state is older than what it has already uploaded: the database was
     * restored from a backup or copied from another device. Pushing would reuse sequence numbers
     * for different content, so nothing is pushed until the store takes a new identity.
     */
    public data class ReplicaStateBehind(val localSeq: Long, val storedSeq: Long) : SyncError

    /** A failure the library did not anticipate (a bug, or a local database error). */
    public data class Unexpected(val detail: String) : SyncError

    /** Another device already writes a newer schema; this app version must not push. */
    public data class SchemaTooOld(val local: Int, val newest: Int) : SyncError
}

/** What the person can do about a [SyncError]. */
public enum class Fix { SignIn, FreeUpSpace, RetryLater, EnterPassphrase, UpdateApp, ResetThisDevice }

public class SyncException(public val error: SyncError, cause: Throwable? = null) :
    Exception(error.toString(), cause)

public enum class SyncPhase { Preparing, Pushing, Pulling, Finishing }

public data class SyncProgress(val phase: SyncPhase, val batchesDone: Int = 0)

public sealed interface SyncState {
    /** [lastSync] is the wall-clock time of the last complete cycle, null if there has been none. */
    public data class Idle(val lastSync: Long?) : SyncState

    public data class Syncing(val progress: SyncProgress) : SyncState

    public data class Error(val kind: SyncError, val fix: Fix?) : SyncState

    public data object NeedsKey : SyncState

    public data object Offline : SyncState
}

/** Another device, as shown in a device list. */
public data class ReplicaInfo(
    val replicaId: ReplicaId,
    val label: String,
    val platform: String,
    /** Wall-clock time that device last completed a cycle, by its own clock. */
    val lastSeen: Long,
    /** How many of this device's batches that device has not applied yet. */
    val batchesBehind: Long,
    /** How many of that device's batches this device has not applied yet (waiting on a lagging listing, or missing). */
    val batchesToFetch: Long,
    val schemaVersion: Int,
    val retired: Boolean,
)
