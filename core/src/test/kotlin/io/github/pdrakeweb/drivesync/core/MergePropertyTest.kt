package io.github.pdrakeweb.drivesync.core

import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.MergedState
import io.github.pdrakeweb.drivesync.testing.TestVector
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/**
 * Invariant C-1 at the level of the merge rules: random change sets across all three merge
 * classes, applied in different orders, split differently, and delivered more than once.
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class MergePropertyTest {
    private val registry = MergeRegistry(
        1,
        listOf(
            TableSpec("fields", MergeClass.LWW_FIELDS),
            TableSpec("append", MergeClass.APPEND_ONLY),
            TableSpec("rows", MergeClass.LWW_ROW),
        ),
    )
    private val config = PropTestConfig(seed = 20261111, iterations = 2_000)

    /** A change set as real replicas could produce it: each (time, replica) stamps one write. */
    private fun changeSet(rnd: Random): List<Change> {
        val used = HashSet<Hlc>()
        val changes = ArrayList<Change>()
        repeat(rnd.nextInt(1, 40)) {
            val hlc = Hlc.of(rnd.nextLong(1, 30), rnd.nextInt(0, 3), ReplicaId("r${rnd.nextInt(3)}"))
            if (!used.add(hlc)) return@repeat
            val table = listOf("fields", "append", "rows")[rnd.nextInt(3)]
            val rowId = "row${rnd.nextInt(4)}"
            val value = Value.Text("v${changes.size}")
            when {
                rnd.nextInt(8) == 0 -> changes += Change.tombstone(table, rowId, hlc)
                table == "rows" -> listOf("a", "b", "c").shuffled(rnd).take(rnd.nextInt(1, 4))
                    .forEach { changes += Change(table, rowId, it, value, hlc) }
                else -> changes += Change(table, rowId, "c${rnd.nextInt(3)}", value, hlc)
            }
        }
        return changes
    }

    private fun merged(vararg deliveries: List<Change>): MergedState = runBlocking {
        val store = InMemoryLocalStore(ReplicaId("observer"), registry) { 0 }
        deliveries.forEach { store.apply(it, registry) }
        store.mergedState()
    }

    @Test
    fun `any order and any duplication converge to the same state`() = runBlocking {
        checkAll(config, Arb.long()) { seed ->
            val rnd = Random(seed)
            val changes = changeSet(rnd)
            val reference = merged(changes)
            val reordered = (changes + changes.filter { rnd.nextBoolean() }).shuffled(rnd)
            assertEquals(reference, merged(reordered), "seed $seed")
            assertEquals(reference, merged(changes.reversed()), "seed $seed")
        }
    }

    @Test
    fun `applying a change set twice changes nothing`() = runBlocking {
        checkAll(config, Arb.long()) { seed ->
            val changes = changeSet(Random(seed))
            val store = InMemoryLocalStore(ReplicaId("observer"), registry) { 0 }
            store.apply(changes, registry)
            val once = store.mergedState()
            val report = store.apply(changes.shuffled(Random(seed)), registry)
            assertEquals(once, store.mergedState(), "seed $seed")
            assertEquals(0, report.applied, "seed $seed")
        }
    }

    @Test
    fun `merging is associative - how changes are grouped into deliveries does not matter`() = runBlocking {
        checkAll(config, Arb.long()) { seed ->
            val rnd = Random(seed)
            val changes = changeSet(rnd)
            val cut1 = rnd.nextInt(changes.size + 1)
            val cut2 = rnd.nextInt(cut1, changes.size + 1)
            val a = changes.subList(0, cut1)
            val b = changes.subList(cut1, cut2)
            val c = changes.subList(cut2, changes.size)
            assertEquals(merged(a + b, c), merged(a, b + c), "seed $seed")
            assertEquals(merged(changes), merged(c, a, b), "seed $seed")
        }
    }

    @Test
    fun `a tombstoned row holds no data whatever else arrives`() = runBlocking {
        checkAll(config, Arb.long()) { seed ->
            val rnd = Random(seed)
            val changes = changeSet(rnd)
            val dead = changes.filter { it.isTombstone }.map { it.row }.toSet()
            val state = merged(changes.shuffled(rnd))
            assertEquals(dead, state.tombstones.keys, "seed $seed")
            assertTrue(state.cells.keys.none { it in dead }, "seed $seed")
        }
    }

    @Test
    fun `each merge class keeps exactly the writes its rule names`() = runBlocking {
        checkAll(config, Arb.long()) { seed ->
            val rnd = Random(seed)
            val changes = changeSet(rnd)
            val dead = changes.filter { it.isTombstone }.map { it.row }.toSet()
            val live = changes.filter { !it.isTombstone && it.row !in dead }
            val state = merged(changes.shuffled(rnd))
            val expected = HashMap<RowRef, Map<String, Cell>>()
            for ((row, writes) in live.groupBy { it.row }) {
                val byColumn = writes.groupBy { it.column!! }
                expected[row] = when (row.table) {
                    "fields" -> byColumn.mapValues { (_, w) -> w.maxBy { it.hlc }.let { Cell(it.value!!, it.hlc) } }
                    "append" -> byColumn.mapValues { (_, w) -> w.minBy { it.hlc }.let { Cell(it.value!!, it.hlc) } }
                    else -> {
                        val newest = writes.maxOf { it.hlc }
                        writes.filter { it.hlc == newest }.associate { it.column!! to Cell(it.value!!, it.hlc) }
                    }
                }
            }
            assertEquals(expected, state.cells, "seed $seed")
        }
    }

    @TestFactory
    fun `shared test vectors hold in every order`(): List<DynamicTest> {
        val vectors = TestVector.all()
        assertTrue(vectors.size >= 12)
        return vectors.map { vector ->
            DynamicTest.dynamicTest(vector.name) {
                val changes = vector.toChanges()
                assertEquals(emptyList<String>(), vector.check(changes))
                assertEquals(emptyList<String>(), vector.check(changes.reversed()))
                repeat(50) { i ->
                    val rnd = Random(i)
                    val order = (changes + changes.filter { rnd.nextBoolean() }).shuffled(rnd)
                    assertEquals(emptyList<String>(), vector.check(order), "shuffle $i")
                }
            }
        }
    }
}
