package spike

import com.tap.synk.meta.Meta
import com.tap.synk.relay.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import kotlin.system.measureNanoTime

private const val DAY = 24L * 60 * 60 * 1000

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SpikeTest {
    private val seeds = System.getProperty("s0.seeds", "500").toInt()
    private val report = File(System.getProperty("s0.report", "build/s0-measurements.txt"))

    private fun record(line: String) {
        println(line)
        report.parentFile.mkdirs()
        report.appendText(line + "\n")
    }

    private fun suite(c: Candidate, skew: Long, shared: Boolean): Map<Property, Int> {
        val tally = HashMap<Property, Int>()
        var firstDetail = ""
        var messages = 0L
        val nanos = measureNanoTime {
            for (seed in 0 until seeds.toLong()) {
                val o = runScenario(c, seed, skew, shared)
                messages += o.messages
                o.violations.forEach { tally.merge(it, 1, Int::plus) }
                if (o.violations.isNotEmpty() && firstDetail.isEmpty()) {
                    firstDetail = "seed $seed: ${o.violations} ${o.detail.take(300)}"
                }
            }
        }
        record(
            "${c.name} | row ids=${if (shared) "shared" else "unique"} | skew=${if (skew == 0L) "none" else "+/-3d"} | runs=$seeds | messages=$messages | " +
                "violations=${Property.entries.associateWith { tally[it] ?: 0 }} | ${nanos / 1_000_000} ms",
        )
        if (firstDetail.isNotEmpty()) record("  first failure: $firstDetail")
        return tally
    }

    @Test
    @Order(1)
    fun `properties without clock skew - own core`() {
        report.delete()
        for (shared in listOf(false, true)) assertEquals(emptyMap<Property, Int>(), suite(OwnCandidate, 0, shared))
    }

    @Test
    @Order(2)
    fun `properties without clock skew - synk, recorded not asserted`() {
        suite(SynkCandidate(fixedSchema = true), 0, false)
        suite(SynkCandidate(fixedSchema = true), 0, true)
        suite(SynkCandidate(fixedSchema = false), 0, false)
    }

    @Test
    @Order(3)
    fun `properties with clock skew of 3 days - own core`() {
        for (shared in listOf(false, true)) assertEquals(emptyMap<Property, Int>(), suite(OwnCandidate, 3 * DAY, shared))
    }

    /** What a device whose wall clock runs two days fast puts on the wire, built with Synk's public types. */
    private fun fromFastDevice(nowMs: Long): Message<SynkRow> {
        val hlc = (nowMs + 2 * DAY).toString().padStart(15, '0') + ":00000:fastdevice"
        return Message(
            SynkRow(NOTES, "n0", mapOf("c0" to "from-fast-device")),
            Meta(SynkRow::class.qualifiedName!!, listOf("c0", "c1", "c2", "_deleted", "_table", "_id").associateWith { hlc }),
        )
    }

    @Test
    @Order(4)
    fun `synk - a later edit loses to a peer whose clock is ahead and replicas diverge`() {
        val key = RowKey(NOTES, "n0")
        val a = SynkReplica("a")
        val b = SynkReplica("b")
        try {
            val fast = Msg("fastdevice", fromFastDevice(System.currentTimeMillis()), 0)
            a.receive(fast)
            b.receive(fast)
            // Causally after the fast device's write: replica a has seen it.
            val edit = a.write(key, mapOf("c0" to "edited-after-seeing-it"))
            b.receive(edit)
            // Both replicas have now seen exactly the same two changes.
            record("synk skew check | a=${a.visible()[key]} | b=${b.visible()[key]}")
            assertEquals("edited-after-seeing-it", a.visible()[key]!!["c0"])
            assertEquals("from-fast-device", b.visible()[key]!!["c0"])
            assertNotEquals(a.visible(), b.visible(), "expected the documented divergence")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    @Order(5)
    fun `own core - same scenario converges on the later edit`() {
        val key = RowKey(NOTES, "n0")
        val now = System.currentTimeMillis()
        val fastDevice = OwnReplica("fastdevice") { now + 2 * DAY }
        val a = OwnReplica("a") { now }
        val b = OwnReplica("b") { now }
        val fast = fastDevice.write(key, mapOf("c0" to "from-fast-device"))
        a.receive(fast)
        b.receive(fast)
        b.receive(a.write(key, mapOf("c0" to "edited-after-seeing-it")))
        record("own skew check | a=${a.visible()[key]} | b=${b.visible()[key]}")
        assertEquals(a.visible(), b.visible())
        assertEquals("edited-after-seeing-it", b.visible()[key]!!["c0"])
    }

    @Test
    @Order(6)
    fun `cost of one single-column edit on a ten-column row`() {
        for (c in listOf(OwnCandidate, SynkCandidate(fixedSchema = true))) {
            val r = c.replica("w") { System.currentTimeMillis() }
            val peer = c.replica("p") { System.currentTimeMillis() }
            val key = RowKey(NOTES, "3f2c9a52-8f0e-4d0e-9a51-6e1f6c1f0a11")
            val create = r.write(key, (0 until 10).associate { "column$it" to "value-of-reasonable-length-$it" })
            peer.receive(create)
            val edit = r.write(key, mapOf("column3" to "changed"))
            repeat(5_000) { peer.receive(r.write(key, mapOf("column3" to "w$it"))) } // warm-up
            val n = 20_000
            val nanos = measureNanoTime { repeat(n) { peer.receive(r.write(key, mapOf("column3" to "v$it"))) } }
            record(
                "${c.name} | create=${create.wireBytes} B | one-column edit=${edit.wireBytes} B | " +
                    "emit+merge=${nanos / n} ns/op",
            )
            r.close()
            peer.close()
        }
    }
}
