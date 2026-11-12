package io.github.pdrakeweb.drivesync.room

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.pdrakeweb.drivesync.core.Change
import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.LocalStore
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncState
import io.github.pdrakeweb.drivesync.core.Value
import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import io.github.pdrakeweb.drivesync.testing.LocalStoreContract
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val V1 = """
    CREATE TABLE notes(id TEXT PRIMARY KEY NOT NULL, title TEXT, body TEXT);
    CREATE TABLE items(id TEXT PRIMARY KEY NOT NULL, note_id TEXT REFERENCES notes(id), text TEXT NOT NULL);
    CREATE TABLE settings(id TEXT PRIMARY KEY NOT NULL, a TEXT, b TEXT, c TEXT);
    CREATE TABLE log(id TEXT PRIMARY KEY NOT NULL, event TEXT, n INTEGER)
"""

// Version 2 adds notes.pinned by rebuilding the table, the way Room migrations often must.
private const val V1_TO_V2 = """
    CREATE TABLE notes_new(id TEXT PRIMARY KEY NOT NULL, title TEXT, body TEXT, pinned INTEGER NOT NULL DEFAULT 0);
    INSERT INTO notes_new(id, title, body) SELECT id, title, body FROM notes;
    DROP TABLE notes;
    ALTER TABLE notes_new RENAME TO notes
"""

