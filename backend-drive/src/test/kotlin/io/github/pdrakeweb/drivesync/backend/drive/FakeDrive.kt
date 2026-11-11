package io.github.pdrakeweb.drivesync.backend.drive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the parts of Drive REST v3 that [DriveBlobStore] uses, faithful to the behaviour
 * that matters: several files may share a name, there is no create-if-absent, listings are paged,
 * and every request needs a bearer token. Not Google's code; the real service is the opt-in test.
 */
class FakeDrive : AutoCloseable {
    class File(val id: String, val name: String, var bytes: ByteArray, val created: Int, val parents: List<String>)

    val files = CopyOnWriteArrayList<File>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Responses to serve instead of handling the request, in order. */
    val forced = ArrayDeque<MockResponse>()

    /** When true, the next create is performed but answered with a 503, as if the reply was lost. */
    var loseNextCreateResponse = false
    var validToken = "good-token"
    var pageSize = 1000
    private var counter = 0
    private val sessions = HashMap<String, String>()
    private val server = MockWebServer()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeDrive) {
                requests += request
                try {
                    handle(request)
                } catch (e: Exception) {
                    json(500, """{"error":{"errors":[{"reason":"fakeDriveBug"}],"message":"$e"}}""")
                }
            }
        }
        server.start()
    }

    val baseUrl: String get() = server.url("/").toString()

    override fun close() = server.close()

    fun error(code: Int, reason: String, retryAfter: Int? = null): MockResponse {
        val builder = MockResponse.Builder().code(code)
            .addHeader("Content-Type", "application/json")
            .body("""{"error":{"code":$code,"errors":[{"reason":"$reason"}]}}""")
        if (retryAfter != null) builder.addHeader("Retry-After", retryAfter.toString())
        return builder.build()
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun describe(file: File) = buildJsonObject {
        put("id", JsonPrimitive(file.id))
        put("name", JsonPrimitive(file.name))
        put("size", JsonPrimitive(file.bytes.size.toString()))
        put("createdTime", JsonPrimitive("2026-11-11T00:00:%02d.%03dZ".format(file.created / 1000, file.created % 1000)))
    }

    private fun add(name: String, parents: List<String>, bytes: ByteArray): File =
        File("id-${++counter}", name, bytes, counter, parents).also { files += it }

    private fun handle(request: RecordedRequest): MockResponse {
        if (forced.isNotEmpty()) return forced.removeFirst()
        if (request.headers["Authorization"] != "Bearer $validToken") return error(401, "authError")
        val url = request.url
        val path = url.encodedPath
        val body = request.body?.toByteArray() ?: ByteArray(0)
        return when {
            request.method == "GET" && path == "/drive/v3/files" -> {
                require(url.queryParameter("spaces") == "appDataFolder") { "listing outside the app-data folder" }
                val q = url.queryParameter("q").orEmpty()
                val exact = Regex("name = '((?:[^'\\\\]|\\\\.)*)'").find(q)?.groupValues?.get(1)?.replace("\\'", "'")?.replace("\\\\", "\\")
                val matching = files.filter { exact == null || it.name == exact }.sortedBy { it.created }
                val start = url.queryParameter("pageToken")?.removePrefix("page-")?.toInt() ?: 0
                val page = matching.drop(start).take(pageSize)
                json(200, buildJsonObject {
                    put("files", buildJsonArray { page.forEach { add(describe(it)) } })
                    if (start + pageSize < matching.size) put("nextPageToken", JsonPrimitive("page-${start + pageSize}"))
                }.toString())
            }
            request.method == "POST" && path == "/upload/drive/v3/files" && url.queryParameter("uploadType") == "multipart" -> {
                val boundary = request.headers["Content-Type"]!!.substringAfter("boundary=")
                val (metadata, media) = splitMultipart(body, boundary)
                created(metadata, media)
            }
            request.method == "POST" && path == "/upload/drive/v3/files" && url.queryParameter("uploadType") == "resumable" -> {
                val session = "session-${++counter}"
                sessions[session] = body.decodeToString()
                MockResponse.Builder().code(200).addHeader("Location", server.url("/upload/session/$session").toString()).build()
            }
            request.method == "PUT" && path.startsWith("/upload/session/") ->
                created(sessions.remove(path.substringAfterLast('/'))!!, body)
            request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                val file = files.firstOrNull { it.id == path.substringAfterLast('/') } ?: return error(404, "notFound")
                file.bytes = body
                json(200, describe(file).toString())
            }
            request.method == "GET" && path.startsWith("/drive/v3/files/") -> {
                val file = files.firstOrNull { it.id == path.substringAfterLast('/') } ?: return error(404, "notFound")
                MockResponse.Builder().code(200).body(Buffer().write(file.bytes)).build()
            }
            request.method == "DELETE" && path.startsWith("/drive/v3/files/") ->
                if (files.removeIf { it.id == path.substringAfterLast('/') }) MockResponse.Builder().code(204).build() else error(404, "notFound")
            else -> error(400, "unsupportedByFakeDrive")
        }
    }

    private fun created(metadata: String, media: ByteArray): MockResponse {
        val meta = Json.parseToJsonElement(metadata).jsonObject
        val parents = meta["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val file = add(meta.getValue("name").jsonPrimitive.content, parents, media)
        if (loseNextCreateResponse) {
            loseNextCreateResponse = false
            return error(503, "backendError")
        }
        return json(200, describe(file).toString())
    }

    private fun splitMultipart(body: ByteArray, boundary: String): Pair<String, ByteArray> {
        fun indexOf(needle: ByteArray, from: Int): Int {
            var i = from
            while (i <= body.size - needle.size) {
                if (needle.indices.all { body[i + it] == needle[it] }) return i
                i++
            }
            error("multipart boundary not found")
        }
        val mark = "--$boundary".encodeToByteArray()
        val blank = "\r\n\r\n".encodeToByteArray()
        val first = indexOf(mark, 0)
        val second = indexOf(mark, first + mark.size)
        val metaStart = indexOf(blank, first) + blank.size
        val metadata = body.copyOfRange(metaStart, second - 2).decodeToString()
        val mediaStart = indexOf(blank, second) + blank.size
        val closing = "\r\n--$boundary--".encodeToByteArray()
        return metadata to body.copyOfRange(mediaStart, body.size - closing.size)
    }
}
