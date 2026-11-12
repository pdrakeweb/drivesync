package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.KeyManager
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.ParentRef
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.RowStatus
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.SyncState
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

public class SimulationOptions(
    /** Each replica's wall clock is offset by a fixed amount in [-max, +max]. */
    public val maxSkewMillis: Long = 3 * DAY,
    /** Inject storage failures, crashes mid-cycle, lagging listings and partitions. */
    public val faults: Boolean = true,
    public val minSteps: Int = 30,
    public val maxSteps: Int = 90,
    /** Small batches force multi-batch pushes. */
    public val maxBatchChanges: Int = 4,
    /** Passed to the engines: compact after this many own batches. Zero leaves compaction off. */
    public val compactAfterBatches: Int = 0,
    /** When set, replica 0 is away for this many days in the middle third of the run, then returns. */
    public val absenceDays: Int = 0,
    /** The key manager for replica number `index`; all must hold the same key. Default: the test scramble. */
    public val keys: (index: Int) -> KeyManager = { InsecureTestKeyManager() },
) {
    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

public class SimulationResult(
    public val seed: Long,
    /** Empty when every invariant held. Each entry names the property and what was observed. */
    public val violations: List<String>,
    public val steps: Int,
    public val objectsStored: Int,
    public val crashes: Int,
    public val snapshotsApplied: Int = 0,
    public val batchesDeleted: Int = 0,
    public val failedCycles: Int,
)

/** Thrown by the simulator to model the process dying. Not an [Exception], so nothing handles it. */
public class SimulatedCrash : Error("simulated process death")

/**
 * Deterministic whole-system simulation: N replicas, each a real [SyncEngine] over an
 * [InMemoryLocalStore], sharing one [InMemoryBlobStore]. A seed fully determines the run.
 *
 * Random local edits are interleaved with sync cycles under injected faults. Then faults stop,
 * everyone syncs until quiet, and the invariants are checked against an oracle that recorded
 * every local write.
 */
public object Simulator {
    public const val MARKER: String = "PLAINTEXT-MARKER"

    public val registry: MergeRegistry = MergeRegistry(
        schemaVersion = 1,
        tables = listOf(
            TableSpec("notes", MergeClass.LWW_FIELDS),
            TableSpec("items", MergeClass.LWW_FIELDS, listOf(ParentRef("note_id", "notes"))),
            TableSpec("settings", MergeClass.LWW_ROW),
            TableSpec("log", MergeClass.APPEND_ONLY),
        ),
    )

    private class Plan(private val rnd: Random) : FaultPlan {
        var offline = false
        var failIn = -1
        var crashIn = -1
        var crashAfter = false
        var lag = 0.0

        fun clear() {
            offline = false; failIn = -1; crashIn = -1; lag = 0.0
        }

        override fun before(op: BlobOp, path: String) {
            if (offline) throw SyncException(SyncError.NetworkUnavailable)
            if (failIn >= 0 && failIn-- == 0) throw SyncException(SyncError.StorageUnavailable("injected"))
            if (!crashAfter && crashIn >= 0 && crashIn-- == 0) throw SimulatedCrash()
        }

        override fun after(op: BlobOp, path: String) {
            if (crashAfter && crashIn >= 0 && crashIn-- == 0) throw SimulatedCrash()
        }

        override fun hiddenFromListing(path: String): Boolean = lag > 0 && rnd.nextDouble() < lag
    }

    private class Node(index: Int, val skew: Long, shared: InMemoryBlobStore, rnd: Random, now: () -> Long, val batch: Int, val keys: KeyManager, val compact: Int) {
        val id = ReplicaId("r$index")
        val clock: () -> Long = { now() + skew }
        val store = InMemoryLocalStore(id, registry, clock)
        val plan = Plan(rnd)
        val blobs = FaultyBlobStore(shared, plan)
        var engine = newEngine()

        var away = false

        fun newEngine() = SyncEngine(id, store, blobs, keys, registry, SyncConfig("device $id", "sim", batch, compact), clock)

        fun restart() {
            engine = newEngine()
        }
    }

    public fun run(seed: Long, options: SimulationOptions = SimulationOptions()): SimulationResult = runBlocking {
        val rnd = Random(seed)
        var now = 1_800_000_000_000L
        val shared = InMemoryBlobStore(pageSize = 7)
        val nodes = List(rnd.nextInt(2, 5)) { i ->
            val skew = if (options.maxSkewMillis == 0L) 0L else rnd.nextLong(-options.maxSkewMillis, options.maxSkewMillis + 1)
            Node(i, skew, shared, rnd, { now }, options.maxBatchChanges, options.keys(i), options.compactAfterBatches)
        }
        val violations = ArrayList<String>()
        var crashes = 0
        var failed = 0
        var snapshotCount = 0
        var deletedBatches = 0

        // The oracle: every local write, as stamped by the store that made it.
        val fieldWrites = HashMap<Pair<RowRef, String>, MutableList<Pair<Hlc, Value>>>()
        val rowWrites = HashMap<RowRef, MutableList<Pair<Hlc, Map<String, Value>>>>()
        val appended = HashMap<RowRef, Map<String, Value>>()
        val itemParent = HashMap<RowRef, RowRef>()
        val deleted = HashSet<RowRef>()
        var counter = 0

        fun text(step: Int) = Value.Text("$MARKER-$step")

        fun writeFields(node: Node, row: RowRef, columns: Map<String, Value>) {
            if (node.store.isDeleted(row)) return
            val before = node.store.cells(row)
            node.store.write(row.table, row.rowId, columns)
            val after = node.store.cells(row)
            for ((column, value) in columns) {
                val cell = after.getValue(column)
                val previous = before[column]
                if (previous != null && cell.hlc <= previous.hlc) {
                    violations += "CLOCK_NOT_MONOTONIC $row.$column ${cell.hlc} after ${previous.hlc}"
                }
                fieldWrites.getOrPut(row to column) { ArrayList() } += cell.hlc to value
            }
        }

        suspend fun cycle(node: Node): SyncState? = try {
            node.engine.sync().also {
                snapshotCount += node.engine.lastCycle.snapshotsApplied
                deletedBatches += node.engine.lastCycle.batchesDeleted
            }
        } catch (e: SimulatedCrash) {
            crashes++
            node.restart()
            null
        }

        val steps = rnd.nextInt(options.minSteps, options.maxSteps + 1)
        repeat(steps) { step ->
            now += rnd.nextLong(0, 5_000)
            // A long absence: replica 0 stops syncing for the middle third while time moves on.
            if (options.absenceDays > 0) {
                val from = steps / 3
                val until = 2 * steps / 3
                nodes[0].away = step in from until until
                if (step in from until until) now += options.absenceDays * 86_400_000L / (until - from) + 1
            }
            val node = nodes[rnd.nextInt(nodes.size)]
            when (rnd.nextInt(100)) {
                in 0 until 26 -> {
                    val visible = node.store.rows("notes").keys.sorted()
                    val id = if (visible.isEmpty() || rnd.nextBoolean()) "n${rnd.nextInt(4)}" else visible[rnd.nextInt(visible.size)]
                    val columns = listOf("title", "body", "pinned").shuffled(rnd).take(rnd.nextInt(1, 3))
                    writeFields(node, RowRef("notes", id), columns.associateWith { text(step) })
                }
                in 26 until 38 -> {
                    val notes = node.store.rows("notes").keys.sorted()
                    val items = node.store.rows("items").keys.sorted()
                    if (items.isNotEmpty() && rnd.nextInt(3) == 0) {
                        writeFields(node, RowRef("items", items[rnd.nextInt(items.size)]), mapOf("text" to text(step)))
                    } else if (notes.isNotEmpty()) {
                        val note = notes[rnd.nextInt(notes.size)]
                        val item = RowRef("items", "${node.id}-i${counter++}")
                        itemParent[item] = RowRef("notes", note)
                        writeFields(node, item, mapOf("note_id" to Value.Text(note), "text" to text(step)))
                    }
                }
                in 38 until 46 -> {
                    val row = RowRef("settings", "s${rnd.nextInt(2)}")
                    if (!node.store.isDeleted(row)) {
                        val columns = listOf("theme", "size").shuffled(rnd).take(rnd.nextInt(1, 3)).associateWith { text(step) }
                        val hlc = node.store.write(row.table, row.rowId, columns)
                        rowWrites.getOrPut(row) { ArrayList() } += hlc to columns
                    }
                }
                in 46 until 56 -> {
                    val row = RowRef("log", "${node.id}-l${counter++}")
                    val columns = mapOf("event" to text(step), "n" to Value.Integer(step.toLong()))
                    node.store.write(row.table, row.rowId, columns)
                    appended[row] = columns
                }
                in 56 until 64 -> {
                    val candidates = listOf("notes", "items", "settings", "log")
                        .flatMap { table -> node.store.rows(table).keys.sorted().map { RowRef(table, it) } }
                    if (candidates.isNotEmpty()) {
                        val row = candidates[rnd.nextInt(candidates.size)]
                        node.store.delete(row.table, row.rowId)
                        deleted += row
                    }
                }
                in 64 until 90 -> if (!node.away) {
                    if (options.faults) {
                        when (rnd.nextInt(10)) {
                            0, 1 -> node.plan.failIn = rnd.nextInt(0, 10)
                            2, 3 -> {
                                node.plan.crashIn = rnd.nextInt(0, 10)
                                node.plan.crashAfter = rnd.nextBoolean()
                            }
                        }
                        node.plan.lag = if (rnd.nextInt(3) == 0) 0.4 else 0.0
                    }
                    val result = cycle(node)
                    if (result != null && result !is SyncState.Idle) failed++
                    when {
                        result is SyncState.Syncing -> violations += "STUCK cycle returned $result"
                        node.plan.offline && result != null && result != SyncState.Offline ->
                            violations += "OFFLINE_NOT_REPORTED got $result"
                    }
                    node.plan.failIn = -1
                    node.plan.crashIn = -1
                }
                in 90 until 96 -> if (options.faults && !node.away) node.plan.offline = !node.plan.offline
                else -> now += rnd.nextLong(0, 3_600_000)
            }
        }

        // Heal: no more faults, everyone online, sync until two full rounds change nothing.
        nodes.forEach { it.plan.clear() }
        var quiet = 0
        var rounds = 0
        var lastStates: List<SyncState?> = emptyList()
        while (quiet < 2 && rounds < 40) {
            val before = nodes.map { it.store.mergedState() } to shared.paths().size
            lastStates = nodes.map { cycle(it) }
            val after = nodes.map { it.store.mergedState() } to shared.paths().size
            quiet = if (before == after) quiet + 1 else 0
            rounds++
        }
        if (quiet < 2) violations += "NOT_QUIESCENT after $rounds rounds"

        // Invariant C-3: nothing stuck, nothing pending.
        nodes.forEachIndexed { i, node ->
            if (lastStates[i] !is SyncState.Idle) violations += "STUCK ${node.id} ended in ${lastStates[i]}"
            val pending = node.store.pendingCount()
            if (pending != 0 || node.engine.pending.value != 0) violations += "PENDING ${node.id} has $pending"
        }

        // Invariant C-1: convergence.
        val states = nodes.map { it.store.mergedState() }
        if (states.distinct().size > 1) violations += "CONVERGENCE replicas hold different merged state"
        val visible = nodes.map { node -> registry.tables.associate { it.name to node.store.rows(it.name) } }
        if (visible.distinct().size > 1) violations += "CONVERGENCE replicas show different rows"

        for (node in nodes) {
            // Invariant C-1: a deleted row never comes back.
            for (row in deleted) {
                if (!node.store.isDeleted(row) || row.rowId in node.store.rows(row.table)) {
                    violations += "RESURRECTED $row on ${node.id}"
                }
            }
            // Invariant C-1: an append-only row is never lost or altered.
            for ((row, columns) in appended) {
                if (row !in deleted && node.store.cells(row).mapValues { it.value.value } != columns) {
                    violations += "APPEND_ONLY_LOST $row on ${node.id}"
                }
            }
            // Field merge: the write with the highest timestamp is the one that remains.
            for ((key, writes) in fieldWrites) {
                val (row, column) = key
                if (row in deleted) continue
                val winner = writes.maxBy { it.first }
                val cell = node.store.cells(row)[column]
                if (cell?.hlc != winner.first || cell.value != winner.second) {
                    violations += "LWW_FIELD $row.$column on ${node.id}: ${cell?.hlc} expected ${winner.first}"
                }
            }
            for ((row, writes) in rowWrites) {
                if (row in deleted) continue
                val winner = writes.maxBy { it.first }
                if (node.store.cells(row).mapValues { it.value.value } != winner.second) {
                    violations += "LWW_ROW $row on ${node.id}"
                }
            }
            // Missing parents: a child of a deleted parent is reported, never silently shown or dropped.
            for ((item, parent) in itemParent) {
                if (item in deleted) continue
                val expected = if (parent in deleted) RowStatus.ORPHANED else RowStatus.ACTIVE
                val actual = node.store.status(item)
                if (actual != expected) violations += "PARENT_STATUS $item on ${node.id}: $actual expected $expected"
            }
        }

        // Invariant C-2, as far as it can be checked without real crypto: nothing bypassed the key
        // manager, and names carry no content.
        val marker = MARKER.encodeToByteArray()
        for (path in shared.paths()) {
            if (!NAME.matches(path)) violations += "FILE_NAME unexpected path $path"
            if (contains(shared.raw(path)!!, marker)) violations += "PLAINTEXT_AT_REST $path"
        }

        SimulationResult(seed, violations, steps, shared.paths().size, crashes, snapshotCount, deletedBatches, failed)
    }

    private val NAME = Regex("replicas/r\\d\\.json|batches/r\\d/\\d{12}-[0-9a-f]{16}\\.bin|snapshots/r\\d-\\d{20}\\.bin")

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
