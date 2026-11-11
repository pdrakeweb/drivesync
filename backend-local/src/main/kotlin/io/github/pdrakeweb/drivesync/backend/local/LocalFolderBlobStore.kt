package io.github.pdrakeweb.drivesync.backend.local

import io.github.pdrakeweb.drivesync.core.BlobInfo
import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.Page
import io.github.pdrakeweb.drivesync.core.PutResult
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * A [BlobStore] over a directory: for tests, development, and moving data by hand (a USB stick, a
 * folder synced by another tool). Several processes may use the same directory.
 *
 * Objects become visible atomically: content is written to a temporary file and then linked or
 * moved into place, so a reader never sees half an object.
 */
public class LocalFolderBlobStore(root: Path, private val pageSize: Int = 1_000) : BlobStore {
    private val root: Path = root.toAbsolutePath().normalize()

    init {
        require(pageSize > 0)
        Files.createDirectories(this.root)
    }

    override suspend fun list(prefix: String, pageToken: String?): Page<BlobInfo> = io {
        val all = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && !it.name.startsWith(TEMP_PREFIX) }
                .map { BlobInfo(root.relativize(it).joinToString("/") { part -> part.name }, it.fileSize()) }
                .filter { it.path.startsWith(prefix) && (pageToken == null || it.path > pageToken) }
                .sorted(compareBy { it.path })
                .limit(pageSize + 1L)
                .toList()
        }
        val items = all.take(pageSize)
        Page(items, if (all.size > pageSize) items.last().path else null)
    }

    override suspend fun get(path: String): ByteArray? = io {
        try {
            Files.readAllBytes(resolve(path))
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult = io {
        val target = resolve(path)
        if (Files.exists(target)) return@io PutResult.AlreadyExists
        val temp = writeTemp(target, bytes)
        try {
            try {
                // A hard link fails if the name is taken, which makes create-if-absent atomic.
                Files.createLink(target, temp)
            } catch (e: UnsupportedOperationException) {
                // File systems without hard links (FAT on removable media): a plain move, which
                // also refuses to replace an existing file.
                Files.move(temp, target)
            }
            PutResult.Created
        } catch (e: FileAlreadyExistsException) {
            PutResult.AlreadyExists
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun putOwned(path: String, bytes: ByteArray): Unit = io {
        val target = resolve(path)
        val temp = writeTemp(target, bytes)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override suspend fun delete(path: String): Unit = io {
        Files.deleteIfExists(resolve(path))
    }

    private fun writeTemp(target: Path, bytes: ByteArray): Path {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling(TEMP_PREFIX + UUID.randomUUID())
        Files.write(temp, bytes)
        return temp
    }

    private fun resolve(path: String): Path {
        require(SAFE_PATH.matches(path) && ".." !in path && !path.substringAfterLast('/').startsWith(TEMP_PREFIX)) {
            "not a storage path: $path"
        }
        val resolved = root.resolve(path).normalize()
        require(resolved.startsWith(root)) { "path escapes the root: $path" }
        return resolved
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            val full = e.message.orEmpty().contains("space", ignoreCase = true)
            throw SyncException(if (full) SyncError.QuotaExceeded else SyncError.StorageUnavailable(e.toString()), e)
        }
    }

    private companion object {
        const val TEMP_PREFIX = ".tmp-"
        val SAFE_PATH = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")
    }
}
