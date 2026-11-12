package io.github.pdrakeweb.drivesync.room

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.pdrakeweb.drivesync.core.MergeClass
import java.util.UUID

/**
 * Creates the sync metadata tables and the triggers that capture every local write.
 *
 * A trigger (SQL that SQLite runs automatically after an insert, update or delete) records each
 * change into the outbox inside the app's own transaction, so a write and its outbox entry commit
 * or roll back together. Triggers stay silent while the library itself applies remote changes.
 */
internal object SyncInstaller {
    // A hybrid logical clock tick in SQL: wall milliseconds shifted left 16 bits, or the previous
    // value plus one, whichever is greater. julianday works on every SQLite version Android ships.
    private const val TICK =
        "UPDATE _ds_meta SET value = max(value + 1, " +
            "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) * 65536) WHERE key = 'clock'"
    private const val CLOCK = "(SELECT value FROM _ds_meta WHERE key = 'clock')"
    private const val REPLICA = "(SELECT value FROM _ds_meta WHERE key = 'replica')"
    private const val NOT_APPLYING = "(SELECT value FROM _ds_meta WHERE key = 'applying') = 0"

    private val METADATA = listOf(
        "CREATE TABLE IF NOT EXISTS _ds_meta(key TEXT PRIMARY KEY NOT NULL, value)",
        "CREATE TABLE IF NOT EXISTS _ds_outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT, tbl TEXT NOT NULL, " +
            "row_id TEXT NOT NULL, col TEXT, value, time INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS _ds_cells(tbl TEXT NOT NULL, row_id TEXT NOT NULL, col TEXT NOT NULL, value, " +
            "time INTEGER NOT NULL, replica TEXT NOT NULL, PRIMARY KEY(tbl, row_id, col))",
        "CREATE TABLE IF NOT EXISTS _ds_tombstones(tbl TEXT NOT NULL, row_id TEXT NOT NULL, time INTEGER NOT NULL, " +
            "replica TEXT NOT NULL, PRIMARY KEY(tbl, row_id))",
        "CREATE TABLE IF NOT EXISTS _ds_cursors(replica TEXT PRIMARY KEY NOT NULL, seq INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS _ds_quarantine(change BLOB PRIMARY KEY NOT NULL, reason TEXT, tbl TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS _ds_rowstate(tbl TEXT NOT NULL, row_id TEXT NOT NULL, status TEXT NOT NULL, " +
            "PRIMARY KEY(tbl, row_id))",
        "CREATE TABLE IF NOT EXISTS _ds_conflicts(tbl TEXT NOT NULL, row_id TEXT NOT NULL, col TEXT NOT NULL, " +
            "lost_time INTEGER NOT NULL, lost_replica TEXT NOT NULL, lost_value, kept_time INTEGER NOT NULL, " +
            "kept_replica TEXT NOT NULL, kept_value, PRIMARY KEY(tbl, row_id, col, lost_time, lost_replica))",
    )

