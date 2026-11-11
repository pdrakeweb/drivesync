@file:OptIn(ExperimentalSerializationApi::class)

package io.github.pdrakeweb.drivesync.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** The current version of every wire format. Readers reject anything newer with a typed error. */
public const val WIRE_FORMAT: Int = 1

@Serializable
public data class BatchHeader(
    val format: Int = WIRE_FORMAT,
    val replicaId: ReplicaId,
    val seq: Long,
    val schemaVersion: Int,
    val hlcFrom: Hlc,
    val hlcTo: Hlc,
    val count: Int,
)

/** An immutable bundle of one replica's changes. */
@Serializable
public data class Batch(val header: BatchHeader, val changes: List<Change>) {
    public companion object {
        public fun of(replica: ReplicaId, seq: Long, schemaVersion: Int, changes: List<Change>): Batch {
            require(changes.isNotEmpty()) { "a batch has at least one change" }
            val header = BatchHeader(
                replicaId = replica,
                seq = seq,
                schemaVersion = schemaVersion,
                hlcFrom = changes.minOf { it.hlc },
                hlcTo = changes.maxOf { it.hlc },
                count = changes.size,
            )
            return Batch(header, changes)
        }
    }
}

/** A replica's own manifest, `replicas/<replicaId>.json`. Written only by that replica. */
@Serializable
public data class ReplicaFile(
    val format: Int = WIRE_FORMAT,
    val replicaId: ReplicaId,
    val label: String,
    val platform: String,
    val lastSeen: Long,
    val lastSeq: Long,
    val cursors: Map<String, Long>,
    val schemaVersion: Int,
    val retired: Boolean = false,
)

@Serializable
private data class FormatProbe(val format: Int = 0)

@Serializable
private data class BatchProbe(val header: FormatProbe = FormatProbe())

/** Encoding and decoding of the versioned wire formats. Formats only ever add fields. */
public object WireCodec {
    // encodeDefaults: the format field has a default and must still be written.
    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Deterministic: the same batch always encodes to the same bytes, so a retry reuses its name. */
    public fun encodeBatch(batch: Batch): ByteArray = cbor.encodeToByteArray(Batch.serializer(), batch)

    public fun decodeBatch(path: String, plain: ByteArray): Batch = decoding(path) {
        val format = cbor.decodeFromByteArray(BatchProbe.serializer(), plain).header.format
        if (format != WIRE_FORMAT) throw SyncException(SyncError.UnsupportedFormat(path, format))
        val batch = cbor.decodeFromByteArray(Batch.serializer(), plain)
        if (batch.header.count != batch.changes.size) throw SyncException(SyncError.Corrupt(path))
        batch
    }

    public fun encodeReplicaFile(file: ReplicaFile): ByteArray =
        json.encodeToString(ReplicaFile.serializer(), file).encodeToByteArray()

    public fun decodeReplicaFile(path: String, plain: ByteArray): ReplicaFile = decoding(path) {
        val text = plain.decodeToString()
        val format = json.decodeFromString(FormatProbe.serializer(), text).format
        if (format != WIRE_FORMAT) throw SyncException(SyncError.UnsupportedFormat(path, format))
        json.decodeFromString(ReplicaFile.serializer(), text)
    }

    private inline fun <T> decoding(path: String, block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw SyncException(SyncError.Corrupt(path), e)
    } catch (e: IllegalArgumentException) {
        throw SyncException(SyncError.Corrupt(path), e)
    }
}

/** Object paths, relative to the sync root `drivesync/v1/`. Names carry ids, numbers and hashes only. */
public object Paths {
    public const val REPLICAS: String = "replicas/"
    public const val META: String = "meta.json"

    public fun replicaFile(replica: ReplicaId): String = "$REPLICAS${replica.value}.json"

    public fun batchPrefix(replica: ReplicaId): String = "batches/${replica.value}/"

    /** `batches/<replica>/<seq, 12 digits>-<first 16 hex of sha256(plaintext)>.bin` */
    public fun batch(replica: ReplicaId, seq: Long, plaintext: ByteArray): String =
        batchPrefix(replica) + seq.toString().padStart(SEQ_DIGITS, '0') + "-" + hash16(plaintext) + ".bin"

    private val REPLICA_FILE = Regex("replicas/([A-Za-z0-9_-]{1,64})\\.json")
    private val BATCH_FILE = Regex("batches/([A-Za-z0-9_-]{1,64})/(\\d{$SEQ_DIGITS})-([0-9a-f]{16})\\.bin")

    public fun parseReplicaFile(path: String): ReplicaId? =
        REPLICA_FILE.matchEntire(path)?.let { ReplicaId(it.groupValues[1]) }

    /** The sequence number in a batch path, or null if [path] is not a batch of [replica]. */
    public fun parseBatchSeq(replica: ReplicaId, path: String): Long? {
        val m = BATCH_FILE.matchEntire(path) ?: return null
        return if (m.groupValues[1] == replica.value) m.groupValues[2].toLong() else null
    }

    internal fun hashOf(path: String): String? = BATCH_FILE.matchEntire(path)?.groupValues?.get(3)

    internal fun hash16(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }

    private const val SEQ_DIGITS = 12
}
