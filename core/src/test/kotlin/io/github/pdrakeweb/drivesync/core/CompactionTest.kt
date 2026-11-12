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

/** Milestone S4: snapshots, safe batch deletion, stale-device retirement. */
class CompactionTest {
    private val day = 24L * 60 * 60 * 1000
    private val plain = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(name: String, val registry: MergeRegistry = plain, val compactAfter: Int = 3) {
        val id = ReplicaId(name)
        val store = InMemoryLocalStore(id, registry) { now }
        val blobs = FaultyBlobStore(cloud)
        var engine = newEngine()

        fun newEngine() = SyncEngine(id, store, blobs, InsecureTestKeyManager(), registry,
            SyncConfig("device $id", "test", maxBatchChanges = 1, compactAfterBatches = compactAfter)) { now }
        fun restart() { engine = newEngine() }
        fun write(row: String, title: String) = store.write("notes", row, mapOf("title" to Value.Text(title)))
        fun titles() = store.rows("notes").mapValues { (it.value["title"] as Value.Text).value }
        suspend fun sync(): SyncState { now += 1_000; return engine.sync() }
    }

    private fun batches(id: String) = cloud.paths().filter { it.startsWith("batches/$id/") }.map { Paths.parseBatchSeq(ReplicaId(id), it)!! }
    private fun snapshots(id: String) = cloud.paths().filter { it.startsWith("snapshots/$id-") }
    private suspend fun manifest(d: Device): ReplicaFile {
        val path = Paths.replicaFile(d.id)
        return WireCodec.decodeReplicaFile(path, InsecureTestKeyManager().decrypt(path, cloud.raw(path)!!))
    }

