package io.github.pdrakeweb.drivesync.core

import io.github.pdrakeweb.drivesync.testing.BlobOp
import io.github.pdrakeweb.drivesync.testing.FaultPlan
import io.github.pdrakeweb.drivesync.testing.FaultyBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import io.github.pdrakeweb.drivesync.testing.SimulatedCrash
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression tests for the findings of the S1 independent review. */
class SyncEngineHardeningTest {
    private val notes = TableSpec("notes", MergeClass.LWW_FIELDS)
    private val items = TableSpec("items", MergeClass.LWW_FIELDS, listOf(ParentRef("note_id", "notes")))
    private val v1 = MergeRegistry(1, listOf(notes, items))
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(name: String, var registry: MergeRegistry = v1) {
        val id = ReplicaId(name)
        val store = InMemoryLocalStore(id, registry) { now }
        val blobs = FaultyBlobStore(cloud)
        val keys = InsecureTestKeyManager()
        var local: LocalStore = store
        var engine = newEngine()

        fun newEngine() = SyncEngine(id, local, blobs, keys, registry, SyncConfig("device $id", "test")) { now }
        fun restart() { engine = newEngine() }
        fun write(row: String, title: String) = store.write("notes", row, mapOf("title" to Value.Text(title)))
        fun title(row: String) = (store.rows("notes")[row]?.get("title") as Value.Text?)?.value
        suspend fun sync(): SyncState { now += 1_000; return engine.sync() }
    }

    private fun batchPaths(id: String) = cloud.paths().filter { it.startsWith("batches/$id/") }

    @Test
    fun `a store restored from an older state is detected and nothing is pushed`() = runBlocking {
        val original = Device("a"); val b = Device("b")
        original.write("n1", "one"); original.sync()
        original.write("n2", "two"); original.sync()
        b.sync()
        val before = cloud.paths().associateWith { cloud.raw(it)!!.toList() }

        // The same identity with an empty database: a restore from backup, or a cloned phone.
        val restored = Device("a")
        restored.write("n9", "written after the restore")
        val expected = SyncState.Error(SyncError.ReplicaStateBehind(localSeq = 0, storedSeq = 2), Fix.ResetThisDevice)
        assertEquals(expected, restored.sync())
        assertEquals(before, cloud.paths().associateWith { cloud.raw(it)!!.toList() }, "storage untouched")
        assertEquals(1, restored.engine.pending.value)
    }

