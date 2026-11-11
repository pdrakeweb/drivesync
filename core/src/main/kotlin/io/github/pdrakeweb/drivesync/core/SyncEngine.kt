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

    @Volatile
    private var knownReplicas: Map<ReplicaId, ReplicaFile> = emptyMap()

    public val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Local changes not yet pushed. Refreshed by [sync] and [refreshPending]. */
    public val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Call after local writes so [pending] reflects them before the next cycle. */
    public suspend fun refreshPending() {
        _pending.value = local.pendingCount()
    }

    /** Other devices as of the last cycle that reached storage. */
    public suspend fun devices(): List<ReplicaInfo> {
        val ownLastSeq = local.cursors()[replicaId] ?: 0
        return knownReplicas.values.filter { it.replicaId != replicaId }.map {
            ReplicaInfo(
                replicaId = it.replicaId,
                label = it.label,
                platform = it.platform,
                lastSeen = it.lastSeen,
                batchesBehind = (ownLastSeq - (it.cursors[replicaId.value] ?: 0)).coerceAtLeast(0),
                schemaVersion = it.schemaVersion,
                retired = it.retired,
            )
        }
    }

    /** One full cycle. Concurrent calls run one after another. */
    public suspend fun sync(): SyncState = mutex.withLock {
        val before = _state.value
        val result = try {
            cycle()
        } catch (e: SyncException) {
            stateFor(e.error)
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            // A bug or an undeclared backend failure must still surface as a typed state.
            stateFor(SyncError.StorageUnavailable("unexpected: $e"))
        }
        _pending.value = local.pendingCount()
        _state.value = result
        result
    }

    private suspend fun cycle(): SyncState {
        if (keys.state() != KeyState.Ready) return SyncState.NeedsKey
        progress(SyncPhase.Preparing)
        // An empty apply re-tries changes quarantined before an app upgrade and reports totals.
        var quarantined = local.apply(emptyList(), registry).quarantined

        val replicas = readReplicaFiles()
        knownReplicas = replicas
        val newestSchema = replicas.values.maxOfOrNull { it.schemaVersion } ?: 0
        val schemaTooOld = newestSchema > registry.schemaVersion

        var batches = 0
        if (!schemaTooOld) {
            // Announce a schema upgrade before the first batch that uses it, so a device that
            // applies such a batch finds the new version in this manifest on its next cycle.
            if (replicas[replicaId]?.schemaVersion != registry.schemaVersion) writeOwnReplicaFile()
            batches = push()
        }
        for ((id, _) in replicas) {
            if (id == replicaId) continue
            pull(id, batches) { report ->
                batches++
                quarantined = report.quarantined
            }
        }
        progress(SyncPhase.Finishing, batches)
        writeOwnReplicaFile()

        if (schemaTooOld) return stateFor(SyncError.SchemaTooOld(registry.schemaVersion, newestSchema))
        val firstQuarantined = quarantined.entries.minByOrNull { it.key }
        if (firstQuarantined != null) {
            return stateFor(SyncError.Quarantined(firstQuarantined.key, firstQuarantined.value))
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
            val path = Paths.batch(replicaId, seq, plain)
            blobs.putIfAbsent(path, keys.encrypt(path, plain))
            // Cursor before acknowledgement: a crash between the two re-sends these changes under
            // the next sequence number (harmless, merging is idempotent) instead of reusing this
            // number for different content.
            local.saveCursor(replicaId, seq)
            local.ackOutbox(changes.maxOf { it.hlc.time })
            pushed++
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
                if (header.replicaId != from || header.seq != next || Paths.hashOf(path) != Paths.hash16(plain)) {
                    throw SyncException(SyncError.Corrupt(path))
                }
                batch
            }
            for (batch in batches) onApplied(local.apply(batch.changes, registry))
            local.saveCursor(from, next)
            count++
            next++
        }
    }

    private suspend fun readReplicaFiles(): Map<ReplicaId, ReplicaFile> {
        val result = LinkedHashMap<ReplicaId, ReplicaFile>()
        for (info in listAll(Paths.REPLICAS).sortedBy { it.path }) {
            val id = Paths.parseReplicaFile(info.path) ?: continue
            val cipher = blobs.get(info.path) ?: continue
            val file = WireCodec.decodeReplicaFile(info.path, keys.decrypt(info.path, cipher))
            if (file.replicaId != id) throw SyncException(SyncError.Corrupt(info.path))
            result[id] = file
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
        is SyncError.Corrupt -> SyncState.Error(error, null)
    }
}
