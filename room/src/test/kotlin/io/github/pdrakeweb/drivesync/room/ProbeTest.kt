package io.github.pdrakeweb.drivesync.room

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProbeTest {
    @Test
    fun realSqliteWithTriggersAndJulianday() {
        val db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE t(id TEXT PRIMARY KEY, v)")
        db.execSQL("CREATE TABLE log(x, at INTEGER)")
        db.execSQL("CREATE TRIGGER tr AFTER INSERT ON t BEGIN INSERT INTO log VALUES(NEW.v, max(1, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER) * 65536)); END")
        db.execSQL("INSERT INTO t VALUES('a', 5)")
        db.rawQuery("SELECT x, at, typeof(x), sqlite_version() FROM log", null).use { c ->
            assertTrue(c.moveToFirst())
            println("probe: at=${c.getLong(1)} now=${System.currentTimeMillis() shl 16} type=${c.getString(2)} sqlite=${c.getString(3)}")
            assertEquals("integer", c.getString(2))
            assertTrue(Math.abs((c.getLong(1) shr 16) - System.currentTimeMillis()) < 5_000)
        }
    }
}
