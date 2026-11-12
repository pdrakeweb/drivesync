package io.github.pdrakeweb.drivesync.backend.drive

import io.github.pdrakeweb.drivesync.core.BlobInfo
import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.Page
import io.github.pdrakeweb.drivesync.core.PutResult
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** An OAuth 2 access token carrying the `https://www.googleapis.com/auth/drive.appdata` scope. */
public class AccessToken(public val value: String)

/**
 * A [BlobStore] over the Google Drive app-data folder: a per-app space hidden from the person's
 * Drive, reachable with the `drive.appdata` scope only.
 *
 * Drive has no paths and allows several files with one name, so a storage path is used as the file
 * name, and when duplicates exist the oldest is the object and the rest are ignored (and removed
 * by the device that made them). Preconditions (`If-Match`, ETags) are never used: correctness
 * rests on every file having a single writer (ADR-0001).
 *
 * Authorization is the app's job: [token] supplies a current access token each time.
 */
public class DriveBlobStore(
    private val token: suspend () -> AccessToken,
    private val client: OkHttpClient = OkHttpClient(),
    baseUrl: String = "https://www.googleapis.com/",
    /** Prefix of every file name, so one app-data folder can hold more than one format version. */
    private val root: String = "drivesync/v1/",
    private val maxAttempts: Int = 5,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) : BlobStore {
    private val base = baseUrl.toHttpUrl()
    private val ids = ConcurrentHashMap<String, String>()

    private class DriveFile(val id: String, val name: String, val size: Long, val created: String)

    override suspend fun list(prefix: String, pageToken: String?): Page<BlobInfo> {
        // Listing everything in the space and filtering here avoids depending on how Drive's
        // "name contains" tokenises names with slashes. The space holds only this app's files.
        val url = base.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", SPACE)
            .addQueryParameter("q", "trashed = false")
            .addQueryParameter("orderBy", "createdTime")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
            .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
            .build()
        val json = call(Request.Builder().url(url).get()) { it.json() }
        val wanted = root + prefix
        val items = files(json).filter { it.name.startsWith(wanted) }
            .groupBy { it.name }.values.map { winner(it) }
            .map { BlobInfo(it.name.removePrefix(root), it.size) }
        return Page(items, json["nextPageToken"]?.jsonPrimitive?.content)
    }

    override suspend fun get(path: String): ByteArray? {
        repeat(2) {
            val id = ids[path] ?: lookup(path).let { found -> if (found.isEmpty()) return null else winner(found).id.also { ids[path] = it } }
            val url = base.newBuilder().addPathSegments("drive/v3/files/$id").addQueryParameter("alt", "media").build()
            val bytes = attempt(Request.Builder().url(url).get(), missingIsNull = true) { it.body.bytes() }
            if (bytes != null) return bytes
            ids.remove(path) // the cached id is stale; look the name up once more
        }
        return null
    }

    override suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult {
        val existing = lookup(path)
        if (existing.isNotEmpty()) {
            ids[path] = winner(existing).id
            return PutResult.AlreadyExists
        }
        val created = create(path, bytes)
        // Drive cannot create-if-absent. If a retry or a race produced a second file with this
        // name, the oldest is the object; a newer one of ours is removed.
        val all = lookup(path)
        val winner = if (all.isEmpty()) created else winner(all)
        ids[path] = winner.id
        if (winner.id == created.id) return PutResult.Created
        deleteId(created.id)
        return PutResult.AlreadyExists
    }

    override suspend fun putOwned(path: String, bytes: ByteArray) {
        val existing = lookup(path)
        if (existing.isEmpty()) {
            ids[path] = create(path, bytes).id
            return
        }
        val keep = winner(existing)
        val url = base.newBuilder().addPathSegments("upload/drive/v3/files/${keep.id}").addQueryParameter("uploadType", "media").build()
        call(Request.Builder().url(url).patch(bytes.toRequestBody(OCTETS))) { }
        ids[path] = keep.id
        existing.filter { it.id != keep.id }.forEach { deleteId(it.id) }
    }

    override suspend fun delete(path: String) {
        lookup(path).forEach { deleteId(it.id) }
        ids.remove(path)
    }

    private suspend fun deleteId(id: String) {
        val url = base.newBuilder().addPathSegments("drive/v3/files/$id").build()
        attempt(Request.Builder().url(url).delete(), missingIsNull = true) { }
    }

    private fun winner(files: List<DriveFile>): DriveFile = files.minWith(compareBy({ it.created }, { it.id }))

    private suspend fun lookup(path: String): List<DriveFile> {
        val name = (root + path).replace("\\", "\\\\").replace("'", "\\'")
        val url = base.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", SPACE)
            .addQueryParameter("q", "name = '$name' and trashed = false")
            .addQueryParameter("fields", "files($FILE_FIELDS)")
            .build()
        return files(call(Request.Builder().url(url).get()) { it.json() })
    }

    private suspend fun create(path: String, bytes: ByteArray): DriveFile {
        val metadata = buildJsonObject {
            put("name", JsonPrimitive(root + path))
            put("parents", buildJsonArray { add(JsonPrimitive(SPACE)) })
        }.toString()
        val json = if (bytes.size <= SIMPLE_UPLOAD_LIMIT) {
            val url = base.newBuilder().addPathSegments("upload/drive/v3/files")
                .addQueryParameter("uploadType", "multipart").addQueryParameter("fields", FILE_FIELDS).build()
            call(Request.Builder().url(url).post(multipart(metadata, bytes))) { it.json() }
        } else {
            val start = base.newBuilder().addPathSegments("upload/drive/v3/files")
                .addQueryParameter("uploadType", "resumable").addQueryParameter("fields", FILE_FIELDS).build()
            val session = call(
                Request.Builder().url(start).post(metadata.toRequestBody(JSON_TYPE))
                    .header("X-Upload-Content-Type", "application/octet-stream")
                    .header("X-Upload-Content-Length", bytes.size.toString()),
            ) { it.header("Location") ?: throw SyncException(SyncError.StorageUnavailable("no upload session")) }
            call(Request.Builder().url(session).put(bytes.toRequestBody(OCTETS))) { it.json() }
        }
        return file(json)
    }

    private fun multipart(metadata: String, bytes: ByteArray): RequestBody {
        val boundary = "drivesync-" + random.nextLong().toULong().toString(16)
        val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n"
        val tail = "\r\n--$boundary--"
        return (head.encodeToByteArray() + bytes + tail.encodeToByteArray())
            .toRequestBody("multipart/related; boundary=$boundary".toMediaType())
    }

    private fun files(json: JsonObject): List<DriveFile> = (json["files"] as? JsonArray).orEmpty().map { file(it.jsonObject) }

    private fun file(json: JsonObject) = DriveFile(
        id = json.getValue("id").jsonPrimitive.content,
        name = json.getValue("name").jsonPrimitive.content,
        size = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
        created = json["createdTime"]?.jsonPrimitive?.content.orEmpty(),
    )

    private fun Response.json(): JsonObject = try {
        Json.parseToJsonElement(body.string()).jsonObject
    } catch (e: RuntimeException) {
        throw SyncException(SyncError.StorageUnavailable("unreadable response from Drive"), e)
    }

    /**
     * Runs one request with authorization, mapping Drive's failures to typed errors and retrying
     * rate limits and server errors with exponential backoff and jitter.
     */
    private suspend fun <T : Any> call(request: Request.Builder, read: (Response) -> T): T =
        attempt(request, missingIsNull = false, read = read) ?: throw SyncException(SyncError.StorageUnavailable("empty response"))

    /** As [call], but a 404 gives null when [missingIsNull]. */
    private suspend fun <T : Any> attempt(request: Request.Builder, missingIsNull: Boolean, read: (Response) -> T): T? {
        var wait = 500L
        var last = "no response"
        repeat(maxAttempts) { attempt ->
            val authorized = request.header("Authorization", "Bearer " + token().value).build()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    client.newCall(authorized).execute().use { response ->
                        when {
                            response.isSuccessful -> Outcome.Done(read(response))
                            response.code == 404 && missingIsNull -> Outcome.Done(null)
                            else -> classify(response)
                        }
                    }
                }
                when (outcome) {
                    is Outcome.Done<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        return outcome.value as T?
                    }
                    is Outcome.Fail -> throw SyncException(outcome.error)
                    is Outcome.Retry -> {
                        last = outcome.why
                        if (attempt < maxAttempts - 1) pause((outcome.afterMillis ?: wait) + random.nextLong(0, wait / 2 + 1))
                        wait = (wait * 2).coerceAtMost(MAX_WAIT)
                    }
                }
            } catch (e: IOException) {
                throw SyncException(SyncError.NetworkUnavailable, e)
            }
        }
        throw SyncException(SyncError.StorageUnavailable(last))
    }

    private sealed interface Outcome {
        class Done<T>(val value: T) : Outcome
        class Fail(val error: SyncError) : Outcome
        class Retry(val why: String, val afterMillis: Long?) : Outcome
    }

    private fun classify(response: Response): Outcome {
        val reason = try {
            Json.parseToJsonElement(response.body.string()).jsonObject["error"]?.jsonObject
                ?.get("errors")?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.content
        } catch (e: RuntimeException) {
            null
        } ?: ""
        val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
        return when {
            response.code == 401 -> Outcome.Fail(SyncError.AuthExpired)
            response.code == 403 && reason in QUOTA_REASONS -> Outcome.Fail(SyncError.QuotaExceeded)
            response.code == 403 && reason in RATE_REASONS -> Outcome.Retry("rate limited", retryAfter)
            // Any other refusal means the token is not allowed to do this: the person must sign in again.
            response.code == 403 -> Outcome.Fail(SyncError.AuthExpired)
            response.code == 429 || response.code >= 500 -> Outcome.Retry("HTTP ${response.code}", retryAfter)
            else -> Outcome.Fail(SyncError.StorageUnavailable("HTTP ${response.code} $reason".trim()))
        }
    }

    private companion object {
        const val SPACE = "appDataFolder"
        const val FILE_FIELDS = "id,name,size,createdTime"
        const val SIMPLE_UPLOAD_LIMIT = 5 * 1024 * 1024
        const val MAX_WAIT = 32_000L
        val OCTETS = "application/octet-stream".toMediaType()
        val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
        val QUOTA_REASONS = setOf("storageQuotaExceeded", "quotaExceeded", "teamDriveFileLimitExceeded")
        val RATE_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}
