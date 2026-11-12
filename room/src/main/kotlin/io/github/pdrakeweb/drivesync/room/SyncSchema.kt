package io.github.pdrakeweb.drivesync.room

import io.github.pdrakeweb.drivesync.core.ChangeValidator
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.ParentRef
import io.github.pdrakeweb.drivesync.core.TableSpec

/**
 * One SQLite table that syncs.
 *
 * The table needs a single-column primary key whose values are unique across devices (a random
 * UUID, not an auto-increment integer: two devices would both create row 1), and at least one
 * other column.
 */
public class SyncTable(
    public val name: String,
    public val mergeClass: MergeClass,
    public val primaryKey: String = "id",
    public val parents: List<ParentRef> = emptyList(),
) {
    init {
        requireIdentifier(name)
        requireIdentifier(primaryKey)
        parents.forEach { requireIdentifier(it.column) }
    }
}

/** Which tables sync and how. [schemaVersion] is the app's database version. */
public class SyncSchema(
    public val schemaVersion: Int,
    public val tables: List<SyncTable>,
    validator: ChangeValidator? = null,
) {
    public val registry: MergeRegistry =
        MergeRegistry(schemaVersion, tables.map { TableSpec(it.name, it.mergeClass, it.parents) }, validator)

    internal fun table(name: String): SyncTable? = tables.firstOrNull { it.name == name }

    /** Parents before children, so rows can be written in an order foreign keys accept. */
    internal val parentsFirst: List<SyncTable> by lazy {
        val ordered = LinkedHashSet<SyncTable>()
        fun visit(table: SyncTable, path: Set<String>) {
            if (table in ordered || table.name in path) return
            table.parents.mapNotNull { table(it.table) }.forEach { visit(it, path + table.name) }
            ordered += table
        }
        tables.forEach { visit(it, emptySet()) }
        ordered.toList()
    }
}

public class SyncSchemaBuilder internal constructor(private val schemaVersion: Int) {
    private val tables = ArrayList<SyncTable>()

    /** Called for every incoming change; see [ChangeValidator]. */
    public var validator: ChangeValidator? = null

    public fun table(
        name: String,
        mergeClass: MergeClass,
        primaryKey: String = "id",
        parents: TableBuilder.() -> Unit = {},
    ) {
        tables += SyncTable(name, mergeClass, primaryKey, TableBuilder().apply(parents).parents)
    }

    public class TableBuilder internal constructor() {
        internal val parents = ArrayList<ParentRef>()

        /** [column] of this table holds the primary key of a row in [table]. */
        public fun parent(column: String, table: String) {
            parents += ParentRef(column, table)
        }
    }

    internal fun build() = SyncSchema(schemaVersion, tables, validator)
}

/**
 * ```
 * val schema = syncSchema(schemaVersion = 3) {
 *     table("notes", MergeClass.LWW_FIELDS)
 *     table("items", MergeClass.LWW_FIELDS) { parent("note_id", "notes") }
 *     table("history", MergeClass.APPEND_ONLY)
 * }
 * ```
 */
public fun syncSchema(schemaVersion: Int, block: SyncSchemaBuilder.() -> Unit): SyncSchema =
    SyncSchemaBuilder(schemaVersion).apply(block).build()

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun requireIdentifier(name: String) {
    require(IDENTIFIER.matches(name)) { "not a plain SQL identifier: $name" }
    require(!name.startsWith("_ds_")) { "the _ds_ prefix is reserved: $name" }
}
