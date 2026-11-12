package spike

import java.util.EnumSet
import kotlin.random.Random

/** Candidate-neutral model: a replica takes local writes and deletes, emits opaque messages, receives them. */
data class RowKey(val table: String, val id: String)

class Msg(val origin: String, val payload: Any, val wireBytes: Int)

interface Replica {
    val id: String
    fun write(key: RowKey, fields: Map<String, String>): Msg
    fun delete(key: RowKey): Msg
    fun receive(msg: Msg)
    fun visible(): Map<RowKey, Map<String, String>>
    fun close() {}
}

interface Candidate {
    val name: String
    fun replica(id: String, wallClock: () -> Long): Replica
}

enum class Property {
    CONVERGENCE, IDEMPOTENCY, COMMUTATIVITY, NO_RESURRECTION, APPEND_ONLY_KEPT, CAUSAL_LAST_WINS, CRASHED
}

class Outcome(val violations: Set<Property>, val detail: String, val messages: Int)

const val NOTES = "notes" // LWW_FIELDS
const val LOG = "log" // APPEND_ONLY

/**
 * One seeded run: 2-4 replicas, random local edits, random partial, duplicated and reordered
 * delivery, then a full exchange. Checks the merge properties from ARCHITECTURE.md section 5.
 * [maxSkewMs] gives each replica a fixed wall-clock offset in [-max, +max].
 * [sharedRowIds] true: any replica may write any of five well-known row ids, so the same row can be
 * created independently on two devices (fixed-id rows such as settings or seeded defaults).
 * false: a row is only ever created on one replica (random ids); others edit it after receiving it.
 */
fun runScenario(c: Candidate, seed: Long, maxSkewMs: Long, sharedRowIds: Boolean): Outcome {
    val rnd = Random(seed)
    val n = rnd.nextInt(2, 5)
    var now = 1_800_000_000_000L
    val skews = List(n) { if (maxSkewMs == 0L) 0L else rnd.nextLong(-maxSkewMs, maxSkewMs + 1) }
    val replicas = List(n) { i -> c.replica("r$i") { now + skews[i] } }
    val extra = ArrayList<Replica>()
    val log = ArrayList<Msg>()
    val seen = List(n) { HashSet<Int>() }
    val deleted = HashSet<RowKey>()
    val appended = HashMap<RowKey, Map<String, String>>()

    class W(val msg: Int, val value: String, val seenBefore: Set<Int>)

    val writes = LinkedHashMap<Pair<RowKey, String>, MutableList<W>>()
    var appendCounter = 0
    val violations = EnumSet.noneOf(Property::class.java)
    val detail = StringBuilder()
    try {
        repeat(rnd.nextInt(20, 61)) { step ->
            now += rnd.nextLong(0, 5000)
            val r = rnd.nextInt(n)
            when (rnd.nextInt(100)) {
                in 0 until 40 -> {
                    val known = replicas[r].visible().keys.filter { it.table == NOTES }.sortedBy { it.id }
                    val key = when {
                        sharedRowIds -> RowKey(NOTES, "n${rnd.nextInt(5)}")
                        known.isNotEmpty() && rnd.nextBoolean() -> known[rnd.nextInt(known.size)]
                        else -> RowKey(NOTES, "r$r-n${appendCounter++}")
                    }
                    val cols = listOf("c0", "c1", "c2").shuffled(rnd).take(rnd.nextInt(1, 3))
                    val fields = cols.associateWith { "s$step" }
                    val before = seen[r].toSet()
                    val idx = log.size
                    log += replicas[r].write(key, fields)
                    seen[r] += idx
                    for (col in cols) writes.getOrPut(key to col) { ArrayList() } += W(idx, "s$step", before)
                }
                in 40 until 55 -> {
                    val key = RowKey(LOG, "r$r-${appendCounter++}")
                    val fields = mapOf("body" to "s$step")
                    appended[key] = fields
                    seen[r] += log.size
                    log += replicas[r].write(key, fields)
                }
                in 55 until 65 -> {
                    val keys = replicas[r].visible().keys.sortedBy { it.table + "/" + it.id }
                    if (keys.isNotEmpty()) {
                        val key = keys[rnd.nextInt(keys.size)]
                        deleted += key
                        seen[r] += log.size
                        log += replicas[r].delete(key)
                    }
                }
                else -> {
                    val from = log.indices.filter { log[it].origin != replicas[r].id }
                    if (from.isNotEmpty()) {
                        repeat(rnd.nextInt(1, 7)) {
                            val i = from[rnd.nextInt(from.size)]
                            replicas[r].receive(log[i])
                            seen[r] += i
                        }
                    }
                }
            }
        }

        fun deliverAll(target: Replica) {
            val order = (log.indices + List(log.size / 3) { rnd.nextInt(log.size) }).shuffled(rnd)
            for (i in order) if (log[i].origin != target.id) target.receive(log[i])
        }
        replicas.forEach { deliverAll(it) }
        val states = replicas.map { it.visible() }
        if (states.distinct().size > 1) {
            violations += Property.CONVERGENCE
            detail.append("states differ: $states\n")
        }

        replicas.forEach { deliverAll(it) }
        if (replicas.map { it.visible() } != states) violations += Property.IDEMPOTENCY

        val o1 = c.replica("o1") { now }.also { extra += it }
        val o2 = c.replica("o2") { now }.also { extra += it }
        deliverAll(o1)
        deliverAll(o2)
        if (o1.visible() != o2.visible() || o1.visible() != states[0]) violations += Property.COMMUTATIVITY

        val all = states + o1.visible() + o2.visible()
        if (all.any { s -> deleted.any { it in s } }) violations += Property.NO_RESURRECTION
        for ((key, fields) in appended) {
            if (key !in deleted && all.any { it[key] != fields }) violations += Property.APPEND_ONLY_KEPT
        }
        for ((kc, ws) in writes) {
            val (key, col) = kc
            if (key in deleted) continue
            val last = ws.last()
            // The last writer had already seen every other write to this field, so it is causally
            // last and must win.
            val causallyLast = last.seenBefore.containsAll(ws.dropLast(1).map { it.msg })
            if (causallyLast && all.any { it[key]?.get(col) != last.value }) {
                violations += Property.CAUSAL_LAST_WINS
                detail.append("field $kc expected ${last.value}\n")
            }
        }
    } catch (e: Throwable) {
        violations += Property.CRASHED
        detail.append(e.toString())
    } finally {
        (replicas + extra).forEach { runCatching { it.close() } }
    }
    return Outcome(violations, detail.toString(), log.size)
}
