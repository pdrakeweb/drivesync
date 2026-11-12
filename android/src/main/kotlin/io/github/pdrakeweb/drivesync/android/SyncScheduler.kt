package io.github.pdrakeweb.drivesync.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.pdrakeweb.drivesync.core.Fix
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Where background work finds the app's [SyncEngine].
 *
 * WorkManager (Android's scheduler for deferrable background work) may start the app's process
 * just to run a sync, with no screen open. Set the provider in `Application.onCreate`, which runs
 * first in that case too.
 */
public object DriveSyncWork {
    @Volatile
    internal var provider: (() -> SyncEngine?)? = null

    /** [provider] returns null when sync is not set up yet (the person has not turned it on). */
    public fun setEngineProvider(provider: () -> SyncEngine?) {
        this.provider = provider
    }
}

/** Runs one sync cycle. Scheduled by [SyncScheduler]; apps do not create it themselves. */
public class DriveSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // No provider is a programming error (setEngineProvider was not called): fail visibly in
        // WorkManager's records rather than report success for work that never ran.
        val provider = DriveSyncWork.provider ?: return Result.failure()
        val engine = provider() ?: return Result.success()
        return when (val state = engine.sync()) {
            is SyncState.Idle -> Result.success()
            // WorkManager retries with backoff; the network constraint holds it until online.
            SyncState.Offline, is SyncState.Syncing -> Result.retry()
            is SyncState.Error -> if (state.fix == Fix.RetryLater) Result.retry() else Result.success()
            // Needs the person: retrying in the background cannot help. The engine's state says why.
            SyncState.NeedsKey -> Result.success()
        }
    }
}

/**
 * Schedules sync cycles with WorkManager: periodically, shortly after local changes, and when the
 * app comes to the foreground. All three share one queue, so cycles never overlap.
 *
 * @param requiresNetwork false for storage that needs no network, such as a local folder.
 */
public class SyncScheduler(context: Context, private val requiresNetwork: Boolean = true) {
    private val appContext = context.applicationContext
    private val work: WorkManager get() = WorkManager.getInstance(appContext)

    private val constraints: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()

    /** Keeps a background sync running about every [interval] (Android enforces at least 15 minutes). */
    public fun schedulePeriodic(interval: Duration = 1.hours) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(interval.inWholeMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * Asks for a sync soon. Call after local writes: repeated calls within [debounce] collapse
     * into one cycle, so a burst of edits costs one upload.
     */
    public fun requestSync(debounce: Duration = 10.seconds) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(debounce.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(ON_DEMAND, ExistingWorkPolicy.REPLACE, request)
    }

    /** Syncs each time the app comes to the foreground. Call once, from `Application.onCreate`. */
    public fun syncOnForeground() {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = requestSync(Duration.ZERO)
        }
        val register = { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        if (Looper.myLooper() == Looper.getMainLooper()) register() else Handler(Looper.getMainLooper()).post { register() }
    }

    /** Stops all scheduled sync, for when the person turns sync off. */
    public fun cancel() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(ON_DEMAND)
    }

    public companion object {
        public const val PERIODIC: String = "drivesync-periodic"
        public const val ON_DEMAND: String = "drivesync-on-demand"
    }
}

/** Whether the device currently has a default network, for showing "offline" before a sync is tried. */
public class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    public val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(manager.activeNetwork != null)
            }
        }
        trySend(manager.activeNetwork != null)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
