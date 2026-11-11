package io.github.pdrakeweb.drivesync.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.pdrakeweb.drivesync.core.KeyState
import io.github.pdrakeweb.drivesync.core.MergeClass
import io.github.pdrakeweb.drivesync.core.MergeRegistry
import io.github.pdrakeweb.drivesync.core.ReplicaId
import io.github.pdrakeweb.drivesync.core.SyncConfig
import io.github.pdrakeweb.drivesync.core.SyncEngine
import io.github.pdrakeweb.drivesync.core.SyncError
import io.github.pdrakeweb.drivesync.core.SyncException
import io.github.pdrakeweb.drivesync.core.TableSpec
import io.github.pdrakeweb.drivesync.core.Value
import io.github.pdrakeweb.drivesync.testing.BlobOp
import io.github.pdrakeweb.drivesync.testing.FaultPlan
import io.github.pdrakeweb.drivesync.testing.FaultyBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryBlobStore
import io.github.pdrakeweb.drivesync.testing.InMemoryLocalStore
import io.github.pdrakeweb.drivesync.testing.InsecureTestKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val registry = MergeRegistry(1, listOf(TableSpec("notes", MergeClass.LWW_FIELDS)))
    private val id = ReplicaId("a")
    private val store = InMemoryLocalStore(id, registry) { 1_800_000_000_000 }
    private val cloud = InMemoryBlobStore()
    private val blobs = FaultyBlobStore(cloud)
    private val keys = InsecureTestKeyManager()
    private val engine = SyncEngine(id, store, blobs, keys, registry, SyncConfig("a"))

    @Before
    fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        DriveSyncWork.provider = null
    }

    private fun runWorker(): Result = runBlocking { TestListenableWorkerBuilder<DriveSyncWorker>(context).build().doWork() }

    private fun failWith(error: SyncError) {
        blobs.plan = object : FaultPlan {
            override fun before(op: BlobOp, path: String): Unit = throw SyncException(error)
        }
    }

    @Test
    fun `the worker runs a cycle and pushes pending changes`() {
        DriveSyncWork.setEngineProvider { engine }
        store.write("notes", "n1", mapOf("title" to Value.Text("t")))
        assertEquals(Result.success(), runWorker())
        assertEquals(0, engine.pending.value)
        assertEquals(1, cloud.paths().count { it.startsWith("batches/a/") })
    }

    @Test
    fun `transient failures are retried, failures that need the person are not`() {
        DriveSyncWork.setEngineProvider { engine }
        failWith(SyncError.NetworkUnavailable)
        assertEquals(Result.retry(), runWorker())
        failWith(SyncError.StorageUnavailable("503"))
        assertEquals(Result.retry(), runWorker())
        failWith(SyncError.AuthExpired)
        assertEquals("retrying cannot sign the person in", Result.success(), runWorker())
        blobs.plan = FaultPlan.None
        keys.keyState = KeyState.NeedsPassphrase
        assertEquals(Result.success(), runWorker())
    }

    @Test
    fun `a missing provider fails visibly, sync not set up is a quiet success`() {
        assertEquals(Result.failure(), runWorker())
        DriveSyncWork.setEngineProvider { null }
        assertEquals(Result.success(), runWorker())
    }

    private fun info(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    @Test
    fun `periodic and on-demand work are unique, constrained to a network, and cancellable`() {
        val scheduler = SyncScheduler(context)
        scheduler.schedulePeriodic(5.minutes)
        scheduler.schedulePeriodic()
        scheduler.requestSync()
        scheduler.requestSync()
        scheduler.requestSync()

        val periodic = info(SyncScheduler.PERIODIC).single()
        assertEquals(WorkInfo.State.ENQUEUED, periodic.state)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        assertEquals("a burst of requests is one pending cycle", 1, info(SyncScheduler.ON_DEMAND).count { !it.state.isFinished })

        scheduler.cancel()
        assertEquals(0, (info(SyncScheduler.PERIODIC) + info(SyncScheduler.ON_DEMAND)).count { !it.state.isFinished })
    }

    @Test
    fun `local-folder storage needs no network`() {
        SyncScheduler(context, requiresNetwork = false).requestSync()
        val pending = info(SyncScheduler.ON_DEMAND).single { !it.state.isFinished }
        assertEquals(NetworkType.NOT_REQUIRED, pending.constraints.requiredNetworkType)
    }
}
