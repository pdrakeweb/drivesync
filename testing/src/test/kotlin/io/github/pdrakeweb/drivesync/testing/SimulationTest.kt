package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.LocalStore
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class SimulationTest {
    private val runs = System.getProperty("drivesync.simRuns", "500").toInt()

    private fun campaign(name: String, options: SimulationOptions) {
        var steps = 0L
        var objects = 0L
        var crashes = 0
        var failedCycles = 0
        val failures = ArrayList<String>()
        val started = System.nanoTime()
        for (seed in 0 until runs.toLong()) {
            val result = Simulator.run(seed, options)
            steps += result.steps
            objects += result.objectsStored
            crashes += result.crashes
            failedCycles += result.failedCycles
            if (result.violations.isNotEmpty() && failures.size < 5) {
                failures += "seed $seed: ${result.violations.take(3)}"
            }
        }
        println(
            "simulation[$name] runs=$runs steps=$steps objects=$objects crashes=$crashes " +
                "failedCycles=$failedCycles violations=${failures.size} " +
                "${(System.nanoTime() - started) / 1_000_000} ms",
        )
        assertEquals(emptyList<String>(), failures)
        if (options.faults) assertTrue(crashes > 0 && failedCycles > 0, "the fault injection must actually fire")
    }

    @Test
    fun `replicas converge under faults, crashes, lag, partitions and clock skew`() =
        campaign("faults+skew", SimulationOptions())

    @Test
    fun `replicas converge without faults or skew`() =
        campaign("clean", SimulationOptions(maxSkewMillis = 0, faults = false))

    @Test
    fun `a run is fully determined by its seed`() {
        val one = Simulator.run(42)
        val two = Simulator.run(42)
        assertEquals(listOf(one.steps, one.objectsStored, one.crashes, one.failedCycles),
            listOf(two.steps, two.objectsStored, two.crashes, two.failedCycles))
    }
}

class InMemoryBlobStoreContractTest : BlobStoreContract() {
    override fun newStore(): BlobStore = InMemoryBlobStore(pageSize = 50)
}

class InMemoryLocalStoreContractTest : LocalStoreContract() {
    private var wall = 1_000L
    override fun newStore(): LocalStore = InMemoryLocalStore(ReplicaId("me"), registry) { wall }
    override fun localWrite(store: LocalStore, table: String, rowId: String, columns: Map<String, Value>) {
        (store as InMemoryLocalStore).write(table, rowId, columns)
    }

    @TestFactory
    fun contract(): List<DynamicTest> = checks.map { (name, check) -> DynamicTest.dynamicTest(name) { check() } }
}
