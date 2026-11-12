package io.github.pdrakeweb.drivesync.backend.drive

import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.PutResult
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.testing.BlobStoreContract
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** The shared contract, against the fake Drive with small pages so paging is exercised. */
class DriveBlobStoreContractTest : BlobStoreContract() {
    private val drives = ArrayList<FakeDrive>()

    override fun newStore(): BlobStore {
        val drive = FakeDrive().also { it.pageSize = 40; drives += it }
        return DriveBlobStore(token = { AccessToken("good-token") }, baseUrl = drive.baseUrl)
    }

    @AfterEach
    fun close() = drives.forEach { it.close() }
}

class DriveBlobStoreTest {
    private val drive = FakeDrive()
    private val pauses = ArrayList<Long>()
    private var tokenValue = "good-token"
    private val store = DriveBlobStore(token = { AccessToken(tokenValue) }, baseUrl = drive.baseUrl, pause = { pauses += it })
    private val path = "batches/a/000000000001-0123456789abcdef.bin"

    @AfterEach
    fun close() = drive.close()

    private fun errorOf(block: suspend () -> Unit): SyncError =
        assertThrows(SyncException::class.java) { runBlocking { block() } }.error

    @Test
    fun `files live in the app-data folder under the versioned root, and no precondition is ever sent`() = runBlocking {
        store.putIfAbsent(path, byteArrayOf(1))
        store.putOwned("replicas/a.json", byteArrayOf(2))
        store.putOwned("replicas/a.json", byteArrayOf(3))
        store.get(path); store.list(""); store.delete(path)

        val kept = drive.files.single()
        assertEquals("drivesync/v1/replicas/a.json", kept.name)
        assertEquals(listOf("appDataFolder"), kept.parents)
        assertArrayEquals(byteArrayOf(3), kept.bytes)
        for (request in drive.requests) {
            assertNull(request.headers["If-Match"], "ADR-0001: never rely on preconditions")
            assertNull(request.headers["If-None-Match"])
            assertEquals("Bearer good-token", request.headers["Authorization"])
        }
    }

    @Test
    fun `a create whose response was lost is found again instead of duplicated`() = runBlocking {
        drive.loseNextCreateResponse = true
        // The upload happened; Drive answered 503; the store retries the create.
        val result = store.putIfAbsent(path, byteArrayOf(7))
        assertEquals(1, drive.files.size, "the retry's duplicate was removed")
        assertArrayEquals(byteArrayOf(7), store.get(path))
        assertTrue(result == PutResult.Created || result == PutResult.AlreadyExists)
        assertEquals(PutResult.AlreadyExists, store.putIfAbsent(path, byteArrayOf(8)))
    }

    @Test
    fun `when two files share a name the oldest is the object, everywhere`() = runBlocking {
        val first = DriveBlobStore({ AccessToken("good-token") }, baseUrl = drive.baseUrl)
        store.putIfAbsent(path, byteArrayOf(1))
        // A second device that did not see the first file yet creates its own (Drive allows it).
        drive.files += FakeDrive.File("id-late", "drivesync/v1/$path", byteArrayOf(2), created = 99_000, parents = listOf("appDataFolder"))

        assertArrayEquals(byteArrayOf(1), first.get(path))
        assertEquals(listOf(path), first.list("batches/").items.map { it.path }, "listed once")
        assertEquals(PutResult.AlreadyExists, first.putIfAbsent(path, byteArrayOf(3)))
        first.putOwned(path, byteArrayOf(4))
        assertEquals(1, drive.files.size, "the owner cleans up duplicates")
        assertArrayEquals(byteArrayOf(4), first.get(path))
    }

    @Test
    fun `rate limits and server errors are retried with growing pauses`() = runBlocking {
        drive.forced += drive.error(429, "rateLimitExceeded")
        drive.forced += drive.error(403, "userRateLimitExceeded")
        drive.forced += drive.error(503, "backendError", retryAfter = 7)
        store.putOwned("replicas/a.json", byteArrayOf(1))
        assertEquals(3, pauses.size)
        assertTrue(pauses[0] in 500..750 && pauses[1] in 1000..1500, pauses.toString())
        assertTrue(pauses[2] >= 7000, "Retry-After is honoured: $pauses")
        assertArrayEquals(byteArrayOf(1), store.get("replicas/a.json"))
    }

    @Test
    fun `persistent failures and refusals become typed errors`() {
        repeat(5) { drive.forced += drive.error(500, "backendError") }
        assertTrue(errorOf { store.list("") } is SyncError.StorageUnavailable)

        drive.forced += drive.error(403, "storageQuotaExceeded")
        assertEquals(SyncError.QuotaExceeded, errorOf { store.putOwned("replicas/a.json", ByteArray(1)) })

        drive.forced += drive.error(403, "insufficientPermissions")
        assertEquals(SyncError.AuthExpired, errorOf { store.list("") })

        tokenValue = "expired"
        assertEquals(SyncError.AuthExpired, errorOf { store.get(path) })
        tokenValue = "good-token"

        drive.close()
        assertEquals(SyncError.NetworkUnavailable, errorOf { store.list("") })
    }

    @Test
    fun `a stale cached id is recovered from, and large objects use a resumable upload`() = runBlocking {
        store.putIfAbsent(path, byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), store.get(path))
        val moved = drive.files.single()
        drive.files.clear()
        drive.files += FakeDrive.File("id-new", moved.name, byteArrayOf(5), 50_000, moved.parents)
        assertArrayEquals(byteArrayOf(5), store.get(path), "looked up again after the cached id returned 404")

        val big = ByteArray(5 * 1024 * 1024 + 10) { (it % 251).toByte() }
        store.putIfAbsent("snapshots/a-1.bin", big)
        assertArrayEquals(big, store.get("snapshots/a-1.bin"))
        assertTrue(drive.requests.any { it.url.queryParameter("uploadType") == "resumable" })
    }
}

/**
 * Opt-in: the same contract against a real Google account.
 * `./gradlew :backend-drive:test -PdriveToken=<access token with the drive.appdata scope>`
 * Uses a unique root per run and deletes what it created.
 */
class RealDriveContractTest : BlobStoreContract() {
    private val token = System.getProperty("drivesync.driveToken").orEmpty()
    private val stores = ArrayList<DriveBlobStore>()

    override fun newStore(): BlobStore {
        assumeTrue(token.isNotEmpty(), "no -PdriveToken given; real Drive run skipped")
        return DriveBlobStore({ AccessToken(token) }, root = "drivesync-test-${UUID.randomUUID()}/").also { stores += it }
    }

    @AfterEach
    fun cleanUp() = runBlocking {
        for (store in stores) {
            var page: String? = null
            do {
                val listed = store.list("", page)
                listed.items.forEach { store.delete(it.path) }
                page = listed.nextPageToken
            } while (page != null)
        }
    }
}
