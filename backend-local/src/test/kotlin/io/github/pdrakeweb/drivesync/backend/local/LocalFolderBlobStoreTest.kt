package io.github.pdrakeweb.drivesync.backend.local

import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.PutResult
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncState
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import io.github.pdrakeweb.drivesync.testing.BlobStoreContract
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalFolderBlobStoreContractTest : BlobStoreContract() {
    @TempDir
    lateinit var dir: Path
    private var n = 0

    override fun newStore(): BlobStore = LocalFolderBlobStore(dir.resolve("store-${n++}"), pageSize = 50)
}

class LocalFolderBlobStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `paths that could escape the folder are refused`(): Unit = runBlocking {
        val store = LocalFolderBlobStore(dir.resolve("root"))
        for (bad in listOf("../outside", "a/../../outside", "/absolute", "a//b", "a\\b", "C:/x", "")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.putOwned(bad, byteArrayOf(1)) } }
        }
        assertEquals(emptyList<Path>(), Files.list(dir).use { s -> s.filter { it.fileName.toString() != "root" }.toList() })
    }

    @Test
    fun `only one of many concurrent creators wins, and no temporary files remain`(): Unit = runBlocking {
        val root = dir.resolve("race")
        val results = (1..24).map { i ->
            async(Dispatchers.IO) { LocalFolderBlobStore(root).putIfAbsent("batches/a/x.bin", byteArrayOf(i.toByte())) }
        }.awaitAll()
        assertEquals(1, results.count { it == PutResult.Created })
        assertEquals(listOf("batches/a/x.bin"), LocalFolderBlobStore(root).list("").items.map { it.path })
        assertEquals(1L, Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.count() })
    }

    @Test
    fun `two devices sync through a shared folder`(): Unit = runBlocking {
        val registry = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
        val folder = dir.resolve("shared")
        var now = 1_800_000_000_000L
        class Device(name: String) {
            val id = ReplicaId(name)
            val store = InMemoryLocalStore(id, registry) { now }
            val engine = SyncEngine(id, store, LocalFolderBlobStore(folder), InsecureTestKeyManager(), registry,
                SyncConfig(name, "jvm")) { now }
        }
        val phone = Device("phone"); val tablet = Device("tablet")
        phone.store.write("notes", "n1", mapOf("title" to Value.Text("written on the phone")))
        tablet.store.write("notes", "n2", mapOf("title" to Value.Text("written on the tablet")))
        for (d in listOf(phone, tablet, phone)) {
            now += 1_000
            assertTrue(d.engine.sync() is SyncState.Idle)
        }
        assertEquals(phone.store.mergedState(), tablet.store.mergedState())
        assertEquals(setOf("n1", "n2"), tablet.store.rows("notes").keys)
        val files = Files.walk(folder).use { s -> s.filter { Files.isRegularFile(it) }.map { folder.relativize(it).toString().replace('\\', '/') }.toList() }
        assertEquals(4, files.size, files.toString())
        assertTrue(files.all { Regex("replicas/(phone|tablet)\\.json|batches/(phone|tablet)/\\d{12}-[0-9a-f]{16}\\.bin").matches(it) })
    }
}
