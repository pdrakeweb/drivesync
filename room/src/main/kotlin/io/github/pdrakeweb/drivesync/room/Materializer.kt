package io.github.pdrakeweb.drivesync.room

import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.pdrakeweb.drivesync.core.Cell
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeState
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.RowStatus
import io.github.pdrakeweb.drivesync.core.RowStatusResolver

/**
 * Writes merged state into the app's own tables. The sync metadata (`_ds_cells`) always holds the
 * full merged state; an app table holds only rows that can be shown: not deleted, and not waiting
 * for or bereft of a parent. Runs with capture triggers silenced, inside the caller's transaction.
 */
internal class Materializer(private val db: SupportSQLiteDatabase, private val schema: SyncSchema) {
    private val state = SqlMergeState(db)
    private val resolver = RowStatusResolver(schema.registry, state)
    private val columns = HashMap<String, List<String>>()

    private fun columnsOf(table: SyncTable) = columns.getOrPut(table.name) { Sql.columns(db, table) }

    /** Brings the app tables in line for [touched] rows and every row that depends on them. */
    fun materialize(touched: Set<RowRef>) {
        val affected = withDependents(touched)
        val order = schema.parentsFirst.map { it.name }
        val byTable = affected.groupBy { it.table }
        silenced {
            // Children leave before their parents, and parents arrive before their children, so
            // foreign keys hold at every step.
            val showable = HashSet<RowRef>()
            for (name in order.reversed()) {
                val table = schema.table(name) ?: continue
                for (row in byTable[name].orEmpty()) {
                    val status = if (state.tombstone(row) != null) null else resolver.status(row)
                    if (status == RowStatus.ACTIVE) showable += row else remove(table, row, status)
                }
            }
            for (name in order) {
                val table = schema.table(name) ?: continue
                for (row in byTable[name].orEmpty()) if (row in showable) upsert(table, row)
            }
        }
    }

    /** Rewrites every row of [table] from merged state; used after a migration changed its columns. */
    fun rematerialize(table: SyncTable) {
        val rows = Sql.strings(db, "SELECT DISTINCT row_id FROM _ds_cells WHERE tbl = ?", arrayOf<Any?>(table.name))
        materialize(rows.map { RowRef(table.name, it) }.toSet())
    }

    private fun withDependents(touched: Set<RowRef>): Set<RowRef> {
        val all = LinkedHashSet(touched)
        val queue = ArrayDeque(touched)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in schema.tables) for (ref in child.parents) {
                if (ref.table != parent.table) continue
                val rows = Sql.strings(
                    db,
                    "SELECT row_id FROM _ds_cells WHERE tbl = ? AND col = ? AND CAST(value AS TEXT) = ?",
                    arrayOf<Any?>(child.name, ref.column, parent.rowId),
                )
                for (rowId in rows) RowRef(child.name, rowId).let { if (all.add(it)) queue += it }
            }
        }
        return all
    }

    private fun remove(table: SyncTable, row: RowRef, status: RowStatus?) {
        db.execSQL("DELETE FROM \"${table.name}\" WHERE \"${table.primaryKey}\" = ?", arrayOf<Any?>(row.rowId))
        setState(row, status?.name)
    }

    private fun upsert(table: SyncTable, row: RowRef) {
        val cells = state.cells(row)
        val known = columnsOf(table)
        // A whole-row table shows exactly the merged columns; a field-merged one leaves columns
        // without a merged value alone.
        val write: List<Pair<String, Cell?>> =
            if (table.mergeClass == MergeClass.LWW_FIELDS) known.filter { it in cells }.map { it to cells[it] }
            else known.map { it to cells[it] }
        try {
            val exists = Sql.long(db, "SELECT 1 FROM \"${table.name}\" WHERE \"${table.primaryKey}\" = ?", arrayOf<Any?>(row.rowId)) != null
            val sql = if (exists) {
                if (write.isEmpty()) return setState(row, null)
                "UPDATE \"${table.name}\" SET " + write.joinToString { "\"${it.first}\" = ?" } + " WHERE \"${table.primaryKey}\" = ?"
            } else {
                "INSERT INTO \"${table.name}\"(" + (write.map { "\"${it.first}\"" } + "\"${table.primaryKey}\"").joinToString() +
                    ") VALUES(" + List(write.size + 1) { "?" }.joinToString() + ")"
            }
            db.compileStatement(sql).use { statement ->
                write.forEachIndexed { i, (_, cell) -> Sql.bind(statement, i + 1, cell?.value) }
                statement.bindString(write.size + 1, row.rowId)
                if (exists) statement.executeUpdateDelete() else statement.executeInsert()
            }
            setState(row, null)
        } catch (e: SQLiteConstraintException) {
            // Not all of the row has arrived yet (a NOT NULL column is still missing), or the app's
            // own constraints refuse it. It stays in merged state and is reported as held.
            setState(row, RowStatus.HELD.name)
        }
    }

    private fun setState(row: RowRef, status: String?) {
        if (status == null) {
            db.execSQL("DELETE FROM _ds_rowstate WHERE tbl = ? AND row_id = ?", arrayOf<Any?>(row.table, row.rowId))
        } else {
            db.execSQL("INSERT OR REPLACE INTO _ds_rowstate(tbl, row_id, status) VALUES(?, ?, ?)", arrayOf<Any?>(row.table, row.rowId, status))
        }
    }

    private inline fun silenced(block: () -> Unit) {
        db.execSQL("UPDATE _ds_meta SET value = 1 WHERE key = 'applying'")
        try {
            block()
        } finally {
            db.execSQL("UPDATE _ds_meta SET value = 0 WHERE key = 'applying'")
        }
    }
}

