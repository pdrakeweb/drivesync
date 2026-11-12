@file:OptIn(ExperimentalSerializationApi::class)

package io.github.pdrakeweb.drivesync.room

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.pdrakeweb.drivesync.core.ApplyReport
import io.github.pdrakeweb.drivesync.core.Change
import io.github.pdrakeweb.drivesync.core.Hlc
import io.github.pdrakeweb.drivesync.core.LocalStore
import io.github.pdrakeweb.drivesync.core.MergeEngine
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.QuarantineReason
import io.github.pdrakeweb.drivesync.core.Quarantined
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.RowRef
import io.github.pdrakeweb.drivesync.core.RowStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * A [LocalStore] inside the app's own SQLite database, so sync metadata commits and rolls back
 * with the app's data. Works with Room (pass `roomDatabase.openHelper`) or any other user of the
 * AndroidX SQLite library.
 *
 * Creating the store installs the capture triggers, so create it before the app writes: with Room,
 * also call [install] from `RoomDatabase.Callback.onOpen`, which runs before any query.
 *
 * Remote changes are written straight to the app's tables. Room does not notice writes made
 * outside its own queries, so pass an [onRemoteChange] that calls
 * `roomDatabase.invalidationTracker.refreshVersionsAsync()` to make observed queries re-run.
 */
