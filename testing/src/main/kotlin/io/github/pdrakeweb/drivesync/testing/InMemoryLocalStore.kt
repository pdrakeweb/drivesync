package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.ApplyReport
import io.github.pdrakeweb.drivesync.core.Cell
import io.github.pdrakeweb.drivesync.core.Change
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
    public val replicaId: ReplicaId,
    private val registry: MergeRegistry,
    wallClock: () -> Long,
) : LocalStore {
    private val clock = HlcClock(replicaId, wallClock)
    private val cells = HashMap<RowRef, MutableMap<String, Cell>>()
    private val tombstones = HashMap<RowRef, Hlc>()
    private val outbox = ArrayList<Change>()
    private var pinned = 0
    private val cursors = HashMap<ReplicaId, Long>()
    private val quarantine = LinkedHashSet<Quarantined>()

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
        override fun quarantined(): Collection<Quarantined> = quarantine.toList()
        override fun quarantine(item: Quarantined) {
            quarantine += item
        }
        override fun release(item: Quarantined) {
            quarantine -= item
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
        val shared = if (spec.mergeClass == MergeClass.LWW_ROW) clock.tick() else null
        val changes = columns.entries.sortedBy { it.key }.map { (column, value) ->
            Change(table, rowId, column, value, shared ?: clock.tick())
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
    public fun quarantined(): List<Quarantined> = quarantine.toList()

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
            all += Change(row.table, row.rowId, column, cell.value, cell.hlc)
        }
        for ((row, hlc) in tombstones) all += Change.tombstone(row.table, row.rowId, hlc)
        all.asSequence()
    }

    override suspend fun cursors(): Map<ReplicaId, Long> = locked { cursors.toMap() }

    override suspend fun saveCursor(replica: ReplicaId, seq: Long): Unit = locked {
        cursors[replica] = seq
    }
}
