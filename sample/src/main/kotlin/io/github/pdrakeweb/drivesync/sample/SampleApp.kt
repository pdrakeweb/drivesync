package io.github.pdrakeweb.drivesync.sample

import android.accounts.Account
import android.app.Application
import android.content.Context
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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import io.github.pdrakeweb.drivesync.android.DriveSyncWork
import io.github.pdrakeweb.drivesync.android.KeystoreKeyStorage
import io.github.pdrakeweb.drivesync.android.SyncScheduler
import io.github.pdrakeweb.drivesync.backend.drive.AccessToken
import io.github.pdrakeweb.drivesync.backend.drive.DriveBlobStore
import io.github.pdrakeweb.drivesync.backend.local.LocalFolderBlobStore
import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.SyncSetup
import io.github.pdrakeweb.drivesync.crypto.PassphraseKeyManager
import io.github.pdrakeweb.drivesync.room.RoomLocalStore
import io.github.pdrakeweb.drivesync.room.syncSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

enum class Backend { LocalFolder, GoogleDrive }

/** Everything that depends on where the data is stored. Rebuilt when the person switches storage. */
class Sync(val backend: Backend, val blobs: BlobStore, val engine: SyncEngine, val setup: SyncSetup)

/**
 * Permission to use this app's own hidden folder in the person's Google Drive (the `drive.appdata`
 * scope: the app cannot see any other Drive file). Google identifies the app by its package name
 * and signing certificate, so there is no client secret anywhere in this project.
 */
object DriveAuth {
    /**
     * When set, Google is asked about this account directly instead of showing its account chooser.
     * Used by the test journey; a real app would remember the account the person picked.
     */
    @Volatile
    var accountName: String? = null

    private fun request(): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.appdata")))
        accountName?.let { builder.setAccount(Account(it, "com.google")) }
        return builder.build()
    }

    /** Asks Google Play services. The result either carries a token or needs a consent screen. */
    suspend fun authorize(context: Context): AuthorizationResult = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { continuation.resume(it) }
            .addOnFailureListener { continuation.resumeWithException(it) }
    }

    /** A current access token for the sync engine, without showing anything. */
    suspend fun token(context: Context): AccessToken {
        val result = try {
            authorize(context)
        } catch (e: ApiException) {
            val offline = e.statusCode == CommonStatusCodes.NETWORK_ERROR || e.statusCode == CommonStatusCodes.TIMEOUT
            throw SyncException(if (offline) SyncError.NetworkUnavailable else SyncError.AuthExpired, e)
        }
        // Consent was withdrawn or never given: only the person can fix that, from the screen.
        val token = result.accessToken
        if (result.hasResolution() || token == null) throw SyncException(SyncError.AuthExpired)
        return AccessToken(token)
    }
}

/** The whole integration: a schema, a store on the Room database, a backend, keys, an engine, a scheduler. */
class SampleApp : Application() {
    private val schema = syncSchema(schemaVersion = 1) {
        table("notes", MergeClass.LWW_FIELDS)
    }
    private val prefs by lazy { getSharedPreferences("sample", MODE_PRIVATE) }

    val database: SampleDatabase by lazy {
        Room.databaseBuilder(this, SampleDatabase::class.java, "sample.db")
            // Installs the capture triggers before any query can run.
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) = RoomLocalStore.install(db, schema)
            })
            .build()
    }

    private val store by lazy {
        RoomLocalStore(database.openHelper, schema) {
            // Room does not notice writes made outside its own queries; tell it to re-check.
            database.invalidationTracker.refreshVersionsAsync()
        }
    }

    /** The folder used when storage is "this device": copy it between devices to sync. */
    val syncFolder: File by lazy { File(getExternalFilesDir(null), "drivesync/v1") }

    /** The data key, held on this device under the Android Keystore. */
    val keys: PassphraseKeyManager by lazy { PassphraseKeyManager(KeystoreKeyStorage(this)) }

    /** Whether this device holds the key; the screen shows the passphrase form until it does. */
    val hasKey = MutableStateFlow(false)

    val sync: MutableStateFlow<Sync> by lazy {
        val saved = prefs.getString("backend", null)?.let(Backend::valueOf) ?: Backend.LocalFolder
        MutableStateFlow(build(saved))
    }
    val engine: SyncEngine get() = sync.value.engine
    val setup: SyncSetup get() = sync.value.setup
    val scheduler: SyncScheduler get() = SyncScheduler(this, requiresNetwork = sync.value.backend == Backend.GoogleDrive)

    private fun build(backend: Backend): Sync {
        val blobs: BlobStore = when (backend) {
            Backend.LocalFolder -> LocalFolderBlobStore(syncFolder.toPath())
            // The root can be overridden by the test journey so that each run starts empty.
            Backend.GoogleDrive -> DriveBlobStore(
                token = { DriveAuth.token(this) },
                root = prefs.getString("driveRoot", null) ?: "drivesync/v1/",
            )
        }
        val engine = SyncEngine(
            replicaId = store.replicaId,
            local = store,
            blobs = blobs,
            keys = keys,
            registry = schema.registry,
            config = SyncConfig(label = "${Build.MANUFACTURER} ${Build.MODEL}"),
        )
        return Sync(backend, blobs, engine, SyncSetup(blobs, keys))
    }

    /** Switches storage. Meant for before sync is turned on; this sample does not move data across. */
    fun useBackend(backend: Backend, driveRoot: String? = null) {
        prefs.edit().putString("backend", backend.name).apply {
            if (driveRoot != null) putString("driveRoot", driveRoot)
            DriveAuth.accountName?.let { putString("driveAccount", it) }
        }.apply()
        sync.value = build(backend)
        scheduler.schedulePeriodic()
    }

    override fun onCreate() {
        super.onCreate()
        DriveAuth.accountName = prefs.getString("driveAccount", null)
        DriveSyncWork.setEngineProvider { engine }
        scheduler.schedulePeriodic()
        scheduler.syncOnForeground()
    }

    suspend fun refreshKeyState() {
        hasKey.value = keys.state() == KeyState.Ready
    }

    /** Turns sync on with a passphrase: creates the key on the first device, opens it on later ones. */
    suspend fun turnOn(passphrase: String, onAccepted: () -> Unit = {}): SyncSetup.Result {
        val result = setup.create(passphrase.toCharArray())
        // Called while the passphrase form is still on screen, before the key state hides it.
        if (result == SyncSetup.Result.Ready) onAccepted()
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
