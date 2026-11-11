package io.github.pdrakeweb.drivesync.room

import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteProgram
import io.github.pdrakeweb.drivesync.core.Value

/** Small helpers over the SupportSQLite API (the interface Room exposes to its underlying database). */
internal object Sql {
    inline fun <T> query(db: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray(), block: (Cursor) -> T): T =
        db.query(SimpleSQLiteQuery(sql, args)).use(block)

    fun strings(db: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): List<String> =
        query(db, sql, args) { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    fun long(db: SupportSQLiteDatabase, sql: String, args: Array<out Any?> = emptyArray()): Long? =
        query(db, sql, args) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun meta(db: SupportSQLiteDatabase, key: String): String? =
        query(db, "SELECT value FROM _ds_meta WHERE key = ?", arrayOf<Any?>(key)) { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** The table's columns other than its primary key, in declaration order. */
    fun columns(db: SupportSQLiteDatabase, table: SyncTable): List<String> =
        query(db, "PRAGMA table_info(\"${table.name}\")") { c ->
            val names = ArrayList<String>()
            val nameIndex = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) names += c.getString(nameIndex)
            require(table.primaryKey in names) { "table ${table.name} has no column ${table.primaryKey}" }
            names.forEach(::requireIdentifier)
            names - table.primaryKey
        }

    fun value(c: Cursor, index: Int): Value = when (c.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> Value.Null
        Cursor.FIELD_TYPE_INTEGER -> Value.Integer(c.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> Value.Real(c.getDouble(index))
        Cursor.FIELD_TYPE_STRING -> Value.Text(c.getString(index))
        else -> Value.Bytes(c.getBlob(index))
    }

    fun bind(statement: SupportSQLiteProgram, index: Int, value: Value?) {
        when (value) {
            null, Value.Null -> statement.bindNull(index)
            is Value.Integer -> statement.bindLong(index, value.value)
            is Value.Real -> statement.bindDouble(index, value.value)
            is Value.Text -> statement.bindString(index, value.value)
            is Value.Bytes -> statement.bindBlob(index, value.value)
        }
    }

    inline fun <T> transaction(db: SupportSQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }
}