/** [MergeState] over the `_ds_` tables. Records which rows a merge touched. */
internal class SqlMergeState(private val db: SupportSQLiteDatabase) : MergeState {
    val touched = LinkedHashSet<RowRef>()

    override fun observe(hlc: io.github.pdrakeweb.drivesync.core.Hlc) {
        db.execSQL("UPDATE _ds_meta SET value = max(value, ?) WHERE key = 'clock'", arrayOf<Any?>(hlc.time))
    }

    override fun tombstone(row: RowRef): io.github.pdrakeweb.drivesync.core.Hlc? =
        Sql.query(db, "SELECT time, replica FROM _ds_tombstones WHERE tbl = ? AND row_id = ?", arrayOf<Any?>(row.table, row.rowId)) { c ->
            if (c.moveToFirst()) hlc(c.getLong(0), c.getString(1)) else null
        }

    override fun putTombstone(row: RowRef, hlc: io.github.pdrakeweb.drivesync.core.Hlc) {
        db.execSQL(
            "INSERT OR REPLACE INTO _ds_tombstones(tbl, row_id, time, replica) VALUES(?, ?, ?, ?)",
            arrayOf<Any?>(row.table, row.rowId, hlc.time, hlc.replica.value),
        )
        db.execSQL("DELETE FROM _ds_cells WHERE tbl = ? AND row_id = ?", arrayOf<Any?>(row.table, row.rowId))
        touched += row
    }

    override fun cells(row: RowRef): Map<String, Cell> =
        Sql.query(
            db,
            "SELECT col, value, time, replica, prev_time, prev_replica FROM _ds_cells WHERE tbl = ? AND row_id = ?",
            arrayOf<Any?>(row.table, row.rowId),
        ) { c ->
            val out = LinkedHashMap<String, Cell>()
            while (c.moveToNext()) {
                val prev = if (c.isNull(4)) null else hlc(c.getLong(4), c.getString(5))
                out[c.getString(0)] = Cell(Sql.value(c, 1), hlc(c.getLong(2), c.getString(3)), prev)
            }
            out
        }

    override fun putCell(row: RowRef, column: String, cell: Cell) {
        db.compileStatement(
            "INSERT OR REPLACE INTO _ds_cells(tbl, row_id, col, value, time, replica, prev_time, prev_replica) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { s ->
            val prev = cell.prev
            if (prev == null) {
                s.bindNull(7)
                s.bindNull(8)
            } else {
                s.bindLong(7, prev.time)
                s.bindString(8, prev.replica.value)
            }
            s.bindString(1, row.table)
            s.bindString(2, row.rowId)
            s.bindString(3, column)
            Sql.bind(s, 4, cell.value)
            s.bindLong(5, cell.hlc.time)
            s.bindString(6, cell.hlc.replica.value)
            s.executeInsert()
        }
        touched += row
    }

    override fun clearCells(row: RowRef) {
        db.execSQL("DELETE FROM _ds_cells WHERE tbl = ? AND row_id = ?", arrayOf<Any?>(row.table, row.rowId))
        touched += row
    }

    override fun quarantined(): Collection<io.github.pdrakeweb.drivesync.core.Quarantined> =
        Sql.query(db, "SELECT change, reason FROM _ds_quarantine ORDER BY rowid") { c ->
            val out = ArrayList<io.github.pdrakeweb.drivesync.core.Quarantined>()
            while (c.moveToNext()) out += ChangeCodec.quarantined(c.getBlob(0), if (c.isNull(1)) null else c.getString(1))
            out
        }

    override fun quarantine(item: io.github.pdrakeweb.drivesync.core.Quarantined) {
        db.execSQL(
            "INSERT OR REPLACE INTO _ds_quarantine(change, reason, tbl) VALUES(?, ?, ?)",
            arrayOf<Any?>(ChangeCodec.encode(item.change), ChangeCodec.reason(item.reason), item.change.table),
        )
    }

    override fun recordConflict(conflict: io.github.pdrakeweb.drivesync.core.Conflict): Boolean =
        db.compileStatement(
            "INSERT OR IGNORE INTO _ds_conflicts(tbl, row_id, col, lost_time, lost_replica, lost_value, " +
                "kept_time, kept_replica, kept_value) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { s ->
            s.bindString(1, conflict.row.table)
            s.bindString(2, conflict.row.rowId)
            s.bindString(3, conflict.column)
            s.bindLong(4, conflict.lost.hlc.time)
            s.bindString(5, conflict.lost.hlc.replica.value)
            Sql.bind(s, 6, conflict.lost.value)
            s.bindLong(7, conflict.kept.hlc.time)
            s.bindString(8, conflict.kept.hlc.replica.value)
            Sql.bind(s, 9, conflict.kept.value)
            s.executeInsert() != -1L
        }

    override fun release(item: io.github.pdrakeweb.drivesync.core.Quarantined) {
        db.execSQL("DELETE FROM _ds_quarantine WHERE change = ?", arrayOf<Any?>(ChangeCodec.encode(item.change)))
    }

    private fun hlc(time: Long, replica: String) =
        io.github.pdrakeweb.drivesync.core.Hlc(time, io.github.pdrakeweb.drivesync.core.ReplicaId(replica))
}
