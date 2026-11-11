package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.Change
import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.ParentRef
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.RowStatus
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/**
 * The shared merge test vectors: JSON files under `drivesync-vectors/` that state the merge rules
 * by example (input changes, expected final state). An implementation of the pattern in another
 * language can run the same files. The format is described in `drivesync-vectors/README.md`.
 */
@Serializable
public data class TestVector(
    val name: String,
    val description: String,
    val tables: List<Table>,
    val changes: List<VectorChange>,
    val expected: Expected,
) {
    @Serializable
    public data class Table(val name: String, val mergeClass: MergeClass, val parents: List<Parent> = emptyList())

    @Serializable
    public data class Parent(val column: String, val table: String)

    @Serializable
    public data class VectorChange(
        val table: String,
        val row: String,
        val column: String? = null,
        val value: JsonElement? = null,
        val delete: Boolean = false,
        val hlc: String,
    )

    @Serializable
    public data class Expected(
        /** table -> row id -> column -> value, for rows the app would see. */
        val rows: Map<String, Map<String, Map<String, JsonElement>>> = emptyMap(),
        val deleted: List<String> = emptyList(),
        val held: List<String> = emptyList(),
        val orphaned: List<String> = emptyList(),
    )

    public fun registry(): MergeRegistry = MergeRegistry(
        schemaVersion = 1,
        tables = tables.map { t -> TableSpec(t.name, t.mergeClass, t.parents.map { ParentRef(it.column, it.table) }) },
    )

    public fun toChanges(): List<Change> = changes.map { c ->
        val hlc = Hlc.parse(c.hlc)
        if (c.delete) Change.tombstone(c.table, c.row, hlc) else Change(c.table, c.row, c.column!!, toValue(c.value), hlc)
    }

    /**
     * Applies [order] (any permutation of [toChanges], duplicates allowed) to a fresh store and
     * returns every difference from [expected]. Empty means the vector holds.
     */
    public fun check(order: List<Change> = toChanges()): List<String> = runBlocking {
        val registry = registry()
        val store = InMemoryLocalStore(ReplicaId("checker"), registry) { 0 }
        val report = store.apply(order, registry)
        val problems = ArrayList<String>()
        for (table in tables) {
            val want = expected.rows[table.name].orEmpty().mapValues { (_, cols) -> cols.mapValues { toValue(it.value) } }
            val got = store.rows(table.name)
            if (want != got) problems += "$name: table ${table.name} expected $want but was $got"
        }
        for (ref in expected.deleted.map(::rowRef)) {
            if (!store.isDeleted(ref)) problems += "$name: expected $ref to be deleted"
        }
        if (report.held.map { it.toString() } != expected.held.sorted()) {
            problems += "$name: held expected ${expected.held} but was ${report.held}"
        }
        if (report.orphaned.map { it.toString() } != expected.orphaned.sorted()) {
            problems += "$name: orphaned expected ${expected.orphaned} but was ${report.orphaned}"
        }
        for (ref in (expected.held + expected.orphaned).map(::rowRef)) {
            if (store.status(ref) == RowStatus.ACTIVE) problems += "$name: $ref should not be active"
        }
        problems
    }

    private fun rowRef(text: String): RowRef = text.split('/', limit = 2).let { RowRef(it[0], it[1]) }

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val ROOT = "/drivesync-vectors/"

        /** Every vector listed in `drivesync-vectors/index.txt`. */
        public fun all(): List<TestVector> = resource("index.txt").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { json.decodeFromString(serializer(), resource(it)) }

        private fun resource(name: String): String =
            requireNotNull(TestVector::class.java.getResourceAsStream(ROOT + name)) { "missing vector resource $name" }
                .use { it.readBytes().decodeToString() }

        internal fun toValue(element: JsonElement?): Value {
            if (element == null || element is JsonNull) return Value.Null
            val (kind, v) = (element as JsonObject).entries.single()
            return when (kind) {
                "text" -> Value.Text(v.jsonPrimitive.content)
                "int" -> Value.Integer(v.jsonPrimitive.long)
                "real" -> Value.Real(v.jsonPrimitive.double)
                "bytes" -> Value.Bytes(Base64.getDecoder().decode(v.jsonPrimitive.content))
                else -> throw IllegalArgumentException("unknown value kind $kind")
            }
        }
    }
}