private fun schema(version: Int) = syncSchema(version) {
    table("notes", MergeClass.LWW_FIELDS, recordConflicts = true)
    table("items", MergeClass.LWW_FIELDS) { parent("note_id", "notes") }
    table("settings", MergeClass.LWW_ROW)
    table("log", MergeClass.APPEND_ONLY)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLocalStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val helpers = ArrayList<SupportSQLiteOpenHelper>()
    private val cloud = InMemoryBlobStore()

    @After
    fun close() = helpers.forEach { it.close() }

    private fun open(name: String?, version: Int = 1, before: (SupportSQLiteDatabase) -> Unit = {}): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1.split(';').forEach { db.execSQL(it) }
                if (version >= 2) V1_TO_V2.split(';').forEach { db.execSQL(it) }
                before(db)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                // Rebuilding a table that other tables reference needs foreign keys off, as in Room.
                V1_TO_V2.split(';').forEach { db.execSQL(it) }
            }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        return FrameworkSQLiteOpenHelperFactory().create(config).also { helpers += it }
    }

    private inner class Device(val helper: SupportSQLiteOpenHelper, version: Int = 1) {
        val changedTables = HashSet<String>()
        val store = RoomLocalStore(helper, schema(version)) { changedTables += it }
        val db: SupportSQLiteDatabase get() = helper.writableDatabase
        val engine = SyncEngine(store.replicaId, store, cloud, InsecureTestKeyManager(), schema(version).registry, SyncConfig("d", "test"))

        fun sync(): SyncState = runBlocking { engine.sync() }
        fun rows(table: String, columns: String = "*"): List<String> =
            Sql.query(db, "SELECT $columns FROM $table ORDER BY id") { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out += (0 until c.columnCount).joinToString("|") { if (c.isNull(it)) "null" else c.getString(it) }
                out
            }
        fun outbox(): List<String> = runBlocking {
            store.drainOutbox(1_000).map { "${it.table}/${it.rowId}.${it.column}=${(it.value as? Value.Text)?.value ?: it.value}" }
        }
        fun pending() = runBlocking { store.pendingCount() }
        fun ackAll() = runBlocking { store.drainOutbox(1_000).maxOfOrNull { it.hlc.time }?.let { store.ackOutbox(it) } }
    }

    private fun device(name: String? = null, version: Int = 1) = Device(open(name, version), version)

    private fun remote(table: String, row: String, column: String, value: Value, wall: Long, replica: String = "remote") =
        Change(table, row, column, value, Hlc.of(wall, 0, ReplicaId(replica)))

    private val far = 4_000_000_000_000L // a wall time later than now, so remote changes win

    // --- change capture

    @Test
    fun `insert, update and delete are captured column by column`() {
        val d = device()
        d.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n1', 'hello', NULL)")
        assertEquals(listOf("notes/n1.title=hello", "notes/n1.body=Null"), d.outbox())
        d.ackAll()

        d.db.execSQL("UPDATE notes SET title = 'hello', body = 'text' WHERE id = 'n1'")
        assertEquals("only the column whose value changed", listOf("notes/n1.body=text"), d.outbox())
        d.ackAll()

        d.db.execSQL("DELETE FROM notes WHERE id = 'n1'")
        assertEquals(listOf("notes/n1.null=null"), d.outbox())
    }

    @Test
    fun `a rolled back transaction leaves nothing in the outbox`() {
        val d = device()
        d.db.beginTransaction()
        d.db.execSQL("INSERT INTO notes(id, title) VALUES('n1', 'never committed')")
        d.db.endTransaction()
        assertEquals(0, d.pending())
        assertEquals(emptyList<String>(), d.rows("notes"))
    }

    @Test
    fun `misuse fails loudly instead of corrupting sync`() {
        val d = device()
        d.db.execSQL("INSERT INTO notes(id, title) VALUES('n1', 't')")
        d.db.execSQL("INSERT INTO log(id, event, n) VALUES('l1', 'e', 1)")
        d.db.execSQL("DELETE FROM notes WHERE id = 'n1'")
        fun refused(sql: String, message: String) {
            val e = assertThrows(SQLiteConstraintException::class.java) { d.db.execSQL(sql) }
            assertTrue(e.message, e.message!!.contains(message))
        }
        refused("INSERT INTO notes(id, title) VALUES('n1', 'again')", "a deleted row id cannot be reused")
        refused("UPDATE log SET event = 'edited' WHERE id = 'l1'", "append-only")
    }

    @Test
    fun `changing the primary key of a synced row is refused`() {
        val d = device()
        d.db.execSQL("INSERT INTO notes(id, title) VALUES('n1', 't')")
        val e = assertThrows(SQLiteConstraintException::class.java) { d.db.execSQL("UPDATE notes SET id = 'n2' WHERE id = 'n1'") }
        assertTrue(e.message!!.contains("primary key of a synced row cannot change"))
    }

    @Test
    fun `rows that existed before sync was installed are captured`() {
        val helper = open(null) { db ->
            db.execSQL("INSERT INTO notes(id, title, body) VALUES('old1', 'existing', NULL)")
            db.execSQL("INSERT INTO settings(id, a, b, c) VALUES('s', '1', NULL, '3')")
        }
        val d = Device(helper)
        assertEquals(listOf("notes/old1.title=existing", "settings/s.a=1", "settings/s.b=Null", "settings/s.c=3"), d.outbox())
        RoomLocalStore.install(d.db, schema(1))
        assertEquals("installing again captures nothing twice", 4, d.pending())
    }

    // --- applying remote changes

    @Test
    fun `remote changes reach the app tables, do not echo, and notify`() = runBlocking {
        val d = device()
        val report = d.store.apply(
            listOf(
                remote("notes", "n1", "title", Value.Text("from afar"), far),
                remote("notes", "n1", "body", Value.Null, far + 1),
                remote("settings", "s", "a", Value.Text("x"), far),
            ),
            schema(1).registry,
        )
        assertEquals(3, report.applied)
        assertEquals(listOf("n1|from afar|null"), d.rows("notes"))
        assertEquals(listOf("s|x|null|null"), d.rows("settings"))
        assertEquals(0, d.pending())
        assertEquals(setOf("notes", "settings"), d.changedTables)

        d.store.apply(listOf(remote("notes", "n1", "title", Value.Text("older, loses"), 5)), schema(1).registry)
        assertEquals(listOf("n1|from afar|null"), d.rows("notes"))

        d.store.apply(listOf(Change.tombstone("notes", "n1", Hlc.of(1, 0, ReplicaId("remote")))), schema(1).registry)
        assertEquals(emptyList<String>(), d.rows("notes"))
        assertEquals(0, d.pending())
    }

    @Test
    fun `a local edit and a remote edit of different columns both survive`() = runBlocking {
        val d = device()
        d.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n1', 'local title', 'local body')")
        d.store.apply(listOf(remote("notes", "n1", "body", Value.Text("remote body"), far)), schema(1).registry)
        assertEquals(listOf("n1|local title|remote body"), d.rows("notes"))
        d.db.execSQL("UPDATE notes SET body = 'edited after seeing the remote one' WHERE id = 'n1'")
        val mine = d.store.drainOutbox(100).last()
        assertTrue("the SQL clock absorbed the remote timestamp", mine.hlc.wallMillis >= far)
    }

    @Test
    fun `a child is held until its parent arrives, and orphaned when the parent is deleted`() = runBlocking {
        val d = device()
        val registry = schema(1).registry
        var report = d.store.apply(
            listOf(
                remote("items", "i1", "note_id", Value.Text("n1"), far),
                remote("items", "i1", "text", Value.Text("child"), far + 1),
            ),
            registry,
        )
        assertEquals(listOf(RowRef("items", "i1")), report.held)
        assertEquals(emptyList<String>(), d.rows("items"))

        report = d.store.apply(listOf(remote("notes", "n1", "title", Value.Text("parent"), far)), registry)
        assertEquals(emptyList<RowRef>(), report.held)
        assertEquals(listOf("i1|n1|child"), d.rows("items"))

        report = d.store.apply(listOf(Change.tombstone("notes", "n1", Hlc.of(far + 5, 0, ReplicaId("remote")))), registry)
        assertEquals(listOf(RowRef("items", "i1")), report.orphaned)
        assertEquals(emptyList<String>(), d.rows("items"))
        assertEquals(emptyList<String>(), d.rows("notes"))
        assertEquals("the orphan is kept in merged state", 2, d.store.snapshot().count { it.table == "items" })
        assertEquals(0, d.pending())
    }

    @Test
    fun `a row that does not yet satisfy the table's constraints is held, then shown`() = runBlocking {
        val d = device()
        val registry = schema(1).registry
        var report = d.store.apply(listOf(remote("items", "i1", "note_id", Value.Null, far)), registry)
        assertEquals("text is NOT NULL and has not arrived", listOf(RowRef("items", "i1")), report.held)
        report = d.store.apply(listOf(remote("items", "i1", "text", Value.Text("now complete"), far)), registry)
        assertEquals(emptyList<RowRef>(), report.held)
        assertEquals(listOf("i1|null|now complete"), d.rows("items"))
    }

    // --- conflict log

    @Test
    fun `concurrent edits of one column are kept in the conflict log, knowing overwrites are not`() = runBlocking {
        val a = device(); val b = device()
        a.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n1', 'original', 'x')")
        a.sync(); b.sync()
        a.db.execSQL("UPDATE notes SET title = 'from a' WHERE id = 'n1'")
        Thread.sleep(5)
        b.db.execSQL("UPDATE notes SET title = 'from b' WHERE id = 'n1'")
        a.sync(); b.sync(); a.sync()

        for (d in listOf(a, b)) {
            val conflict = d.store.conflicts().single()
            assertEquals(RowRef("notes", "n1"), conflict.row)
            assertEquals("title", conflict.column)
            assertEquals(Value.Text("from b"), conflict.kept.value)
            assertEquals(Value.Text("from a"), conflict.lost.value)
            assertEquals(listOf("n1|from b|x"), d.rows("notes"))
        }

        a.db.execSQL("UPDATE notes SET title = 'a again, having seen b' WHERE id = 'n1'")
        a.sync(); b.sync(); a.sync(); b.sync()
        assertEquals("a knowing overwrite is not a conflict", 1, b.store.conflicts().size)
        assertEquals(listOf("n1|a again, having seen b|x"), b.rows("notes"))

        b.store.clearConflicts()
        assertEquals(0, b.store.conflicts().size)
        b.sync(); a.sync(); b.sync()
        assertEquals("cleared entries do not come back", 0, b.store.conflicts().size)
    }

    // --- end to end

    @Test
    fun `two databases converge through the sync engine across every merge class`() {
        val a = device(); val b = device()
        a.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n1', 'a title', 'a body')")
        a.db.execSQL("INSERT INTO items(id, note_id, text) VALUES('i1', 'n1', 'child')")
        a.db.execSQL("INSERT INTO settings(id, a, b, c) VALUES('s', '1', '2', '3')")
        a.db.execSQL("INSERT INTO log(id, event, n) VALUES('la', 'from a', 1)")
        b.db.execSQL("INSERT INTO log(id, event, n) VALUES('lb', 'from b', 2)")
        assertTrue(a.sync() is SyncState.Idle); assertTrue(b.sync() is SyncState.Idle); assertTrue(a.sync() is SyncState.Idle)

        b.db.execSQL("UPDATE notes SET title = 'b title' WHERE id = 'n1'")
        a.db.execSQL("UPDATE notes SET body = 'a body 2' WHERE id = 'n1'")
        b.db.execSQL("UPDATE settings SET a = '9' WHERE id = 's'")
        b.db.execSQL("DELETE FROM log WHERE id = 'la'")
        repeat(2) { a.sync(); b.sync() }

        for (table in listOf("notes", "items", "settings", "log")) assertEquals(table, a.rows(table), b.rows(table))
        assertEquals(listOf("n1|b title|a body 2"), a.rows("notes"))
        assertEquals(listOf("i1|n1|child"), b.rows("items"))
        assertEquals(listOf("s|9|2|3"), a.rows("settings"))
        assertEquals(listOf("lb|from b|2"), a.rows("log"))
        assertEquals(0, a.pending() + b.pending())
        assertEquals(runBlocking { a.store.snapshot().toSet() }, runBlocking { b.store.snapshot().toSet() })
    }

    @Test
    fun `a migration with pending changes loses nothing, and later data for the new column is kept`() {
        val peer = device(version = 2)
        val old = Device(open("migrating.db", 1), 1)
        old.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n1', 'made on v1', 'pending at upgrade time')")
        old.sync(); peer.sync()
        // The peer (already on v2) pins the note; the old device receives a column it has never heard of.
        peer.db.execSQL("UPDATE notes SET pinned = 1 WHERE id = 'n1'")
        peer.sync(); old.sync()
        old.db.execSQL("INSERT INTO notes(id, title, body) VALUES('n2', 'unpushed when the app was updated', NULL)")
        old.db.execSQL("UPDATE notes SET body = 'also unpushed' WHERE id = 'n1'")
        val pendingBefore = old.pending()
        assertEquals(3, pendingBefore)
        old.helper.close()

        val upgraded = Device(open("migrating.db", 2), 2)
        assertEquals("pinned arrived before the column existed and is written into it now", listOf("n1|1", "n2|0"), upgraded.rows("notes", "id, pinned"))
        assertTrue("nothing pending was lost", upgraded.pending() >= pendingBefore)
        assertTrue(upgraded.sync() is SyncState.Idle)
        assertTrue(peer.sync() is SyncState.Idle)
        upgraded.sync()

        assertEquals(upgraded.rows("notes"), peer.rows("notes"))
        assertEquals(listOf("n1|made on v1|also unpushed|1", "n2|unpushed when the app was updated|null|0"), peer.rows("notes"))
        assertEquals(0, upgraded.pending())
        // Capture still works on the rebuilt table.
        upgraded.db.execSQL("UPDATE notes SET pinned = 1 WHERE id = 'n2'")
        assertEquals(listOf("notes/n2.pinned=Integer(value=1)"), upgraded.outbox())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLocalStoreContractTest {
    private val helpers = ArrayList<SupportSQLiteOpenHelper>()

    private val contract = object : LocalStoreContract() {
        override fun newStore(): LocalStore {
            val callback = object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY NOT NULL, title TEXT, body TEXT)")
                    db.execSQL("CREATE TABLE settings(id TEXT PRIMARY KEY NOT NULL, a TEXT, b TEXT, c TEXT)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
            val context: Context = ApplicationProvider.getApplicationContext()
            val helper = FrameworkSQLiteOpenHelperFactory()
                .create(SupportSQLiteOpenHelper.Configuration.builder(context).name(null).callback(callback).build())
            helpers += helper
            val schema = syncSchema(1) {
                table("notes", MergeClass.LWW_FIELDS)
                table("settings", MergeClass.LWW_ROW)
            }
            return Stored(helper, RoomLocalStore(helper, schema))
        }

        override fun localWrite(store: LocalStore, table: String, rowId: String, columns: Map<String, Value>) {
            val db = (store as Stored).helper.writableDatabase
            val names = columns.keys.toList()
            val values = names.map { (columns.getValue(it) as Value.Text).value }
            val exists = Sql.long(db, "SELECT 1 FROM $table WHERE id = ?", arrayOf(rowId)) != null
            if (exists) {
                db.execSQL("UPDATE $table SET ${names.joinToString { "$it = ?" }} WHERE id = ?", (values + rowId).toTypedArray())
            } else {
                db.execSQL(
                    "INSERT INTO $table(${(names + "id").joinToString()}) VALUES(${List(names.size + 1) { "?" }.joinToString()})",
                    (values + rowId).toTypedArray(),
                )
            }
        }
    }

    private class Stored(val helper: SupportSQLiteOpenHelper, store: RoomLocalStore) : LocalStore by store

    @After
    fun close() = helpers.forEach { it.close() }

    @Test
    fun `every check of the local store contract holds`() {
        assertEquals(7, contract.checks.size)
        val failures = contract.checks.mapNotNull { (name, check) ->
            try {
                check()
                null
            } catch (e: Throwable) {
                "$name: $e"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }
}
