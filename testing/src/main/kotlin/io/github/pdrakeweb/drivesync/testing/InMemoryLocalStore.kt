package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.ApplyReport
import io.github.pdrakeweb.drivesync.core.Cell
import io.github.pdrakeweb.drivesync.core.Change
import io.github.pdrakeweb.drivesync.core.Conflict
import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.HlcClock
import io.github.pdrakeweb.drivesync.core.LocalStore
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeEngine
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.MergeState
import io.github.pdrakeweb.drivesync.core.Quarantined
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.RowStatus
import io.github.pdrakeweb.drivesync.core.RowStatusResolver
import io.github.pdrakeweb.drivesync.core.Value

/** The complete merge metadata of a store. Two replicas have converged when these are equal. */
public data class MergedState(
    val cells: Map<RowRef, Map<String, Cell>>,
    val tombstones: Map<RowRef, Hlc>,
)

/**
 * A [LocalStore] held in memory: the reference implementation of the store contract, and the
 * stand-in for an app database in tests and in the simulator. The instance plays the role of the
 * database file, so it survives a simulated process death while the sync engine is recreated.
 */
public class InMemoryLocalStore(
    replicaId: ReplicaId,
    private val registry: MergeRegistry,
    private val wallClock: () -> Long,
) : LocalStore {
    /** This store's identity. Changes only through [resetIdentity]. */
    public var replicaId: ReplicaId = replicaId
        private set
    private var clock = HlcClock(replicaId, wallClock)
    private val cells = HashMap<RowRef, MutableMap<String, Cell>>()
    private val tombstones = HashMap<RowRef, Hlc>()
    private val outbox = ArrayList<Change>()
    private var pinned = 0
    private val cursors = HashMap<ReplicaId, Long>()
    private val quarantine = LinkedHashMap<Change, Quarantined>()
    private var schemaSeen = 0
    private val conflictLog = LinkedHashMap<Triple<RowRef, String, Hlc>, Conflict>()

    private fun <T> locked(block: () -> T): T = synchronized(this, block)

    // Local writes are the app's own; they are not subject to the incoming-change validator.
    private val localRegistry = MergeRegistry(registry.schemaVersion, registry.tables.toList())

    private val state = object : MergeState {
        override fun observe(hlc: Hlc) = clock.observe(hlc)
        override fun tombstone(row: RowRef): Hlc? = tombstones[row]
        override fun putTombstone(row: RowRef, hlc: Hlc) {
            tombstones[row] = hlc
            cells.remove(row)
        }
        override fun cells(row: RowRef): Map<String, Cell> = cells[row] ?: emptyMap()
        override fun putCell(row: RowRef, column: String, cell: Cell) {
            cells.getOrPut(row) { HashMap() }[column] = cell
        }
        override fun clearCells(row: RowRef) {
            cells.remove(row)
        }
        override fun quarantined(): Collection<Quarantined> = quarantine.values.toList()
        override fun quarantine(item: Quarantined) {
            quarantine[item.change] = item
        }
        override fun recordConflict(conflict: Conflict): Boolean =
            conflictLog.putIfAbsent(Triple(conflict.row, conflict.column, conflict.lost.hlc), conflict) == null
        override fun release(item: Quarantined) {
            quarantine.remove(item.change)
        }
    }

    /**
     * A local write of some columns of a row, captured into the outbox. For an [MergeClass.LWW_ROW]
     * table the columns given become the whole row. Returns the timestamp of the last change made.
     */
    @Synchronized
    public fun write(table: String, rowId: String, columns: Map<String, Value>): Hlc {
        require(columns.isNotEmpty())
        val spec = requireNotNull(localRegistry.table(table)) { "unknown table $table" }
        val row = RowRef(table, rowId)
        check(row !in tombstones) { "$row was deleted; ids are never reused" }
        if (spec.mergeClass == MergeClass.APPEND_ONLY) check(row !in cells) { "$row is append-only" }
        // Whole-row classes stamp every column of one write with the same timestamp.
        val shared = if (spec.mergeClass == MergeClass.LWW_FIELDS) null else clock.tick()
        val changes = columns.entries.sortedBy { it.key }.map { (column, value) ->
            // A field write records the timestamp of the value it replaces (see Change.prev).
            val prev = if (shared == null) cells[row]?.get(column)?.hlc else null
            Change(table, rowId, column, value, shared ?: clock.tick(), prev)
        }
        MergeEngine.apply(state, changes, localRegistry)
        outbox += changes
        return changes.last().hlc
    }

    @Synchronized
    public fun delete(table: String, rowId: String): Hlc {
        requireNotNull(localRegistry.table(table)) { "unknown table $table" }
        val change = Change.tombstone(table, rowId, clock.tick())
        MergeEngine.apply(state, listOf(change), localRegistry)
        outbox += change
        return change.hlc
    }

    /**
     * Gives this store a new identity, for a database that was restored from a backup or copied
     * to another device ([io.github.pdrakeweb.drivesync.core.Fix.ResetThisDevice]).
     *
     * Changes not yet uploaded become the new identity's, at their original times; everything
     * already uploaded keeps its author. Cursors are kept: the old identity's entry now means
     * "applied this far from that replica", which is true of this store's own past. The caller
     * must then build a new sync engine with the new id.
     */
    @Synchronized
    public fun resetIdentity(newId: ReplicaId) {
        require(newId != replicaId && newId !in cursors) { "the new identity must never have been used" }
        val pending = outbox.mapTo(HashSet()) { it.hlc }
        fun moved(hlc: Hlc) = if (hlc in pending) Hlc(hlc.time, newId) else hlc
        outbox.replaceAll { it.copy(hlc = moved(it.hlc), prev = it.prev?.let(::moved)) }
        for (columns in cells.values) columns.replaceAll { _, cell -> cell.copy(hlc = moved(cell.hlc), prev = cell.prev?.let(::moved)) }
        tombstones.replaceAll { _, hlc -> moved(hlc) }
        pinned = 0
        clock = HlcClock(newId, wallClock, clock.last)
        replicaId = newId
    }

    /** An independent copy of this store, identity included: what a backup or a cloned phone holds. */
    @Synchronized
    public fun copy(): InMemoryLocalStore {
        val other = InMemoryLocalStore(replicaId, registry, wallClock)
        other.clock = HlcClock(replicaId, wallClock, clock.last)
        for ((row, columns) in cells) other.cells[row] = HashMap(columns)
        other.tombstones.putAll(tombstones)
        other.outbox.addAll(outbox)
        other.pinned = pinned
        other.cursors.putAll(cursors)
        other.quarantine.putAll(quarantine)
        other.schemaSeen = schemaSeen
        other.conflictLog.putAll(conflictLog)
        return other
    }

    /** Rows the app would see: not deleted, and not held back or orphaned. */
    @Synchronized
    public fun rows(table: String): Map<String, Map<String, Value>> {
        val resolver = RowStatusResolver(registry, state)
        return cells.filterKeys { it.table == table && resolver.status(it) == RowStatus.ACTIVE }
            .entries.associate { (row, columns) -> row.rowId to columns.mapValues { it.value.value } }
    }

    @Synchronized
    public fun status(row: RowRef): RowStatus = RowStatusResolver(registry, state).status(row)

    @Synchronized
    public fun isDeleted(row: RowRef): Boolean = row in tombstones

    @Synchronized
    public fun cells(row: RowRef): Map<String, Cell> = cells[row]?.toMap() ?: emptyMap()

    @Synchronized
    public fun mergedState(): MergedState =
        MergedState(cells.mapValues { it.value.toMap() }, tombstones.toMap())

    @Synchronized
    public fun quarantined(): List<Quarantined> = quarantine.values.toList()

    /** The conflict log, oldest first. */
    @Synchronized
    public fun conflicts(): List<Conflict> = conflictLog.values.toList()

    override suspend fun drainOutbox(max: Int): List<Change> = locked {
        if (pinned == 0 && outbox.isNotEmpty()) {
            var n = minOf(max, outbox.size)
            while (n < outbox.size && outbox[n].hlc == outbox[n - 1].hlc) n++
            pinned = n
        }
        outbox.subList(0, pinned).toList()
    }

    override suspend fun ackOutbox(upTo: Long): Unit = locked {
        val acked = outbox.takeWhile { it.hlc.time <= upTo }.size
        repeat(acked) { outbox.removeAt(0) }
        pinned = (pinned - acked).coerceAtLeast(0)
    }

    override suspend fun pendingCount(): Int = locked { outbox.size }

    override suspend fun apply(changes: List<Change>, registry: MergeRegistry): ApplyReport = locked {
        val report = MergeEngine.apply(state, changes, registry)
        val resolver = RowStatusResolver(registry, state)
        val withParents = registry.tables.filter { it.parents.isNotEmpty() }.map { it.name }.toSet()
        val statuses = cells.keys.filter { it.table in withParents }.groupBy { resolver.status(it) }
        report.copy(
            held = statuses[RowStatus.HELD].orEmpty().sortedBy { it.toString() },
            orphaned = statuses[RowStatus.ORPHANED].orEmpty().sortedBy { it.toString() },
        )
    }

    override suspend fun snapshot(): Sequence<Change> = locked {
        val all = ArrayList<Change>()
        for ((row, columns) in cells) for ((column, cell) in columns) {
            all += Change(row.table, row.rowId, column, cell.value, cell.hlc, cell.prev)
        }
        for ((row, hlc) in tombstones) all += Change.tombstone(row.table, row.rowId, hlc)
        all.asSequence()
    }

    override suspend fun cursors(): Map<ReplicaId, Long> = locked { cursors.toMap() }

    override suspend fun saveCursor(replica: ReplicaId, seq: Long): Unit = locked {
        cursors[replica] = maxOf(seq, cursors[replica] ?: 0)
    }

    override suspend fun highestSchemaSeen(): Int = locked { schemaSeen }

    override suspend fun noteSchemaSeen(version: Int): Unit = locked {
        schemaSeen = maxOf(schemaSeen, version)
    }
}