    @Test
    fun `batches are deleted only once every active device has applied them`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "one"); a.sync(); b.sync()               // b has applied a's batch 1
        repeat(4) { a.write("n${it + 2}", "more") }
        a.sync()

        assertEquals(1, snapshots("a").size, "a snapshot is written at the threshold")
        assertEquals(listOf(2L, 3L, 4L, 5L), batches("a"), "only the batch b had applied is gone")
        assertEquals(1L, manifest(a).compactedThrough)
        assertEquals(1, a.engine.lastCycle.batchesDeleted)

        b.sync()                                               // b catches up through batch 5
        repeat(3) { a.write("m$it", "later") }
        a.sync()
        assertEquals(listOf(6L, 7L, 8L), batches("a"))
        assertEquals(1, snapshots("a").size, "the older snapshot was replaced")
        b.sync()
        assertEquals(a.store.mergedState(), b.store.mergedState())
        assertEquals(0, b.engine.lastCycle.snapshotsApplied, "an up-to-date device never needs the snapshot")
    }

    @Test
    fun `a new device starts from the snapshot and ends with the same state, tombstones included`() = runBlocking {
        val a = Device("a")
        repeat(6) { a.write("n$it", "t$it") }
        a.store.delete("notes", "n0")
        a.sync()
        assertTrue(batches("a").size < 7, "some batches were compacted away: ${batches("a")}")

        val c = Device("c")
        assertTrue(c.sync() is SyncState.Idle)
        assertEquals(1, c.engine.lastCycle.snapshotsApplied)
        assertEquals(a.store.mergedState(), c.store.mergedState())
        assertTrue(c.store.isDeleted(RowRef("notes", "n0")), "a row deleted before the snapshot stays deleted")
        assertEquals(a.titles(), c.titles())

        a.write("after", "written after the snapshot"); a.sync(); c.sync()
        assertEquals("written after the snapshot", c.titles()["after"])
        assertEquals(0, c.engine.lastCycle.snapshotsApplied)
    }

    @Test
    fun `a device away for 200 days stops holding back deletion, then rebuilds and delivers its own edits`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "before the absence"); a.sync(); b.sync(); a.sync()
        b.write("offline-edit", "made on b just before going away")
        b.write("n1", "b's older edit of n1")

        repeat(20) { i ->
            now += 10 * day
            a.write("n1", "a's edit $i"); a.write("d$i", "day ${i * 10}")
            a.sync()
        }
        assertTrue(a.engine.devices().single().retired, "b counts as retired after 180 days unseen")
        assertTrue(batches("a").min() > 2, "a's old batches were deleted without waiting for b: ${batches("a")}")

        assertTrue(b.sync() is SyncState.Idle)                  // b returns
        assertEquals(1, b.engine.lastCycle.snapshotsApplied)
        assertTrue(a.sync() is SyncState.Idle)
        b.sync()

        assertEquals(a.store.mergedState(), b.store.mergedState())
        assertEquals("made on b just before going away", a.titles()["offline-edit"], "its offline work is not lost")
        assertEquals("a's edit 19", b.titles()["n1"], "the newer edit wins over the one made 200 days ago")
        assertEquals(0, b.store.pendingCount())
        assertFalse(a.engine.devices().single().retired, "back, so active again")
    }

    @Test
    fun `a crash between announcing a snapshot and deleting batches loses nothing`() = runBlocking {
        val a = Device("a")
        repeat(4) { a.write("n$it", "t$it") }
        a.blobs.plan = object : FaultPlan {
            override fun before(op: BlobOp, path: String) {
                if (op == BlobOp.Delete) throw SimulatedCrash()
            }
        }
        assertThrows(SimulatedCrash::class.java) { runBlocking { a.sync() } }
        assertEquals(4, batches("a").size, "announced but not yet deleted")
        assertEquals(4L, manifest(a).compactedThrough)

        val c = Device("c")
        assertTrue(c.sync() is SyncState.Idle)
        assertEquals(a.store.mergedState(), c.store.mergedState())

        a.blobs.plan = FaultPlan.None
        a.restart()
        repeat(3) { a.write("m$it", "after the crash") }
        assertTrue(a.sync() is SyncState.Idle)
        assertEquals(emptyList<Long>(), batches("a").filter { it <= 4 }, "the leftovers are cleaned up by the next compaction")
        c.sync()
        assertEquals(a.store.mergedState(), c.store.mergedState())
    }

    @Test
    fun `nothing is compacted while changes are quarantined`() = runBlocking {
        // b cannot represent some of what it has pulled, so a snapshot of b would be incomplete.
        val picky = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS))) { c ->
            if ((c.value as? Value.Text)?.value == "refused") "not accepted here" else null
        }
        val a = Device("a", compactAfter = 0); val b = Device("b", registry = picky)
        a.write("x", "refused"); a.sync()
        repeat(5) { b.write("n$it", "t") }
        assertTrue(b.sync() is SyncState.Error)
        assertEquals(emptyList<String>(), snapshots("b"))
        assertEquals(5, batches("b").size)
    }

    @Test
    fun `a damaged snapshot is reported and does not stop sync with other devices`() = runBlocking {
        val a = Device("a"); val b = Device("b", compactAfter = 0)
        repeat(5) { a.write("n$it", "t") }
        a.sync()
        b.write("fromb", "b's note"); b.sync()
        val snapshot = snapshots("a").single()
        val good = cloud.raw(snapshot)!!
        cloud.tamper(snapshot, byteArrayOf(1, 2, 3))

        val c = Device("c")
        assertEquals(SyncState.Error(SyncError.Corrupt(snapshot), null), c.sync())
        assertEquals("b's note", c.titles()["fromb"])
        assertEquals(1L, c.engine.devices().single { it.replicaId == b.id }.let { 1L }, "b is still listed")

        cloud.tamper(snapshot, good)
        assertTrue(c.sync() is SyncState.Idle)
        a.sync()
        assertEquals(a.store.mergedState(), c.store.mergedState())
    }

    @Test
    fun `a lagging listing cannot make a device forget what it compacted`() = runBlocking {
        val a = Device("a")
        repeat(4) { a.write("n$it", "t") }
        a.sync()
        val compacted = manifest(a).compactedThrough
        assertTrue(compacted > 0)

        a.blobs.plan = object : FaultPlan {
            override fun hiddenFromListing(path: String) = path == Paths.replicaFile(a.id)
        }
        a.restart()                                            // nothing in memory either
        a.write("later", "t"); a.sync()
        assertEquals(compacted, manifest(a).compactedThrough)
        assertEquals(manifest(a).snapshot, snapshots("a").single())
    }
}
