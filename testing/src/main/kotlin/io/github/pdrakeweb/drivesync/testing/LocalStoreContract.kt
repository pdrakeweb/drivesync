package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.Change
import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.LocalStore
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import kotlinx.coroutines.runBlocking

/**
 * The behaviour the sync engine relies on from every [LocalStore]. Framework-neutral, because Android
 * stores are tested under JUnit 4 (Robolectric) and JVM stores under JUnit 5: a test class extends
 * this, and runs each entry of [checks] as one test.
 * A failed check throws [AssertionError].
 */
public abstract class LocalStoreContract {
    protected val registry: MergeRegistry = MergeRegistry(
        1,
        listOf(TableSpec("notes", MergeClass.LWW_FIELDS), TableSpec("settings", MergeClass.LWW_ROW)),
    )

    /** A fresh, empty store for [registry]. */
    protected abstract fun newStore(): LocalStore

    /** Make one local write of [columns] to a row, as the app would. */
    protected abstract fun localWrite(store: LocalStore, table: String, rowId: String, columns: Map<String, Value>)

    private fun text(s: String) = Value.Text(s)

    public fun `outbox holds local writes in timestamp order and counts them`(): Unit = runBlocking {
        val store = newStore()
        assertEquals(0, store.pendingCount())
        localWrite(store, "notes", "n1", mapOf("title" to text("a"), "body" to text("b")))
        localWrite(store, "notes", "n2", mapOf("title" to text("c")))
        assertEquals(3, store.pendingCount())
        val drained = store.drainOutbox(100)
        assertEquals(3, drained.size)
        assertEquals(drained.sortedBy { it.hlc }, drained)
        assertEquals(drained.size, drained.map { it.hlc }.distinct().size, "field writes get distinct timestamps")
    }

    public fun `a drained set is stable until acknowledged`(): Unit = runBlocking {
        val store = newStore()
        repeat(5) { localWrite(store, "notes", "n$it", mapOf("title" to text("v$it"))) }
        val first = store.drainOutbox(3)
        assertEquals(3, first.size)
        repeat(4) { localWrite(store, "notes", "m$it", mapOf("title" to text("later"))) }
        assertEquals(first, store.drainOutbox(3), "same set after more writes")
        assertEquals(first, store.drainOutbox(100), "same set even when asked for more")

        store.ackOutbox(first.maxOf { it.hlc.time })
        assertEquals(6, store.pendingCount())
        val second = store.drainOutbox(100)
        assertEquals(6, second.size)
        assertTrue(second.none { it in first })
        store.ackOutbox(second.maxOf { it.hlc.time })
        assertEquals(emptyList<Change>(), store.drainOutbox(100))
        assertEquals(0, store.pendingCount())
    }

    public fun `a whole-row write shares one timestamp and is never split`(): Unit = runBlocking {
        val store = newStore()
        localWrite(store, "settings", "s", mapOf("a" to text("1"), "b" to text("2"), "c" to text("3")))
        val drained = store.drainOutbox(1)
        assertEquals(3, drained.size)
        assertEquals(1, drained.map { it.hlc }.distinct().size)
    }

    public fun `remote changes are merged but never enter the outbox`(): Unit = runBlocking {
        val store = newStore()
        val remote = Change("notes", "n1", "title", text("remote"), Hlc.of(5_000_000_000_000, 0, ReplicaId("other")))
        val report = store.apply(listOf(remote, remote), registry)
        assertEquals(1, report.applied)
        assertEquals(1, report.ignored)
        assertEquals(0, store.pendingCount())
        assertTrue(remote in store.snapshot().toList())
    }

    public fun `a local write after a remote change from a fast clock still wins`(): Unit = runBlocking {
        val store = newStore()
        val future = Hlc.of(5_000_000_000_000, 7, ReplicaId("other"))
        store.apply(listOf(Change("notes", "n1", "title", text("remote"), future)), registry)
        localWrite(store, "notes", "n1", mapOf("title" to text("local")))
        val mine = store.drainOutbox(10).single()
        assertTrue(mine.hlc > future, "the store's clock absorbed the remote timestamp")
    }

    public fun `cursors are saved per replica`(): Unit = runBlocking {
        val store = newStore()
        assertEquals(emptyMap<ReplicaId, Long>(), store.cursors())
        store.saveCursor(ReplicaId("a"), 3)
        store.saveCursor(ReplicaId("b"), 1)
        store.saveCursor(ReplicaId("a"), 4)
        assertEquals(mapOf(ReplicaId("a") to 4L, ReplicaId("b") to 1L), store.cursors())
    }

    /** Every check, by name. Run each as its own test. */
    public val checks: Map<String, () -> Unit> = linkedMapOf(
        "outbox holds local writes in timestamp order and counts them" to
            { `outbox holds local writes in timestamp order and counts them`() },
        "a drained set is stable until acknowledged" to { `a drained set is stable until acknowledged`() },
        "a whole-row write shares one timestamp and is never split" to
            { `a whole-row write shares one timestamp and is never split`() },
        "remote changes are merged but never enter the outbox" to
            { `remote changes are merged but never enter the outbox`() },
        "a local write after a remote change from a fast clock still wins" to
            { `a local write after a remote change from a fast clock still wins`() },
        "cursors are saved per replica" to { `cursors are saved per replica`() },
    )

    private fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
        if (expected != actual) throw AssertionError("$message expected <$expected> but was <$actual>".trim())
    }

    private fun assertTrue(condition: Boolean, message: String = "expected true") {
        if (!condition) throw AssertionError(message)
    }
}
