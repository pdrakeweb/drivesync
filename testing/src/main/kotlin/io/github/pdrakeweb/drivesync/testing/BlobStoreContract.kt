package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.PutResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The behaviour every [BlobStore] must have. Each backend's test module extends this class and
 * supplies a fresh, empty store.
 */
public abstract class BlobStoreContract {
    protected abstract fun newStore(): BlobStore

    private suspend fun BlobStore.listAll(prefix: String): List<String> {
        val all = ArrayList<String>()
        var token: String? = null
        var pages = 0
        do {
            val page = list(prefix, token)
            all += page.items.map { it.path }
            token = page.nextPageToken
            check(++pages < 10_000) { "listing never ends" }
        } while (token != null)
        return all
    }

    @Test
    public fun `get of a missing object is null`(): Unit = runBlocking {
        assertNull(newStore().get("batches/a/000000000001-0123456789abcdef.bin"))
    }

    @Test
    public fun `putIfAbsent creates once and never overwrites`(): Unit = runBlocking {
        val store = newStore()
        val path = "batches/a/000000000001-0123456789abcdef.bin"
        assertEquals(PutResult.Created, store.putIfAbsent(path, byteArrayOf(1, 2, 3)))
        assertEquals(PutResult.AlreadyExists, store.putIfAbsent(path, byteArrayOf(9, 9)))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.get(path))
    }

    @Test
    public fun `putOwned creates and replaces`(): Unit = runBlocking {
        val store = newStore()
        store.putOwned("replicas/a.json", byteArrayOf(1))
        store.putOwned("replicas/a.json", byteArrayOf(2, 2))
        assertArrayEquals(byteArrayOf(2, 2), store.get("replicas/a.json"))
    }

    @Test
    public fun `delete removes and tolerates a missing object`(): Unit = runBlocking {
        val store = newStore()
        store.putOwned("replicas/a.json", byteArrayOf(1))
        store.delete("replicas/a.json")
        store.delete("replicas/a.json")
        assertNull(store.get("replicas/a.json"))
        assertEquals(emptyList<String>(), store.listAll("replicas/"))
    }

    @Test
    public fun `content round-trips byte for byte`(): Unit = runBlocking {
        val store = newStore()
        val bytes = ByteArray(70_000) { (it * 31 + it / 256).toByte() }
        store.putIfAbsent("snapshots/a-1.bin", bytes)
        assertArrayEquals(bytes, store.get("snapshots/a-1.bin"))
        assertArrayEquals(ByteArray(0), store.also { it.putOwned("replicas/empty.json", ByteArray(0)) }.get("replicas/empty.json"))
    }

    @Test
    public fun `list returns exactly the objects under the prefix across pages`(): Unit = runBlocking {
        val store = newStore()
        val wanted = (1..230).map { "batches/a/${it.toString().padStart(12, '0')}-0123456789abcdef.bin" }
        for (path in wanted) store.putIfAbsent(path, byteArrayOf(1))
        store.putIfAbsent("batches/ab/000000000001-0123456789abcdef.bin", byteArrayOf(1))
        store.putIfAbsent("batches/b/000000000001-0123456789abcdef.bin", byteArrayOf(1))
        store.putOwned("replicas/a.json", byteArrayOf(1))
        store.putOwned("meta.json", byteArrayOf(1))

        assertEquals(wanted, store.listAll("batches/a/").sorted())
        assertEquals(listOf("replicas/a.json"), store.listAll("replicas/"))
        assertEquals(234, store.listAll("").size)
        assertEquals(emptyList<String>(), store.listAll("attachments/"))
    }

    @Test
    public fun `list reports sizes`(): Unit = runBlocking {
        val store = newStore()
        store.putOwned("replicas/a.json", ByteArray(41))
        assertEquals(41L, store.list("replicas/").items.single().size)
    }
}
