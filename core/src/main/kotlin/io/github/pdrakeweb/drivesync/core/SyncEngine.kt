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
    /**
     * Once this many of this replica's batches are in storage it writes a snapshot and deletes the
     * batches every active device has already applied. Zero turns compaction off.
     */
    public val compactAfterBatches: Int = 200,
    /** A device not seen for this long stops holding back compaction; it rebuilds from a snapshot if it returns. */
    public val retireAfterMillis: Long = 180L * 24 * 60 * 60 * 1000,
) {
    init {
        require(maxBatchChanges > 0 && compactAfterBatches >= 0 && retireAfterMillis > 0)
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

    // Compaction state. Rebuilt from this replica's own manifest at the start of each cycle.
    private var compactedThrough = 0L
    private var ownSnapshot: String? = null
    private var snapshotAtSeq = 0L

    private class MutableStats(var pushed: Int = 0, var applied: Int = 0, var snapshotsApplied: Int = 0, var deleted: Int = 0)

    /** What the last completed cycle did, for diagnostics and tests. */
    @Volatile
    public var lastCycle: CycleStats = CycleStats(0, 0, 0, 0)
        private set

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
                retired = isRetired(it, wallClock()),
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
        lastCycle = CycleStats(0, 0, 0, 0)
        if (keys.state() != KeyState.Ready) return SyncState.NeedsKey
        progress(SyncPhase.Preparing)
        // An empty apply re-tries quarantined changes (an app upgrade may now accept them) and
        // reports what is currently set aside.
        var report = local.apply(emptyList(), registry)

        // A bad object from one replica is remembered and reported, but does not stop this
        // device pushing, pulling from the others, or publishing its manifest.
        val problems = ArrayList<SyncError>()
        val stats = MutableStats()
        val listed = readReplicaFiles(problems)
        // This replica's own manifest is read by name as well: a lagging listing must not make it
        // forget what it has already uploaded or compacted.
        val own = listed[replicaId] ?: readReplicaFile(Paths.replicaFile(replicaId), replicaId, problems)
        val replicas = if (own == null) listed else listed + (replicaId to own)
        knownReplicas = replicas

        val ownSeq = local.cursors()[replicaId] ?: 0
        val storedSeq = own?.lastSeq ?: 0
        if (storedSeq > ownSeq) return stateFor(SyncError.ReplicaStateBehind(ownSeq, storedSeq))
        if (own != null && own.compactedThrough >= compactedThrough) {
            compactedThrough = own.compactedThrough
            ownSnapshot = own.snapshot ?: ownSnapshot
        }

        replicas.values.forEach { local.noteSchemaSeen(it.schemaVersion) }
        var batches = 0
        val mayPush = local.highestSchemaSeen() <= registry.schemaVersion
        if (mayPush) {
            // Announce a schema upgrade before the first batch that uses it.
            if (own?.schemaVersion != registry.schemaVersion) writeOwnReplicaFile()
            batches = push()
            stats.pushed = batches
        }
        for ((id, file) in replicas) {
            if (id == replicaId) continue
            try {
                pull(id, file, batches, stats) {
                    batches++
                    report = it
                }
            } catch (e: SyncException) {
                if (e.error is SyncError.Corrupt || e.error is SyncError.UnsupportedFormat) problems += e.error else throw e
            }
        }
        progress(SyncPhase.Finishing, batches)
        // A snapshot must contain everything the batches it replaces contained. Changes set aside
        // in quarantine are not in merged state, so nothing is compacted while any exist.
        if (mayPush && report.quarantined.isEmpty() && local.highestSchemaSeen() <= registry.schemaVersion) {
            compact(replicas, stats)
        }
        writeOwnReplicaFile()
        lastCycle = CycleStats(stats.pushed, stats.applied, stats.snapshotsApplied, stats.deleted)
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
            putVerified(path, plain)
            // Cursor before acknowledgement: a crash between the two re-sends these changes under
            // the next sequence number (harmless, merging is idempotent) instead of reusing this
            // number for different content.
            local.saveCursor(replicaId, seq)
            local.ackOutbox(changes.maxOf { it.hlc.time })
            pushed++
        }
    }

    /** Stores one of this replica's own immutable objects and makes sure it is really there. */
    private suspend fun putVerified(path: String, plain: ByteArray) {
        val cipher = keys.encrypt(path, plain)
        if (blobs.putIfAbsent(path, cipher) == PutResult.AlreadyExists && !holds(path, plain)) {
            // An earlier attempt left a truncated or damaged object under this name. The file is
            // this replica's own, so it may be replaced; treating it as stored would lose its
            // content for every other device.
            blobs.putOwned(path, cipher)
        }
    }

    /**
     * DESIGN.md section 8, steps 4 and 5. Writes a snapshot of the merged state, announces it, and
     * only then deletes this replica's batches that every active replica has applied. A replica
     * not seen for [SyncConfig.retireAfterMillis] no longer holds deletion back.
     *
     * Deleting is always safe for correctness, because the announced snapshot contains everything
     * the deleted batches did; waiting for active replicas only spares them a rebuild.
     */
    private suspend fun compact(replicas: Map<ReplicaId, ReplicaFile>, stats: MutableStats) {
        if (config.compactAfterBatches == 0) return
        // Cursors first, then the snapshot: the snapshot then holds at least what the cursors claim.
        val cursors = local.cursors()
        val ownSeq = cursors[replicaId] ?: 0
        if (ownSeq - maxOf(compactedThrough, snapshotAtSeq) < config.compactAfterBatches) return

        val changes = local.snapshot().toList()
        val header = SnapshotHeader(
            replicaId = replicaId,
            schemaVersion = registry.schemaVersion,
            covers = cursors.mapKeys { it.key.value },
            count = changes.size,
        )
        val path = Paths.snapshot(replicaId, changes.maxOfOrNull { it.hlc.time } ?: 0)
        putVerified(path, WireCodec.encodeSnapshot(Snapshot(header, changes)))
        snapshotAtSeq = ownSeq

        val now = wallClock()
        val active = replicas.values.filter { it.replicaId != replicaId && !isRetired(it, now) }
        val applied = active.minOfOrNull { it.cursors[replicaId.value] ?: 0 } ?: ownSeq
        compactedThrough = maxOf(compactedThrough, minOf(ownSeq, applied))
        ownSnapshot = path
        // Announce before deleting: a reader that finds a batch gone must already be able to learn why.
        writeOwnReplicaFile()

        for (info in listAll(Paths.batchPrefix(replicaId))) {
            val seq = Paths.parseBatchSeq(replicaId, info.path) ?: continue
            if (seq <= compactedThrough) {
                blobs.delete(info.path)
                stats.deleted++
            }
        }
        for (info in listAll(Paths.snapshotPrefix(replicaId))) {
            if (info.path != path && Paths.isSnapshotOf(replicaId, info.path)) blobs.delete(info.path)
        }
    }

    private fun isRetired(file: ReplicaFile, now: Long): Boolean =
        file.retired || now - file.lastSeen > config.retireAfterMillis

    /**
     * Brings this replica up to [file]'s snapshot when the batches it would need next have been
     * compacted away: a new device, or one returning after a long absence.
     */
    private suspend fun bootstrap(from: ReplicaId, file: ReplicaFile, stats: MutableStats, onApplied: (ApplyReport) -> Unit): Boolean {
        val manifest = Paths.replicaFile(from)
        val path = file.snapshot ?: throw SyncException(SyncError.Corrupt(manifest))
        if (!Paths.isSnapshotOf(from, path)) throw SyncException(SyncError.Corrupt(manifest))
        // Not there (yet, or any more): the manifest read this cycle may be stale. Retry next cycle.
        val cipher = blobs.get(path) ?: return false
        val snapshot = WireCodec.decodeSnapshot(path, keys.decrypt(path, cipher))
        val covers = try {
            snapshot.header.covers.mapKeys { ReplicaId(it.key) }
        } catch (e: IllegalArgumentException) {
            throw SyncException(SyncError.Corrupt(path), e)
        }
        if (snapshot.header.replicaId != from || (covers[from] ?: 0) < file.compactedThrough) {
            throw SyncException(SyncError.Corrupt(path))
        }
        local.noteSchemaSeen(snapshot.header.schemaVersion)
        onApplied(local.apply(snapshot.changes, registry))
        // The snapshot contains every change of the batches it covers, whoever wrote them, so each
        // of those cursors may move up. Never this replica's own: that counts its own uploads.
        for ((id, seq) in covers) if (id != replicaId) local.saveCursor(id, seq)
        stats.snapshotsApplied++
        return true
    }

    private suspend fun holds(path: String, plain: ByteArray): Boolean {
        val stored = blobs.get(path) ?: return false
        return try {
            keys.decrypt(path, stored).contentEquals(plain)
        } catch (e: SyncException) {
            if (e.error is SyncError.Corrupt) false else throw e
        }
    }

    private suspend fun pull(from: ReplicaId, file: ReplicaFile, done: Int, stats: MutableStats, onApplied: (ApplyReport) -> Unit) {
        if ((local.cursors()[from] ?: 0) < file.compactedThrough && !bootstrap(from, file, stats, onApplied)) return
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
            stats.applied++
            count++
            next++
        }
    }

    private suspend fun readReplicaFiles(problems: MutableList<SyncError>): Map<ReplicaId, ReplicaFile> {
        val result = LinkedHashMap<ReplicaId, ReplicaFile>()
        for (info in listAll(Paths.REPLICAS).sortedBy { it.path }) {
            val id = Paths.parseReplicaFile(info.path) ?: continue
            readReplicaFile(info.path, id, problems)?.let { result[id] = it }
        }
        return result
    }

    private suspend fun readReplicaFile(path: String, id: ReplicaId, problems: MutableList<SyncError>): ReplicaFile? {
        val cipher = blobs.get(path) ?: return null
        return try {
            val file = WireCodec.decodeReplicaFile(path, keys.decrypt(path, cipher))
            if (file.replicaId != id) throw SyncException(SyncError.Corrupt(path))
            file
        } catch (e: SyncException) {
            if (e.error is SyncError.Corrupt || e.error is SyncError.UnsupportedFormat) {
                problems += e.error
                null
            } else {
                throw e
            }
        }
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
            compactedThrough = compactedThrough,
            snapshot = ownSnapshot,
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

public data class CycleStats(
    val batchesPushed: Int,
    val batchesApplied: Int,
    val snapshotsApplied: Int,
    val batchesDeleted: Int,
)