public class RoomLocalStore(
    private val helper: SupportSQLiteOpenHelper,
    private val schema: SyncSchema,
    private val onRemoteChange: (tables: Set<String>) -> Unit = {},
) : LocalStore {
    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    init {
        install(db, schema)
    }

    /** This install's identity, created on first use and stored in the database. */
    public val replicaId: ReplicaId get() = ReplicaId(Sql.meta(db, "replica")!!)

    override suspend fun drainOutbox(max: Int): List<Change> = io {
        Sql.transaction(db) {
            var pinned = Sql.long(db, "SELECT value FROM _ds_meta WHERE key = 'pinned'") ?: 0
            if (pinned == 0L) {
                // Pin a prefix of the outbox. Changes that share a timestamp (one whole-row write)
                // are consecutive and are never split.
                val lastTime = Sql.long(db, "SELECT time FROM _ds_outbox ORDER BY seq LIMIT 1 OFFSET ?", arrayOf<Any?>(max - 1))
                pinned = if (lastTime == null) {
                    Sql.long(db, "SELECT max(seq) FROM _ds_outbox") ?: 0
                } else {
                    Sql.long(db, "SELECT max(seq) FROM _ds_outbox WHERE time <= ?", arrayOf<Any?>(lastTime)) ?: 0
                }
                db.execSQL("UPDATE _ds_meta SET value = ? WHERE key = 'pinned'", arrayOf<Any?>(pinned))
            }
            val replica = replicaId
            Sql.query(db, "SELECT tbl, row_id, col, value, time FROM _ds_outbox WHERE seq <= ? ORDER BY seq", arrayOf<Any?>(pinned)) { c ->
                val out = ArrayList<Change>()
                while (c.moveToNext()) {
                    val hlc = Hlc(c.getLong(4), replica)
                    out += if (c.isNull(2)) {
                        Change.tombstone(c.getString(0), c.getString(1), hlc)
                    } else {
                        Change(c.getString(0), c.getString(1), c.getString(2), Sql.value(c, 3), hlc)
                    }
                }
                out
            }
        }
    }

    override suspend fun ackOutbox(upTo: Long): Unit = io {
        Sql.transaction(db) {
            db.execSQL("DELETE FROM _ds_outbox WHERE time <= ?", arrayOf<Any?>(upTo))
            val pinned = Sql.long(db, "SELECT value FROM _ds_meta WHERE key = 'pinned'") ?: 0
            if (Sql.long(db, "SELECT 1 FROM _ds_outbox WHERE seq <= ? LIMIT 1", arrayOf<Any?>(pinned)) == null) {
                db.execSQL("UPDATE _ds_meta SET value = 0 WHERE key = 'pinned'")
            }
        }
    }

    override suspend fun pendingCount(): Int = io {
        (Sql.long(db, "SELECT count(*) FROM _ds_outbox") ?: 0).toInt()
    }

    override suspend fun apply(changes: List<Change>, registry: MergeRegistry): ApplyReport = io {
        val state = SqlMergeState(db)
        val report = Sql.transaction(db) {
            val merged = MergeEngine.apply(state, changes, registry)
            if (state.touched.isNotEmpty()) Materializer(db, schema).materialize(state.touched)
            merged.copy(held = rowsIn(RowStatus.HELD), orphaned = rowsIn(RowStatus.ORPHANED))
        }
        if (state.touched.isNotEmpty()) onRemoteChange(state.touched.map { it.table }.toSet())
        report
    }

    override suspend fun snapshot(): Sequence<Change> = io {
        val all = ArrayList<Change>()
        Sql.transaction(db) {
            Sql.query(db, "SELECT tbl, row_id, col, value, time, replica FROM _ds_cells") { c ->
                while (c.moveToNext()) {
                    all += Change(c.getString(0), c.getString(1), c.getString(2), Sql.value(c, 3), Hlc(c.getLong(4), ReplicaId(c.getString(5))))
                }
            }
            Sql.query(db, "SELECT tbl, row_id, time, replica FROM _ds_tombstones") { c ->
                while (c.moveToNext()) {
                    all += Change.tombstone(c.getString(0), c.getString(1), Hlc(c.getLong(2), ReplicaId(c.getString(3))))
                }
            }
        }
        all.asSequence()
    }

    override suspend fun cursors(): Map<ReplicaId, Long> = io {
        Sql.query(db, "SELECT replica, seq FROM _ds_cursors") { c ->
            val out = HashMap<ReplicaId, Long>()
            while (c.moveToNext()) out[ReplicaId(c.getString(0))] = c.getLong(1)
            out
        }
    }

    override suspend fun saveCursor(replica: ReplicaId, seq: Long): Unit = io {
        db.execSQL(
            "INSERT OR REPLACE INTO _ds_cursors(replica, seq) VALUES(?, max(?, " +
                "coalesce((SELECT seq FROM _ds_cursors WHERE replica = ?), 0)))",
            arrayOf<Any?>(replica.value, seq, replica.value),
        )
    }

    override suspend fun highestSchemaSeen(): Int = io {
        (Sql.long(db, "SELECT value FROM _ds_meta WHERE key = 'schema_seen'") ?: 0).toInt()
    }

    override suspend fun noteSchemaSeen(version: Int): Unit = io {
        db.execSQL(
            "INSERT OR REPLACE INTO _ds_meta(key, value) VALUES('schema_seen', max(?, " +
                "coalesce((SELECT value FROM _ds_meta WHERE key = 'schema_seen'), 0)))",
            arrayOf<Any?>(version),
        )
    }

    /** Changes currently set aside, for an app that wants to show them. */
    public suspend fun quarantined(): List<Quarantined> = io { SqlMergeState(db).quarantined().toList() }

    private fun rowsIn(status: RowStatus): List<RowRef> =
        Sql.query(db, "SELECT tbl, row_id FROM _ds_rowstate WHERE status = ? ORDER BY tbl, row_id", arrayOf<Any?>(status.name)) { c ->
            val out = ArrayList<RowRef>()
            while (c.moveToNext()) out += RowRef(c.getString(0), c.getString(1))
            out
        }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    public companion object {
        /**
         * Creates or refreshes the sync tables and capture triggers. Idempotent; run it on every
         * open and after every migration (migrations that rebuild a table drop its triggers).
         */
        public fun install(db: SupportSQLiteDatabase, schema: SyncSchema) {
            SyncInstaller.install(db, schema)
        }
    }
}

internal object ChangeCodec {
    private val cbor = Cbor { encodeDefaults = true }

    fun encode(change: Change): ByteArray = cbor.encodeToByteArray(Change.serializer(), change)

    fun reason(reason: QuarantineReason): String? = when (reason) {
        QuarantineReason.UnknownTable -> null
        is QuarantineReason.Rejected -> reason.reason
    }

    fun quarantined(change: ByteArray, reason: String?): Quarantined = Quarantined(
        cbor.decodeFromByteArray(Change.serializer(), change),
        if (reason == null) QuarantineReason.UnknownTable else QuarantineReason.Rejected(reason),
    )
}
