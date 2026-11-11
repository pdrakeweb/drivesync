package io.github.pdrakeweb.drivesync.sample

import android.app.Application
import android.os.Build
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.pdrakeweb.drivesync.android.DriveSyncWork
import io.github.pdrakeweb.drivesync.android.SyncScheduler
import io.github.pdrakeweb.drivesync.backend.local.LocalFolderBlobStore
import io.github.pdrakeweb.drivesync.core.KeyManager
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.WrappedKey
import io.github.pdrakeweb.drivesync.room.RoomLocalStore
import io.github.pdrakeweb.drivesync.room.syncSchema
import kotlinx.coroutines.flow.Flow
import java.io.File
import kotlin.time.Duration.Companion.seconds

@Entity(tableName = "notes")
data class Note(@PrimaryKey val id: String, val title: String, val body: String?)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY title, id")
    fun observe(): Flow<List<Note>>

    @Insert
    suspend fun insert(note: Note)

    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM notes WHERE title = :title LIMIT 1")
    suspend fun byTitle(title: String): Note?
}

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class SampleDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
}

/** The whole integration: a schema, a store on the Room database, a backend, an engine, a scheduler. */
class SampleApp : Application() {
    private val schema = syncSchema(schemaVersion = 1) {
        table("notes", MergeClass.LWW_FIELDS)
    }

    val database: SampleDatabase by lazy {
        Room.databaseBuilder(this, SampleDatabase::class.java, "sample.db")
            // Installs the capture triggers before any query can run.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) = RoomLocalStore.install(db, schema)
            })
            .build()
    }

    /** The folder that stands in for cloud storage in this milestone; copy it between devices to sync. */
    val syncFolder: File by lazy { File(getExternalFilesDir(null), "drivesync/v1") }

    val engine: SyncEngine by lazy {
        val store = RoomLocalStore(database.openHelper, schema) {
            // Room does not notice writes made outside its own queries; tell it to re-check.
            database.invalidationTracker.refreshVersionsAsync()
        }
        SyncEngine(
            replicaId = store.replicaId,
            local = store,
            blobs = LocalFolderBlobStore(syncFolder.toPath()),
            keys = NoEncryptionYet,
            registry = schema.registry,
            config = SyncConfig(label = "${Build.MANUFACTURER} ${Build.MODEL}"),
        )
    }

    val scheduler: SyncScheduler by lazy { SyncScheduler(this, requiresNetwork = false) }

    override fun onCreate() {
        super.onCreate()
        DriveSyncWork.setEngineProvider { engine }
        scheduler.schedulePeriodic()
        scheduler.syncOnForeground()
    }

    /** Call after every local write. */
    suspend fun changed() {
        engine.refreshPending()
        scheduler.requestSync(debounce = 2.seconds)
    }
}

/**
 * PLACEHOLDER, NOT ENCRYPTION. Real encryption arrives with the `:crypto` module in milestone S3;
 * until then this sample writes its sync folder unencrypted and says so on screen.
 */
object NoEncryptionYet : KeyManager {
    override suspend fun state() = KeyState.Ready
    override suspend fun create(passphrase: CharArray) = WrappedKey("none", emptyMap(), ByteArray(0), ByteArray(0))
    override suspend fun unlock(passphrase: CharArray, wrapped: WrappedKey) = Unit
    override suspend fun encrypt(path: String, plain: ByteArray) = plain
    override suspend fun decrypt(path: String, cipher: ByteArray) = cipher
    override suspend fun fingerprint(plain: ByteArray) = "%016x".format(plain.contentHashCode().toLong())
}
