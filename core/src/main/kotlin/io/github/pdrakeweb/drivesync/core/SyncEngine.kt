package io.github.pdrakeweb.drivesync.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class SyncConfig(
    /** Name shown for this device in other devices' lists. Travels encrypted. */
    public val label: String,
    public val platform: String = "android",
    /** Upper bound on changes per batch. */
    public val maxBatchChanges: Int = 2_000,
) {
    init {
        require(maxBatchChanges > 0)
    }
}

/**
 * Runs sync cycles for one replica (DESIGN.md section 8): push the outbox as immutable batches,
 * pull and merge other replicas' batches, publish this replica's manifest.
 *
 * Scheduling is the caller's job. [sync] never throws for an expected failure; it returns and
 * publishes a typed [SyncState] instead.
 *
 * Use one engine per local store. A second engine on the same store is safe (cursors never move
 * backwards, pushes are idempotent) but wasteful.
 */
public class SyncEngine(
    private val replicaId: ReplicaId,
    private val local: LocalStore,
    private val blobs: BlobStore,
    private val keys: KeyManager,
    private val registry: MergeRegistry,
    private val config: SyncConfig,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle(null))
    private val _pending = MutableStateFlow(0)
    private val _held = MutableStateFlow<List<RowRef>>(emptyList())
    private val _orphaned = MutableStateFlow<List<RowRef>>(emptyList())

    @Volatile
    private var knownReplicas: Map<ReplicaId, ReplicaFile> = emptyMap()

    public val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Local changes not yet pushed. Refreshed by [sync] and [refreshPending]. */
    public val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Rows kept but not shown because a parent has not arrived yet. Refreshed by [sync]. */
    public val held: StateFlow<List<RowRef>> = _held.asStateFlow()

    /** Rows kept but not shown because a parent was deleted; the app decides what to do with them. */
    public val orphaned: StateFlow<List<RowRef>> = _orphaned.asStateFlow()

    /** Call after local writes so [pending] reflects them before the next cycle. */
    public suspend fun refreshPending() {
        _pending.value = local.pendingCount()
    }

    /** Other devices as of the last cycle that reached storage. */
    public suspend fun devices(): List<ReplicaInfo> {
        val cursors = local.cursors()
        val ownLastSeq = cursors[replicaId] ?: 0
        return knownReplicas.values.filter { it.replicaId != replicaId }.map {
            ReplicaInfo(
                replicaId = it.replicaId,
                label = it.label,
                platform = it.platform,
                lastSeen = it.lastSeen,
                batchesBehind = (ownLastSeq - (it.cursors[replicaId.value] ?: 0)).coerceAtLeast(0),
                batchesToFetch = (it.lastSeq - (cursors[it.replicaId] ?: 0)).coerceAtLeast(0),
                schemaVersion = it.schemaVersion,
                retired = it.retired,
            )
        }
    }

    /** One full cycle. Concurrent calls run one after another. */
    public suspend fun sync(): SyncState = mutex.withLock {
        val before = _state.value
        var result = try {
            cycle()
        } catch (e: SyncException) {
            stateFor(e.error)
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            // A bug or a local database failure must still surface as a typed state.
            stateFor(SyncError.Unexpected(e.toString()))
        }
        try {
            _pending.value = local.pendingCount()
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            result = stateFor(SyncError.Unexpected(e.toString()))
        }
        _state.value = result
        result
    }

    private suspend fun cycle(): SyncState {
        if (keys.state() != KeyState.Ready) return SyncState.NeedsKey
        progress(SyncPhase.Preparing)
        // An empty apply re-tries quarantined changes (an app upgrade may now accept them) and
        // reports what is currently set aside.
        var report = local.apply(emptyList(), registry)

        // A bad object from one replica is remembered and reported, but does not stop this
        // device pushing, pulling from the others, or publishing its manifest.
        val problems = ArrayList<SyncError>()
        val replicas = readReplicaFiles(problems)
        knownReplicas = replicas

        val ownSeq = local.cursors()[replicaId] ?: 0
        val storedSeq = replicas[replicaId]?.lastSeq ?: 0
        if (storedSeq > ownSeq) return stateFor(SyncError.ReplicaStateBehind(ownSeq, storedSeq))

        replicas.values.forEach { local.noteSchemaSeen(it.schemaVersion) }
        var batches = 0
        if (local.highestSchemaSeen() <= registry.schemaVersion) {
            // Announce a schema upgrade before the first batch that uses it.
            if (replicas[replicaId]?.schemaVersion != registry.schemaVersion) writeOwnReplicaFile()
            batches = push()
        }
        for (id in replicas.keys) {
            if (id == replicaId) continue
            try {
                pull(id, batches) {
                    batches++
                    report = it
                }
            } catch (e: SyncException) {
                if (e.error is SyncError.Corrupt || e.error is SyncError.UnsupportedFormat) problems += e.error else throw e
            }
        }
        progress(SyncPhase.Finishing, batches)
        writeOwnReplicaFile()
        _held.value = report.held
        _orphaned.value = report.orphaned

        // Decided after the pull: a batch applied in this very cycle may carry a newer schema.
        val newestSchema = local.highestSchemaSeen()
        if (newestSchema > registry.schemaVersion) {
            return stateFor(SyncError.SchemaTooOld(registry.schemaVersion, newestSchema))
        }
        problems.firstOrNull()?.let { return stateFor(it) }
        report.quarantined.entries.minByOrNull { it.key }?.let {
            return stateFor(SyncError.Quarantined(it.key, it.value))
        }
        return SyncState.Idle(wallClock())
    }

    private suspend fun push(): Int {
        var pushed = 0
        while (true) {
            // drainOutbox is stable until acknowledged, so a retry after a crash rebuilds the
            // identical batch, gets the identical name, and putIfAbsent finds it already there.
            val changes = local.drainOutbox(config.maxBatchChanges)
            if (changes.isEmpty()) return pushed
            progress(SyncPhase.Pushing, pushed)
            val seq = (local.cursors()[replicaId] ?: 0) + 1
            val plain = WireCodec.encodeBatch(Batch.of(replicaId, seq, registry.schemaVersion, changes))
            val path = Paths.batch(replicaId, seq, keys.fingerprint(plain))
            val cipher = keys.encrypt(path, plain)
            if (blobs.putIfAbsent(path, cipher) == PutResult.AlreadyExists && !holds(path, plain)) {
                // An earlier attempt left a truncated or damaged object under this name. The file is
                // this replica's own, so it may be replaced; acknowledging without this would lose
                // the changes for every other device.
                blobs.putOwned(path, cipher)
            }
            // Cursor before acknowledgement: a crash between the two re-sends these changes under
            // the next sequence number (harmless, merging is idempotent) instead of reusing this
            // number for different content.
            local.saveCursor(replicaId, seq)
            local.ackOutbox(changes.maxOf { it.hlc.time })
            pushed++
        }
    }

    private suspend fun holds(path: String, plain: ByteArray): Boolean {
        val stored = blobs.get(path) ?: return false
        return try {
            keys.decrypt(path, stored).contentEquals(plain)
        } catch (e: SyncException) {
            if (e.error is SyncError.Corrupt) false else throw e
        }
    }

    private suspend fun pull(from: ReplicaId, done: Int, onApplied: (ApplyReport) -> Unit) {
        var next = (local.cursors()[from] ?: 0) + 1
        val bySeq = listAll(Paths.batchPrefix(from))
            .mapNotNull { info -> Paths.parseBatchSeq(from, info.path)?.let { it to info.path } }
            .filter { it.first >= next }
            .groupBy({ it.first }, { it.second })
        var count = done
        while (true) {
            // Strictly in sequence. A gap means the listing is lagging: stop and retry next cycle.
            val paths = bySeq[next]?.sorted() ?: return
            progress(SyncPhase.Pulling, count)
            // Fetch every object of this sequence number before applying any, so the cursor only
            // moves when all of them were merged.
            val batches = paths.map { path ->
                val cipher = blobs.get(path) ?: return
                val plain = keys.decrypt(path, cipher)
                val batch = WireCodec.decodeBatch(path, plain)
                val header = batch.header
                if (header.replicaId != from || header.seq != next || Paths.tagOf(path) != keys.fingerprint(plain)) {
                    throw SyncException(SyncError.Corrupt(path))
                }
                batch
            }
            for (batch in batches) {
                // Before applying: once data of a newer schema is merged, this app version must
                // not push again, even if the process dies right after.
                local.noteSchemaSeen(batch.header.schemaVersion)
                onApplied(local.apply(batch.changes, registry))
            }
            local.saveCursor(from, next)
            count++
            next++
        }
    }

    private suspend fun readReplicaFiles(problems: MutableList<SyncError>): Map<ReplicaId, ReplicaFile> {
        val result = LinkedHashMap<ReplicaId, ReplicaFile>()
        for (info in listAll(Paths.REPLICAS).sortedBy { it.path }) {
            val id = Paths.parseReplicaFile(info.path) ?: continue
            val cipher = blobs.get(info.path) ?: continue
            try {
                val file = WireCodec.decodeReplicaFile(info.path, keys.decrypt(info.path, cipher))
                if (file.replicaId != id) throw SyncException(SyncError.Corrupt(info.path))
                result[id] = file
            } catch (e: SyncException) {
                if (e.error is SyncError.Corrupt || e.error is SyncError.UnsupportedFormat) problems += e.error else throw e
            }
        }
        return result
    }

    private suspend fun writeOwnReplicaFile() {
        val cursors = local.cursors()
        val file = ReplicaFile(
            replicaId = replicaId,
            label = config.label,
            platform = config.platform,
            lastSeen = wallClock(),
            lastSeq = cursors[replicaId] ?: 0,
            cursors = cursors.filterKeys { it != replicaId }.mapKeys { it.key.value },
            schemaVersion = registry.schemaVersion,
        )
        val path = Paths.replicaFile(replicaId)
        blobs.putOwned(path, keys.encrypt(path, WireCodec.encodeReplicaFile(file)))
    }

    private suspend fun listAll(prefix: String): List<BlobInfo> {
        val all = ArrayList<BlobInfo>()
        var token: String? = null
        do {
            val page = blobs.list(prefix, token)
            all += page.items
            token = page.nextPageToken
        } while (token != null)
        return all
    }

    private fun progress(phase: SyncPhase, batches: Int = 0) {
        _state.value = SyncState.Syncing(SyncProgress(phase, batches))
    }

    private fun stateFor(error: SyncError): SyncState = when (error) {
        SyncError.NetworkUnavailable -> SyncState.Offline
        SyncError.KeyMissing -> SyncState.NeedsKey
        SyncError.AuthExpired -> SyncState.Error(error, Fix.SignIn)
        SyncError.QuotaExceeded -> SyncState.Error(error, Fix.FreeUpSpace)
        is SyncError.StorageUnavailable -> SyncState.Error(error, Fix.RetryLater)
        is SyncError.Quarantined -> SyncState.Error(error, Fix.UpdateApp)
        is SyncError.UnsupportedFormat -> SyncState.Error(error, Fix.UpdateApp)
        is SyncError.SchemaTooOld -> SyncState.Error(error, Fix.UpdateApp)
        is SyncError.ReplicaStateBehind -> SyncState.Error(error, Fix.ResetThisDevice)
        is SyncError.Corrupt -> SyncState.Error(error, null)
        is SyncError.Unexpected -> SyncState.Error(error, null)
    }
}
