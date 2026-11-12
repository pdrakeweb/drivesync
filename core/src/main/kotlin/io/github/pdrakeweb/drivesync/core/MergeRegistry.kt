package io.github.pdrakeweb.drivesync.core

/** How concurrent writes to one table are resolved (DESIGN.md section 5). */
public enum class MergeClass {
    /** Each column independently takes the write with the highest timestamp. */
    LWW_FIELDS,

    /** Rows are inserted once and never edited; the only later change is a tombstone. */
    APPEND_ONLY,

    /** The whole row is replaced by the write with the highest timestamp. */
    LWW_ROW,
}

/** [column] of a child table holds the row id of a row in [table]. A null value means no parent. */
public data class ParentRef(val column: String, val table: String)

public data class TableSpec(
    val name: String,
    val mergeClass: MergeClass,
    val parents: List<ParentRef> = emptyList(),
)

/**
 * App-supplied check on incoming changes. Return null to accept, or a reason to reject.
 * Rejected changes are quarantined and reported, never dropped, and tried again on every later
 * apply. Must be deterministic: replicas
 * running different validators can hold different data.
 *
 * Local writes are not validated: the app is trusted with its own data. A validator must therefore
 * accept everything the same app version can write, or a change kept on the device that made it
 * would be quarantined everywhere else.
 */
public fun interface ChangeValidator {
    public fun validate(change: Change): String?
}

/** The app's sync schema: which tables sync, how each merges, and the schema version it writes. */
public class MergeRegistry(
    public val schemaVersion: Int,
    tables: List<TableSpec>,
    public val validator: ChangeValidator? = null,
) {
    private val byName: Map<String, TableSpec> = tables.associateBy { it.name }

    init {
        require(byName.size == tables.size) { "duplicate table name" }
        for (t in tables) for (p in t.parents) {
            require(p.table in byName) { "table ${t.name} names unknown parent table ${p.table}" }
        }
    }

    public val tables: Collection<TableSpec> get() = byName.values

    public fun table(name: String): TableSpec? = byName[name]
}
