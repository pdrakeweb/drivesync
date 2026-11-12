@file:OptIn(ExperimentalSerializationApi::class)

package io.github.pdrakeweb.drivesync.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HlcAndWireTest {
    private val a = ReplicaId("a")
    private val b = ReplicaId("b")

    @Test
    fun `hlc orders by wall then counter then replica`() {
        val ordered = listOf(Hlc.of(1, 0, b), Hlc.of(1, 1, a), Hlc.of(2, 0, a), Hlc.of(2, 0, b))
        assertEquals(ordered, ordered.shuffled().sorted())
        assertEquals(Hlc.of(1234, 5, a), Hlc.parse("1234:5:a"))
        assertEquals("1234:5:a", Hlc.of(1234, 5, a).toString())
    }

    @Test
    fun `clock ticks are strictly increasing even when the wall clock stands still or goes back`() {
        var wall = 1_000L
        val clock = HlcClock(a, { wall })
        val first = clock.tick()
        val second = clock.tick()
        wall = 500
        val third = clock.tick()
        wall = 2_000
        val fourth = clock.tick()
        assertTrue(first < second && second < third && third < fourth)
        assertEquals(1_000, third.wallMillis)
        assertEquals(2, third.counter)
        assertEquals(2_000, fourth.wallMillis)
        assertEquals(0, fourth.counter)
    }

    @Test
    fun `clock absorbs a remote timestamp from far in the future`() {
        val clock = HlcClock(a, { 1_000 })
        val remote = Hlc.of(1_000 + 3L * 24 * 60 * 60 * 1000, 9, b)
        clock.observe(remote)
        val next = clock.tick()
        assertTrue(next > remote)
        assertEquals(remote.wallMillis, next.wallMillis)
        assertEquals(10, next.counter)
    }

    @Test
    fun `counter overflow carries into the wall time instead of failing`() {
        val clock = HlcClock(a, { 1_000 }, last = Hlc.of(1_000, 0xFFFF, a).time)
        val next = clock.tick()
        assertEquals(1_001, next.wallMillis)
        assertEquals(0, next.counter)
    }

    @Test
    fun `clock resumes from persisted state`() {
        val before = HlcClock(a, { 5_000 })
        val issued = before.tick()
        val after = HlcClock(a, { 1_000 }, last = before.last)
        assertTrue(after.tick() > issued)
    }

    @Test
    fun `replica ids are restricted to path-safe characters`() {
        assertThrows(IllegalArgumentException::class.java) { ReplicaId("../x") }
        assertThrows(IllegalArgumentException::class.java) { ReplicaId("") }
        ReplicaId("3f2c9a52-8f0e_4d0e")
    }

    private fun batch(seq: Long = 7) = Batch.of(
        a, seq, 3,
        listOf(
            Change("notes", "n1", "title", Value.Text("hello"), Hlc.of(10, 0, a)),
            Change("notes", "n1", "n", Value.Integer(-4), Hlc.of(10, 1, a)),
            Change("notes", "n1", "r", Value.Real(2.5), Hlc.of(10, 2, a)),
            Change("notes", "n1", "b", Value.Bytes(byteArrayOf(0, 1, -1)), Hlc.of(10, 3, a)),
            Change("notes", "n1", "z", Value.Null, Hlc.of(10, 4, a)),
            Change.tombstone("notes", "n2", Hlc.of(11, 0, a)),
        ),
    )

    @Test
    fun `batch round-trips with every value type and a correct header`() {
        val original = batch()
        val decoded = WireCodec.decodeBatch("p", WireCodec.encodeBatch(original))
        assertEquals(original, decoded)
        assertEquals(Hlc.of(10, 0, a), decoded.header.hlcFrom)
        assertEquals(Hlc.of(11, 0, a), decoded.header.hlcTo)
        assertEquals(6, decoded.header.count)
        assertEquals(WIRE_FORMAT, decoded.header.format)
    }

    @Test
    fun `batch encoding and naming are deterministic`() {
        val one = WireCodec.encodeBatch(batch())
        val two = WireCodec.encodeBatch(batch())
        assertArrayEquals(one, two)
        val path = Paths.batch(a, 7, "0123456789abcdef")
        assertThrows(IllegalArgumentException::class.java) { Paths.batch(a, 7, "not-a-tag") }
        assertTrue(Regex("batches/a/000000000007-[0-9a-f]{16}\\.bin").matches(path), path)
        assertEquals(7L, Paths.parseBatchSeq(a, path))
        assertNull(Paths.parseBatchSeq(b, path))
        assertNotEquals(path, Paths.batch(a, 7, "fedcba9876543210"))
    }

    @Serializable
    private data class FutureHeader(val format: Int, val replicaId: ReplicaId, val seq: Long, val somethingNew: String)

    @Serializable
    private data class FutureBatch(val header: FutureHeader, val changes: List<Change>, val extra: Int)

    @Test
    fun `a newer format is rejected with a typed error, garbage as corrupt`() {
        val future = Cbor.encodeToByteArray(FutureBatch.serializer(), FutureBatch(FutureHeader(2, a, 1, "x"), emptyList(), 1))
        val newer = assertThrows(SyncException::class.java) { WireCodec.decodeBatch("some/path", future) }
        assertEquals(SyncError.UnsupportedFormat("some/path", 2), newer.error)

        val garbage = assertThrows(SyncException::class.java) { WireCodec.decodeBatch("some/path", byteArrayOf(1, 2, 3)) }
        assertEquals(SyncError.Corrupt("some/path"), garbage.error)
    }

    @Test
    fun `replica file round-trips, ignores unknown fields, rejects a newer format`() {
        val file = ReplicaFile(replicaId = a, label = "Pixel", platform = "android", lastSeen = 5, lastSeq = 9,
            cursors = mapOf("b" to 4L), schemaVersion = 2)
        assertEquals(file, WireCodec.decodeReplicaFile("p", WireCodec.encodeReplicaFile(file)))

        val withExtra = """{"format":1,"replicaId":"a","label":"L","platform":"p","lastSeen":1,"lastSeq":2,
            "cursors":{},"schemaVersion":1,"addedLater":true}""".encodeToByteArray()
        assertEquals(2, WireCodec.decodeReplicaFile("p", withExtra).lastSeq)

        val newer = """{"format":9,"somethingElse":1}""".encodeToByteArray()
        val e = assertThrows(SyncException::class.java) { WireCodec.decodeReplicaFile("p", newer) }
        assertEquals(SyncError.UnsupportedFormat("p", 9), e.error)
    }
}
