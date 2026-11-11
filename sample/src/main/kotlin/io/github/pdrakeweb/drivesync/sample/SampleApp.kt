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
import io.github.pdrakeweb.drivesync.android.KeystoreKeyStorage
import io.github.pdrakeweb.drivesync.android.SyncScheduler
import io.github.pdrakeweb.drivesync.backend.local.LocalFolderBlobStore
import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncSetup
import io.github.pdrakeweb.drivesync.crypto.PassphraseKeyManager
import io.github.pdrakeweb.drivesync.room.RoomLocalStore
import io.github.pdrakeweb.drivesync.room.syncSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** The whole integration: a schema, a store on the Room database, a backend, keys, an engine, a scheduler. */
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

    /** The folder that stands in for cloud storage; copy it between devices to sync. */
    val syncFolder: File by lazy { File(getExternalFilesDir(null), "drivesync/v1") }

    private val blobs: BlobStore by lazy { LocalFolderBlobStore(syncFolder.toPath()) }

    /** The data key, held on this device under the Android Keystore. */
    val keys: PassphraseKeyManager by lazy { PassphraseKeyManager(KeystoreKeyStorage(this)) }

    val setup: SyncSetup by lazy { SyncSetup(blobs, keys) }

    /** Whether this device holds the key; the screen shows the passphrase form until it does. */
    val hasKey = MutableStateFlow(false)

    val engine: SyncEngine by lazy {
        val store = RoomLocalStore(database.openHelper, schema) {
            // Room does not notice writes made outside its own queries; tell it to re-check.
            database.invalidationTracker.refreshVersionsAsync()
        }
        SyncEngine(
            replicaId = store.replicaId,
            local = store,
            blobs = blobs,
            keys = keys,
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

    suspend fun refreshKeyState() {
        hasKey.value = keys.state() == KeyState.Ready
    }

    /** Turns sync on with a passphrase: creates the key on the first device, opens it on later ones. */
    suspend fun turnOn(passphrase: String): SyncSetup.Result {
        val result = setup.create(passphrase.toCharArray())
        refreshKeyState()
        if (result == SyncSetup.Result.Ready) engine.sync()
        return result
    }

    /** Call after every local write. */
    suspend fun changed() {
        engine.refreshPending()
        scheduler.requestSync(debounce = 2.seconds)
    }
}