    @Test
    fun `a truncated object left by an interrupted upload is repaired, not acknowledged`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "t")
        a.blobs.plan = object : FaultPlan {
            override fun after(op: BlobOp, path: String) {
                if (op == BlobOp.PutIfAbsent) throw SimulatedCrash()
            }
        }
        assertThrows(SimulatedCrash::class.java) { runBlocking { a.sync() } }
        val path = batchPaths("a").single()
        cloud.tamper(path, cloud.raw(path)!!.copyOf(5)) // power cut: the name exists, the content does not

        a.blobs.plan = FaultPlan.None
        a.restart()
        assertTrue(a.sync() is SyncState.Idle)
        assertTrue(b.sync() is SyncState.Idle)
        assertEquals("t", b.title("n1"))
        assertEquals(1, batchPaths("a").size)
    }

    @Test
    fun `a corrupt object from one device does not stop sync with the others`() = runBlocking {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        a.write("n1", "from a"); a.sync()
        b.write("n2", "from b"); b.sync()
        val bad = batchPaths("a").single()
        cloud.tamper(bad, byteArrayOf(1, 2, 3))
        c.write("n3", "from c")

        assertEquals(SyncState.Error(SyncError.Corrupt(bad), null), c.sync())
        assertEquals("from b", c.title("n2"), "b sorts after a and is still pulled")
        assertEquals(1, batchPaths("c").size, "c still pushed")
        assertEquals(0, c.engine.pending.value)
        assertTrue(Paths.replicaFile(c.id) in cloud.paths(), "c still published its manifest")
        assertEquals(1L, c.engine.devices().single { it.replicaId == a.id }.batchesToFetch)

        // A corrupt manifest is contained the same way.
        cloud.tamper(Paths.replicaFile(a.id), byteArrayOf(9))
        b.write("n4", "more from b"); b.sync()
        assertEquals(SyncState.Error(SyncError.Corrupt(Paths.replicaFile(a.id)), null), c.sync())
        assertEquals("more from b", c.title("n4"))
    }

    @Test
    fun `a rejected change leaves quarantine once the validator accepts it`() = runBlocking {
        var strict = true
        val picky = MergeRegistry(1, listOf(notes, items)) { change ->
            if (strict && (change.value as? Value.Text)?.value == "bad") "reason ${System.nanoTime()}" else null
        }
        val a = Device("a"); val b = Device("b", registry = picky)
        a.write("n1", "bad"); a.sync()
        repeat(3) { assertEquals(SyncState.Error(SyncError.Quarantined("notes", 1), Fix.UpdateApp), b.sync()) }
        assertEquals(1, b.store.quarantined().size, "one entry per change, even when the reason text varies")

        strict = false
        assertTrue(b.sync() is SyncState.Idle)
        assertEquals("bad", b.title("n1"))
        assertEquals(a.store.mergedState(), b.store.mergedState())
    }

    @Test
    fun `held and orphaned rows are published to the app`() = runBlocking {
        val a = Device("a"); val b = Device("b"); val c = Device("c")
        a.write("n1", "parent"); a.sync(); b.sync()
        a.store.delete("notes", "n1")
        b.store.write("items", "i1", mapOf("note_id" to Value.Text("n1"), "text" to Value.Text("child")))
        b.store.write("items", "i2", mapOf("note_id" to Value.Text("never-created"), "text" to Value.Text("x")))
        a.sync(); b.sync(); a.sync(); c.sync()

        for (d in listOf(a, b, c)) {
            assertEquals(listOf(RowRef("items", "i1")), d.engine.orphaned.value, d.id.value)
            assertEquals(listOf(RowRef("items", "i2")), d.engine.held.value, d.id.value)
            assertEquals(emptyMap<String, Any>(), d.store.rows("items"))
        }
    }

    @Test
    fun `once newer-schema data was applied the device never pushes, even if the manifest disappears`() = runBlocking {
        val v2 = MergeRegistry(2, listOf(notes, items))
        val newer = Device("a", registry = v2); val older = Device("b")
        newer.write("n1", "from v2"); newer.sync()
        assertEquals(SyncState.Error(SyncError.SchemaTooOld(1, 2), Fix.UpdateApp), older.sync())

        older.blobs.plan = object : FaultPlan {
            override fun hiddenFromListing(path: String) = path == Paths.replicaFile(newer.id)
        }
        older.write("n2", "from v1")
        older.restart() // nothing in memory: the floor must come from the store
        assertEquals(SyncState.Error(SyncError.SchemaTooOld(1, 2), Fix.UpdateApp), older.sync())
        assertEquals(emptyList<String>(), batchPaths("b"))
        assertEquals(2, older.store.highestSchemaSeen())
    }

    @Test
    fun `the schema floor is taken from applied batches, not only from manifests`() = runBlocking {
        val v2 = MergeRegistry(2, listOf(notes, items))
        val newer = Device("a"); val older = Device("b")
        newer.write("n1", "v1 data"); newer.sync(); older.sync()
        // a upgrades and pushes a v2 batch, but b only ever sees a's old v1 manifest.
        val oldManifest = cloud.raw(Paths.replicaFile(newer.id))!!
        newer.registry = v2; newer.restart()
        newer.write("n2", "v2 data"); newer.sync()
        cloud.tamper(Paths.replicaFile(newer.id), oldManifest)

        assertEquals(SyncState.Error(SyncError.SchemaTooOld(1, 2), Fix.UpdateApp), older.sync())
        assertEquals("v2 data", older.title("n2"))
        older.write("n3", "written on v1 after applying v2 data")
        older.sync()
        assertEquals(0, batchPaths("b").size, "nothing is pushed after the v2 batch was applied")
        assertEquals(1, older.engine.pending.value)
    }

    @Test
    fun `a failing local database ends in a typed state, not in Syncing`() = runBlocking {
        val a = Device("a")
        a.local = object : LocalStore by a.store {
            override suspend fun pendingCount(): Int = throw IllegalStateException("database is closed")
        }
        a.restart()
        val state = a.sync()
        assertTrue(state is SyncState.Error && state.kind is SyncError.Unexpected && state.fix == null, state.toString())
        assertEquals(state, a.engine.state.value)
    }

    @Test
    fun `a second engine on the same store cannot reuse a sequence number`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "one"); a.sync()
        a.write("n2", "two"); a.sync()
        b.sync()
        // What a stale engine would do after resuming: try to move the cursor back.
        a.store.saveCursor(a.id, 1)
        assertEquals(2L, a.store.cursors()[a.id])
        a.write("n3", "three"); a.sync(); b.sync()
        assertEquals(listOf(1L, 2L, 3L), batchPaths("a").map { Paths.parseBatchSeq(a.id, it) })
        assertEquals("three", b.title("n3"))
    }

    @Test
    fun `out-of-range timestamps are rejected at the wire and the clock cannot wrap`() {
        assertThrows(IllegalArgumentException::class.java) { Hlc(-1, ReplicaId("a")) }
        val clock = HlcClock(ReplicaId("a"), { 1_000 })
        clock.observe(Hlc(Long.MAX_VALUE, ReplicaId("b")))
        assertThrows(IllegalStateException::class.java) { clock.tick() }
    }
}
