package io.github.pdrakeweb.drivesync.core

import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Milestone S4: the opt-in conflict log. */
class ConflictLogTest {
    private val registry = MergeRegistry(
        1,
        listOf(
            TableSpec("notes", MergeClass.LWW_FIELDS, recordConflicts = true),
            TableSpec("quiet", MergeClass.LWW_FIELDS),
        ),
    )
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(name: String) {
        val id = ReplicaId(name)
        val store = InMemoryLocalStore(id, registry) { now }
        val engine = SyncEngine(id, store, cloud, InsecureTestKeyManager(), registry,
            SyncConfig(name, "test", maxBatchChanges = 1, compactAfterBatches = 3)) { now }
        fun write(row: String, title: String, table: String = "notes") {
            now += 1_000
            store.write(table, row, mapOf("title" to Value.Text(title)))
        }
        suspend fun sync() { now += 1_000; assertTrue(engine.sync() is SyncState.Idle) }
        fun lost() = store.conflicts().map { "${it.row.rowId}: kept ${(it.kept.value as Value.Text).value}, lost ${(it.lost.value as Value.Text).value}" }
    }

    @Test
    fun `edits made without seeing each other are logged on every device, once`() = runBlocking {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        a.write("n1", "original"); a.sync(); b.sync()
        val emitted = ArrayList<Conflict>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { b.engine.conflicts().toList(emitted) }

        a.write("n1", "edited on a")      // neither has seen the other's edit
        b.write("n1", "edited on b")      // later, so it is the one kept
        a.sync(); b.sync(); a.sync(); c.sync()

        val expected = listOf("n1: kept edited on b, lost edited on a")
        for (d in listOf(a, b, c)) assertEquals(expected, d.lost(), d.id.value)
        assertEquals(a.store.mergedState(), b.store.mergedState())

        repeat(2) { a.sync(); b.sync(); c.sync() }
        assertEquals(expected, b.lost(), "syncing again adds nothing")
        yield() // let the collector run
        collector.cancel()
        assertEquals(1, emitted.size, "the engine reports it once")
        assertEquals("edited on a", (emitted.single().lost.value as Value.Text).value)
    }

    @Test
    fun `an edit made after seeing the other one is not a conflict, in any arrival order`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "first"); a.sync(); b.sync()
        b.write("n1", "second, written knowing the first"); b.sync(); a.sync()
        a.write("n1", "third, written knowing the second"); a.sync()

        // A device that receives the newest value before the older ones (b's batches after a's).
        val late = Device("zz-late")
        late.sync(); b.sync()
        for (d in listOf(a, b, late)) assertEquals(emptyList<String>(), d.lost(), d.id.value)
        assertEquals(a.store.mergedState(), late.store.mergedState())
    }

    @Test
    fun `replaying history from a snapshot reports nothing new`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "base"); a.sync(); b.sync()
        a.write("n1", "from a"); b.write("n1", "from b")
        a.sync(); b.sync(); a.sync()
        assertEquals(1, a.lost().size)
        repeat(6) { a.write("pad$it", "x") }      // enough batches for a to compact
        a.sync(); b.sync()

        val fresh = Device("fresh")
        fresh.sync()
        assertTrue(fresh.engine.lastCycle.snapshotsApplied > 0)
        assertEquals(a.store.mergedState(), fresh.store.mergedState())
        assertTrue(fresh.lost().size <= 1, "at most the one real conflict: ${fresh.lost()}")
        fresh.sync(); a.sync()
        assertEquals(1, a.lost().size)
    }

    @Test
    fun `tables that did not opt in, equal values and one device's own edits are never logged`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("q", "a", table = "quiet"); b.write("q", "b", table = "quiet")   // not opted in
        a.write("same", "identical"); b.write("same", "identical")              // nothing was lost
        a.write("own", "one"); a.write("own", "two"); a.write("own", "three")   // one author
        a.sync(); b.sync(); a.sync()
        assertEquals(emptyList<String>(), a.lost())
        assertEquals(emptyList<String>(), b.lost())
    }

    @Test
    fun `turn-taking edits never produce a conflict, whatever the sync pattern`() = runBlocking {
        // Whoever edits has synced first, so every edit knowingly replaces the previous one.
        val rnd = Random(7)
        val devices = listOf(Device("a"), Device("b"), Device("c"))
        repeat(120) { step ->
            val d = devices[rnd.nextInt(devices.size)]
            devices.shuffled(rnd).forEach { it.sync() }      // everything published reaches everyone
            d.sync()
            d.write("n${rnd.nextInt(3)}", "step $step by ${d.id}")
            d.sync()
        }
        devices.forEach { it.sync() }
        for (d in devices) assertEquals(emptyList<String>(), d.lost(), d.id.value)
        assertEquals(1, devices.map { it.store.mergedState() }.distinct().size)
    }

    @Test
    fun `a redelivered change never adds a second log entry`() = runBlocking {
        val store = InMemoryLocalStore(ReplicaId("observer"), registry) { now }
        val base = Change("notes", "n1", "title", Value.Text("base"), Hlc.of(100, 0, ReplicaId("a")))
        val fromA = Change("notes", "n1", "title", Value.Text("from a"), Hlc.of(200, 0, ReplicaId("a")), prev = base.hlc)
        val fromB = Change("notes", "n1", "title", Value.Text("from b"), Hlc.of(300, 0, ReplicaId("b")), prev = base.hlc)

        assertEquals(1, store.apply(listOf(base, fromA, fromB), registry).conflicts.size)
        for (again in listOf(listOf(fromA), listOf(fromB, fromA), listOf(base, fromA, fromB), listOf(fromA, fromA))) {
            assertEquals(emptyList<Conflict>(), store.apply(again, registry).conflicts, "redelivering $again")
        }
        assertEquals(1, store.conflicts().size)
        assertEquals(Value.Text("from a"), store.conflicts().single().lost.value)
    }
}