    /** Idempotent. Run on every open, and again after any migration, inside or outside a transaction. */
    fun install(db: SupportSQLiteDatabase, schema: SyncSchema) {
        db.beginTransaction()
        try {
            METADATA.forEach(db::execSQL)
            // Columns added after the first builds of this adapter.
            for (table in listOf("_ds_outbox", "_ds_cells")) {
                addColumn(db, table, "prev_time", "INTEGER")
                addColumn(db, table, "prev_replica", "TEXT")
            }
            db.execSQL("INSERT OR IGNORE INTO _ds_meta(key, value) VALUES('replica', ?)", arrayOf<Any?>(newReplicaId()))
            for (key in listOf("clock", "applying", "pinned")) {
                db.execSQL("INSERT OR IGNORE INTO _ds_meta(key, value) VALUES(?, 0)", arrayOf<Any?>(key))
            }
            // A crash cannot leave this set (the flag changes inside a transaction), but be explicit.
            db.execSQL("UPDATE _ds_meta SET value = 0 WHERE key = 'applying'")
            for (table in schema.parentsFirst) {
                val columns = Sql.columns(db, table)
                require(columns.isNotEmpty()) { "table ${table.name} needs at least one column besides its primary key" }
                createTriggers(db, table, columns)
                val signature = columns.joinToString(",")
                val previous = Sql.meta(db, "columns:${table.name}")
                if (previous != signature) {
                    // After a migration added a column, values for it may already have arrived from
                    // a device on the newer schema and been kept; write them into the new column.
                    if (previous != null) Materializer(db, schema).rematerialize(table)
                    db.execSQL("INSERT OR REPLACE INTO _ds_meta(key, value) VALUES(?, ?)", arrayOf<Any?>("columns:${table.name}", signature))
                }
                backfill(db, table, columns)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun newReplicaId(): String = UUID.randomUUID().toString()

    private fun addColumn(db: SupportSQLiteDatabase, table: String, column: String, type: String) {
        val present = Sql.query(db, "PRAGMA table_info($table)") { c ->
            val name = c.getColumnIndexOrThrow("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }.toList()
        }
        if (column !in present) db.execSQL("ALTER TABLE $table ADD COLUMN $column $type")
    }

    private fun createTriggers(db: SupportSQLiteDatabase, table: SyncTable, columns: List<String>) {
        val t = table.name
        val id = "CAST(NEW.\"${table.primaryKey}\" AS TEXT)"
        val oldId = "CAST(OLD.\"${table.primaryKey}\" AS TEXT)"
        // A field write also records the timestamp of the value it replaces (Change.prev). The
        // subqueries read the cell before the same statement replaces it.
        fun capture(column: String, where: String = "", withPrevious: Boolean = false): String {
            val old = "FROM _ds_cells WHERE tbl = '$t' AND row_id = $id AND col = '$column'"
            val prevTime = if (withPrevious) "(SELECT time $old)" else "NULL"
            val prevReplica = if (withPrevious) "(SELECT replica $old)" else "NULL"
            return "INSERT INTO _ds_outbox(tbl, row_id, col, value, time, prev_time, prev_replica) " +
                "SELECT '$t', $id, '$column', NEW.\"$column\", $CLOCK, $prevTime, $prevReplica $where; " +
                "INSERT OR REPLACE INTO _ds_cells(tbl, row_id, col, value, time, replica, prev_time, prev_replica) " +
                "SELECT '$t', $id, '$column', NEW.\"$column\", $CLOCK, $REPLICA, $prevTime, $prevReplica $where; "
        }
        // Whole-row classes stamp every column of one write with the same timestamp.
        val sharedClock = table.mergeClass != MergeClass.LWW_FIELDS

        for (suffix in listOf("ai", "au", "ad")) db.execSQL("DROP TRIGGER IF EXISTS \"_ds_${suffix}_$t\"")

        val insert = StringBuilder()
        insert.append("SELECT RAISE(ABORT, 'drivesync: a deleted row id cannot be reused ($t)') WHERE EXISTS ")
            .append("(SELECT 1 FROM _ds_tombstones WHERE tbl = '$t' AND row_id = $id); ")
        if (table.mergeClass == MergeClass.APPEND_ONLY) {
            insert.append("SELECT RAISE(ABORT, 'drivesync: this append-only row already exists ($t)') WHERE EXISTS ")
                .append("(SELECT 1 FROM _ds_cells WHERE tbl = '$t' AND row_id = $id); ")
        }
        if (sharedClock) {
            insert.append("$TICK; DELETE FROM _ds_cells WHERE tbl = '$t' AND row_id = $id; ")
        }
        for (column in columns) {
            if (!sharedClock) insert.append("$TICK; ")
            insert.append(capture(column, withPrevious = !sharedClock))
        }
        db.execSQL("CREATE TRIGGER \"_ds_ai_$t\" AFTER INSERT ON \"$t\" WHEN $NOT_APPLYING BEGIN $insert END")

        val update = StringBuilder()
        update.append("SELECT RAISE(ABORT, 'drivesync: the primary key of a synced row cannot change ($t)') ")
            .append("WHERE $id IS NOT $oldId; ")
        when (table.mergeClass) {
            MergeClass.APPEND_ONLY ->
                update.append("SELECT RAISE(ABORT, 'drivesync: $t is append-only and cannot be updated'); ")
            MergeClass.LWW_ROW -> {
                update.append("$TICK; DELETE FROM _ds_cells WHERE tbl = '$t' AND row_id = $id; ")
                columns.forEach { update.append(capture(it)) }
            }
            MergeClass.LWW_FIELDS -> for (column in columns) {
                val changed = "WHERE NEW.\"$column\" IS NOT OLD.\"$column\""
                update.append("$TICK AND NEW.\"$column\" IS NOT OLD.\"$column\"; ").append(capture(column, changed, withPrevious = true))
            }
        }
        db.execSQL("CREATE TRIGGER \"_ds_au_$t\" AFTER UPDATE ON \"$t\" WHEN $NOT_APPLYING BEGIN $update END")

        db.execSQL(
            "CREATE TRIGGER \"_ds_ad_$t\" AFTER DELETE ON \"$t\" WHEN $NOT_APPLYING BEGIN " +
                "$TICK; " +
                "INSERT INTO _ds_outbox(tbl, row_id, col, value, time) VALUES('$t', $oldId, NULL, NULL, $CLOCK); " +
                "INSERT OR REPLACE INTO _ds_tombstones(tbl, row_id, time, replica) VALUES('$t', $oldId, $CLOCK, $REPLICA); " +
                "DELETE FROM _ds_cells WHERE tbl = '$t' AND row_id = $oldId; " +
                "DELETE FROM _ds_rowstate WHERE tbl = '$t' AND row_id = $oldId; " +
                "END",
        )
    }

    /**
     * Rows that existed before sync was installed, and columns a migration filled in, have no sync
     * metadata yet. Capture them as local writes so they reach other devices.
     */
    private fun backfill(db: SupportSQLiteDatabase, table: SyncTable, columns: List<String>) {
        val t = table.name
        val id = "CAST(\"${table.primaryKey}\" AS TEXT)"
        val untracked = "NOT EXISTS (SELECT 1 FROM _ds_tombstones WHERE tbl = '$t' AND row_id = $id)"
        if (table.mergeClass != MergeClass.LWW_FIELDS) {
            // Whole-row tables are captured row by row, all columns under one timestamp.
            val rows = Sql.strings(
                db,
                "SELECT $id FROM \"$t\" WHERE $untracked AND NOT EXISTS (SELECT 1 FROM _ds_cells WHERE tbl = '$t' AND row_id = $id)",
            )
            for (rowId in rows) {
                db.execSQL(TICK)
                for (column in columns) captureExisting(db, table, column, "$id = ?", arrayOf<Any?>(rowId))
            }
            return
        }
        for (column in columns) {
            val missing = "$untracked AND \"$column\" IS NOT NULL AND NOT EXISTS " +
                "(SELECT 1 FROM _ds_cells WHERE tbl = '$t' AND row_id = $id AND col = '$column')"
            for (rowId in Sql.strings(db, "SELECT $id FROM \"$t\" WHERE $missing")) {
                db.execSQL(TICK)
                captureExisting(db, table, column, "$id = ?", arrayOf<Any?>(rowId))
            }
        }
    }

    private fun captureExisting(db: SupportSQLiteDatabase, table: SyncTable, column: String, where: String, args: Array<Any?>) {
        val t = table.name
        val id = "CAST(\"${table.primaryKey}\" AS TEXT)"
        db.execSQL(
            "INSERT INTO _ds_outbox(tbl, row_id, col, value, time) SELECT '$t', $id, '$column', \"$column\", $CLOCK FROM \"$t\" WHERE $where",
            args,
        )
        db.execSQL(
            "INSERT OR REPLACE INTO _ds_cells(tbl, row_id, col, value, time, replica) " +
                "SELECT '$t', $id, '$column', \"$column\", $CLOCK, $REPLICA FROM \"$t\" WHERE $where",
            args,
        )
    }
}
