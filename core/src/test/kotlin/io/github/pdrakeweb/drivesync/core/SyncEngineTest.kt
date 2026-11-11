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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {
    private val v1 = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(
        name: String,
        var registry: MergeRegistry = v1,
        val batchSize: Int = 100,
        storeRegistry: MergeRegistry = registry,
    ) {
        val id = ReplicaId(name)
        val store = InMemoryLocalStore(id, storeRegistry) { now }
        val blobs = FaultyBlobStore(cloud)
        val keys = InsecureTestKeyManager()
        var local: LocalStore = store
        var engine = newEngine()

        fun newEngine() = SyncEngine(id, local, blobs, keys, registry, SyncConfig("device $id", "test", batchSize)) { now }
        fun restart() { engine = newEngine() }
        fun write(row: String, vararg columns: Pair<String, String>) =
            store.write("notes", row, columns.associate { it.first to Value.Text(it.second) })
        fun title(row: String) = (store.rows("notes")[row]?.get("title") as Value.Text?)?.value
        suspend fun sync(): SyncState { now += 1_000; return engine.sync() }
    }

    private fun batchPaths(device: Device) = cloud.paths().filter { it.startsWith("batches/${device.id}/") }

    private fun failing(error: SyncError, only: BlobOp? = null) = object : FaultPlan {
        override fun before(op: BlobOp, path: String) {
            if (only == null || only == op) throw SyncException(error)
        }
    }

    @Test
    fun `two devices exchange edits and report each other`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "title" to "from a")
        b.write("n2", "title" to "from b")
        a.engine.refreshPending()
        assertEquals(1, a.engine.pending.value)

        assertTrue(a.sync() is SyncState.Idle)
        assertTrue(b.sync() is SyncState.Idle)
        val final = a.sync()

        assertEquals(SyncState.Idle(now), final)
        assertEquals(final, a.engine.state.value)
        assertEquals(0, a.engine.pending.value)
        assertEquals(a.store.mergedState(), b.store.mergedState())
        assertEquals("from b", a.title("n2"))

        b.sync()
        val seen = a.also { it.sync() }.engine.devices().single()
        assertEquals(ReplicaId("b"), seen.replicaId)
        assertEquals("device b", seen.label)
        assertEquals(0, seen.batchesBehind)
        assertFalse(seen.retired)
        a.write("n3", "title" to "more"); a.sync()
        assertEquals(1, a.engine.devices().single().batchesBehind)
    }

    @Test
    fun `a push is split into size-capped batches applied in sequence`() = runBlocking {
        val a = Device("a", batchSize = 2); val b = Device("b")
        repeat(5) { a.write("n$it", "title" to "t$it") }
        a.sync(); b.sync()
        assertEquals(listOf(1L, 2L, 3L), batchPaths(a).map { Paths.parseBatchSeq(a.id, it) })
        assertEquals(5, b.store.rows("notes").size)
        assertEquals(3L, b.store.cursors()[a.id])
    }

    @Test
    fun `a crash after the upload is retried into the same object`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "title" to "t")
        a.blobs.plan = object : FaultPlan {
            override fun after(op: BlobOp, path: String) {
                if (op == BlobOp.PutIfAbsent) throw SimulatedCrash()
            }
        }
        assertThrows(SimulatedCrash::class.java) { runBlocking { a.sync() } }
        assertEquals(1, batchPaths(a).size)
        assertEquals(1, a.store.pendingCount(), "not acknowledged, so still pending")

        a.blobs.plan = FaultPlan.None
        a.restart()
        a.write("n2", "title" to "written after the crash")
        assertTrue(a.sync() is SyncState.Idle)
        assertEquals(listOf(1L, 2L), batchPaths(a).map { Paths.parseBatchSeq(a.id, it) }, "the retry reused batch 1")
        assertEquals(0, a.store.pendingCount())
        b.sync()
        assertEquals(a.store.mergedState(), b.store.mergedState())
    }

    @Test
    fun `a crash between saving the cursor and acknowledging loses nothing`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "title" to "t")
        var crash = true
        a.local = object : LocalStore by a.store {
            override suspend fun ackOutbox(upTo: Long) {
                if (crash) throw SimulatedCrash()
                a.store.ackOutbox(upTo)
            }
        }
        a.restart()
        assertThrows(SimulatedCrash::class.java) { runBlocking { a.sync() } }
        crash = false
        a.restart()
        assertTrue(a.sync() is SyncState.Idle)
        assertEquals(0, a.store.pendingCount())
        b.sync()
        assertEquals("t", b.title("n1"))
        assertEquals(a.store.mergedState(), b.store.mergedState())
    }

    @Test
    fun `a batch missing from a lagging listing is waited for, never skipped`() = runBlocking {
        val a = Device("a", batchSize = 1); val b = Device("b")
        a.write("n1", "title" to "first"); a.write("n1", "title" to "second")
        a.sync()
        val first = batchPaths(a).first()
        b.blobs.plan = object : FaultPlan {
            override fun hiddenFromListing(path: String) = path == first
        }
        assertTrue(b.sync() is SyncState.Idle)
        assertEquals(null, b.store.cursors()[a.id], "batch 2 is visible but must not be applied before batch 1")
        assertEquals(emptyMap<String, Any>(), b.store.rows("notes"))

        b.blobs.plan = FaultPlan.None
        b.sync()
        assertEquals(2L, b.store.cursors()[a.id])
        assertEquals("second", b.title("n1"))
    }

    @Test
    fun `storage failures become typed states with a fix, and pending stays visible`() = runBlocking {
        val a = Device("a")
        a.write("n1", "title" to "t")
        val cases = listOf(
            SyncError.StorageUnavailable("503") to SyncState.Error(SyncError.StorageUnavailable("503"), Fix.RetryLater),
            SyncError.AuthExpired to SyncState.Error(SyncError.AuthExpired, Fix.SignIn),
            SyncError.QuotaExceeded to SyncState.Error(SyncError.QuotaExceeded, Fix.FreeUpSpace),
            SyncError.NetworkUnavailable to SyncState.Offline,
        )
        for ((error, expected) in cases) {
            a.blobs.plan = failing(error)
            assertEquals(expected, a.sync())
            assertEquals(expected, a.engine.state.value)
            assertEquals(1, a.engine.pending.value)
        }
        a.blobs.plan = failing(SyncError.QuotaExceeded, BlobOp.PutIfAbsent)
        assertEquals(SyncState.Error(SyncError.QuotaExceeded, Fix.FreeUpSpace), a.sync())

        a.blobs.plan = FaultPlan.None
        assertTrue(a.sync() is SyncState.Idle)
        assertEquals(0, a.engine.pending.value)
    }

    @Test
    fun `an undeclared backend exception still surfaces as a typed error`() = runBlocking {
        val a = Device("a")
        a.blobs.plan = object : FaultPlan {
            override fun before(op: BlobOp, path: String): Unit = throw IllegalStateException("boom")
        }
        val state = a.sync() as SyncState.Error
        assertTrue(state.kind is SyncError.StorageUnavailable)
        assertEquals(Fix.RetryLater, state.fix)
    }

    @Test
    fun `without a key nothing is read or written and the state says so`() = runBlocking {
        val a = Device("a")
        a.write("n1", "title" to "t")
        a.keys.keyState = KeyState.NeedsPassphrase
        assertEquals(SyncState.NeedsKey, a.sync())
        assertEquals(emptyList<String>(), cloud.paths())
        assertEquals(1, a.engine.pending.value)
    }

    @Test
    fun `a tampered or misplaced batch is reported as corrupt and never applied`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "title" to "t"); a.sync()
        val path = batchPaths(a).single()
        val good = cloud.raw(path)!!

        cloud.tamper(path, good.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() })
        assertEquals(SyncState.Error(SyncError.Corrupt(path), null), b.sync())
        assertEquals(null, b.store.cursors()[a.id])

        // The same valid object served under another replica's name.
        cloud.tamper(path, good)
        val moved = path.replace("batches/a/", "batches/c/")
        cloud.putIfAbsent(moved, good)
        cloud.putOwned(Paths.replicaFile(ReplicaId("c")), b.keys.encrypt(Paths.replicaFile(ReplicaId("c")),
            WireCodec.encodeReplicaFile(ReplicaFile(replicaId = ReplicaId("c"), label = "c", platform = "x", lastSeen = 0,
                lastSeq = 1, cursors = emptyMap(), schemaVersion = 1))))
        assertEquals(SyncState.Error(SyncError.Corrupt(moved), null), b.sync())
    }

    @Test
    fun `an object from a newer library version asks for an app update`() = runBlocking {
        val a = Device("a")
        val path = Paths.replicaFile(ReplicaId("z"))
        cloud.putOwned(path, a.keys.encrypt(path, """{"format":2}""".encodeToByteArray()))
        assertEquals(SyncState.Error(SyncError.UnsupportedFormat(path, 2), Fix.UpdateApp), a.sync())
    }

    @Test
    fun `changes for an unknown table are quarantined, reported, and released by an upgrade`() = runBlocking {
        val v2 = MergeRegistry(2, listOf(TableSpec("notes", MergeClass.LWW_FIELDS), TableSpec("tags", MergeClass.LWW_FIELDS)))
        val newer = Device("a", registry = v2)
        // The older device's database survives its upgrade, so its store already knows both tables.
        val older = Device("b", registry = v1, storeRegistry = v2)
        newer.store.write("tags", "t1", mapOf("name" to Value.Text("work")))
        newer.write("n1", "title" to "known table")
        newer.sync()

        assertEquals(SyncState.Error(SyncError.SchemaTooOld(1, 2), Fix.UpdateApp), older.sync())
        assertEquals("known table", older.title("n1"), "the rest still syncs")
        assertEquals(1, older.store.quarantined().size)
        assertEquals(emptyMap<String, Any>(), older.store.rows("tags"))

        older.registry = v2
        older.restart()
        assertTrue(older.sync() is SyncState.Idle)
        assertEquals(0, older.store.quarantined().size)
        assertEquals(newer.store.mergedState(), older.store.mergedState())
    }

    @Test
    fun `a device on an older schema keeps pulling but does not push until updated`() = runBlocking {
        val v2 = MergeRegistry(2, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
        val newer = Device("a", registry = v2); val older = Device("b")
        newer.write("n1", "title" to "from v2"); newer.sync()
        older.write("n2", "title" to "from v1")

        assertEquals(SyncState.Error(SyncError.SchemaTooOld(1, 2), Fix.UpdateApp), older.sync())
        assertEquals("from v2", older.title("n1"))
        assertEquals(emptyList<String>(), batchPaths(older))
        assertEquals(1, older.engine.pending.value)

        older.registry = v2
        older.restart()
        assertTrue(older.sync() is SyncState.Idle)
        assertEquals(1, batchPaths(older).size)
        assertEquals(0, older.engine.pending.value)
    }

    @Test
    fun `a validator rejection is quarantined and reported, not dropped`() = runBlocking {
        val strict = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS))) { change ->
            if ((change.value as? Value.Text)?.value == "bad") "title may not be bad" else null
        }
        val a = Device("a"); val b = Device("b", registry = strict)
        a.write("n1", "title" to "bad"); a.write("n2", "title" to "fine"); a.sync()

        assertEquals(SyncState.Error(SyncError.Quarantined("notes", 1), Fix.UpdateApp), b.sync())
        assertEquals(setOf("n2"), b.store.rows("notes").keys)
        val held = b.store.quarantined().single()
        assertEquals(QuarantineReason.Rejected("title may not be bad"), held.reason)
        assertEquals(SyncState.Error(SyncError.Quarantined("notes", 1), Fix.UpdateApp), b.sync(), "still reported next cycle")
    }
}
