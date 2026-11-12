package io.github.pdrakeweb.drivesync.core

import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A store that was restored from a backup, or copied to a second phone, shares its identity with
 * another life of itself. The engine refuses to push ([SyncError.ReplicaStateBehind]); the fix is
 * a new identity. These tests show that the fix loses nothing and that everyone converges (C-1).
 */
class IdentityResetTest {
    private val registry = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
    private val cloud = InMemoryBlobStore()
    private var now = 1_800_000_000_000L

    private inner class Device(val store: InMemoryLocalStore) {
        constructor(name: String) : this(InMemoryLocalStore(ReplicaId(name), registry) { now })

        var engine = newEngine()

        private fun newEngine() =
            SyncEngine(store.replicaId, store, cloud, InsecureTestKeyManager(), registry, SyncConfig("device", "test")) { now }

        fun write(row: String, title: String) = store.write("notes", row, mapOf("title" to Value.Text(title)))
        fun titles() = store.rows("notes").mapValues { (it.value["title"] as Value.Text).value }.toSortedMap()
        suspend fun sync(): SyncState { now += 1_000; return engine.sync() }

        /** What an app does for [Fix.ResetThisDevice]: new identity, then a new engine. */
        fun reset(newName: String) {
            store.resetIdentity(ReplicaId(newName))
            engine = newEngine()
        }
    }

    private suspend fun settle(vararg devices: Device) = repeat(3) { devices.forEach { assertTrue(it.sync() is SyncState.Idle) } }

    @Test
    fun `a restored store that takes a new identity catches up and keeps what it wrote`() = runBlocking {
        val original = Device("a"); val b = Device("b")
        original.write("n1", "one"); original.sync()
        original.write("n2", "two"); original.sync()
        b.sync()

        val restored = Device("a") // the same identity, with an empty database
        restored.write("n9", "written after the restore")
        assertTrue((restored.sync() as SyncState.Error).fix == Fix.ResetThisDevice)

        restored.reset("a2")
        assertEquals(1, restored.store.pendingCount(), "the pending change survives the reset")
        settle(restored, b)

        val all = sortedMapOf("n1" to "one", "n2" to "two", "n9" to "written after the restore")
        assertEquals(all, restored.titles())
        assertEquals(all, b.titles())
        assertEquals(restored.store.mergedState(), b.store.mergedState())
    }

    @Test
    fun `a cloned store and its original both keep writing and converge`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "one"); a.sync()
        a.write("n2", "pending on both when the phone was cloned")

        val clone = Device(a.store.copy())
        a.write("n3", "from the original"); a.sync()
        clone.write("n4", "from the clone")
        clone.write("n1", "one, edited on the clone")
        assertEquals(
            SyncState.Error(SyncError.ReplicaStateBehind(localSeq = 1, storedSeq = 2), Fix.ResetThisDevice),
            clone.sync(),
        )

        clone.reset("a-clone")
        settle(clone, a, b)

        val all = sortedMapOf(
            "n1" to "one, edited on the clone",
            "n2" to "pending on both when the phone was cloned",
            "n3" to "from the original",
            "n4" to "from the clone",
        )
        assertEquals(all, a.titles())
        assertEquals(all, clone.titles())
        assertEquals(all, b.titles())
        assertEquals(a.store.mergedState(), clone.store.mergedState())
        assertEquals(a.store.mergedState(), b.store.mergedState())
    }

    @Test
    fun `changes already uploaded keep their author, pending ones take the new identity`() = runBlocking {
        val a = Device("a")
        a.write("n1", "uploaded"); a.sync()
        a.write("n2", "pending")
        a.store.resetIdentity(ReplicaId("a2"))

        assertEquals(ReplicaId("a"), a.store.cells(RowRef("notes", "n1")).getValue("title").hlc.replica)
        assertEquals(ReplicaId("a2"), a.store.cells(RowRef("notes", "n2")).getValue("title").hlc.replica)
        assertEquals(listOf(ReplicaId("a2")), a.store.drainOutbox(10).map { it.hlc.replica })
        assertTrue(a.write("n3", "after").replica == ReplicaId("a2"))
    }

    @Test
    fun `a deletion pending at the reset still deletes everywhere`() = runBlocking {
        val a = Device("a"); val b = Device("b")
        a.write("n1", "one"); a.sync(); b.sync()
        val clone = Device(a.store.copy())
        a.write("n2", "two"); a.sync()
        clone.store.delete("notes", "n1")
        clone.sync()
        clone.reset("a-clone")
        settle(clone, a, b)
        assertEquals(sortedMapOf("n2" to "two"), a.titles())
        assertEquals(a.store.mergedState(), b.store.mergedState())
        assertEquals(a.store.mergedState(), clone.store.mergedState())
    }
}
