package io.github.pdrakeweb.drivesync.testing

import io.github.pdrakeweb.drivesync.core.BlobInfo
import io.github.pdrakeweb.drivesync.core.BlobStore
import io.github.pdrakeweb.drivesync.core.Page
import io.github.pdrakeweb.drivesync.core.PutResult
import java.util.TreeMap

/** A [BlobStore] held in memory. Share one instance between replicas to stand in for the cloud. */
public class InMemoryBlobStore(private val pageSize: Int = 100) : BlobStore {
    private val objects = TreeMap<String, ByteArray>()

    /** Every stored path, for assertions. */
    public fun paths(): List<String> = synchronized(objects) { objects.keys.toList() }

    /** The stored bytes exactly as a storage provider would see them. */
    public fun raw(path: String): ByteArray? = synchronized(objects) { objects[path]?.copyOf() }

    /** Overwrites an object regardless of ownership, to simulate tampering. */
    public fun tamper(path: String, bytes: ByteArray): Unit = synchronized(objects) { objects[path] = bytes.copyOf() }

    override suspend fun list(prefix: String, pageToken: String?): Page<BlobInfo> = synchronized(objects) {
        val matching = objects.tailMap(prefix, true).entries
            .asSequence()
            .takeWhile { it.key.startsWith(prefix) }
            .filter { pageToken == null || it.key > pageToken }
            .take(pageSize + 1)
            .map { BlobInfo(it.key, it.value.size.toLong()) }
            .toList()
        val items = matching.take(pageSize)
        Page(items, if (matching.size > pageSize) items.last().path else null)
    }

    override suspend fun get(path: String): ByteArray? = raw(path)

    override suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult = synchronized(objects) {
        if (objects.containsKey(path)) {
            PutResult.AlreadyExists
        } else {
            objects[path] = bytes.copyOf()
            PutResult.Created
        }
    }

    override suspend fun putOwned(path: String, bytes: ByteArray): Unit =
        synchronized(objects) { objects[path] = bytes.copyOf() }

    override suspend fun delete(path: String): Unit = synchronized(objects) { objects.remove(path) }
}

public enum class BlobOp { List, Get, PutIfAbsent, PutOwned, Delete }

/** Decides, per storage call, whether to inject a failure. All methods default to "no fault". */
public interface FaultPlan {
    /** Throw here to fail the call before it reaches storage. */
    public fun before(op: BlobOp, path: String) {}

    /** Throw here to fail after storage already performed the call (a lost response, a crash). */
    public fun after(op: BlobOp, path: String) {}

    /** Return true to leave [path] out of a listing, as a lagging listing would. */
    public fun hiddenFromListing(path: String): Boolean = false

    public companion object {
        public val None: FaultPlan = object : FaultPlan {}
    }
}

/** One replica's view of a shared [BlobStore], with faults injected according to [plan]. */
public class FaultyBlobStore(private val delegate: BlobStore, public var plan: FaultPlan = FaultPlan.None) : BlobStore {
    override suspend fun list(prefix: String, pageToken: String?): Page<BlobInfo> {
        plan.before(BlobOp.List, prefix)
        val page = delegate.list(prefix, pageToken)
        plan.after(BlobOp.List, prefix)
        return Page(page.items.filterNot { plan.hiddenFromListing(it.path) }, page.nextPageToken)
    }

    override suspend fun get(path: String): ByteArray? {
        plan.before(BlobOp.Get, path)
        return delegate.get(path).also { plan.after(BlobOp.Get, path) }
    }

    override suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult {
        plan.before(BlobOp.PutIfAbsent, path)
        return delegate.putIfAbsent(path, bytes).also { plan.after(BlobOp.PutIfAbsent, path) }
    }

    override suspend fun putOwned(path: String, bytes: ByteArray) {
        plan.before(BlobOp.PutOwned, path)
        delegate.putOwned(path, bytes)
        plan.after(BlobOp.PutOwned, path)
    }

    override suspend fun delete(path: String) {
        plan.before(BlobOp.Delete, path)
        delegate.delete(path)
        plan.after(BlobOp.Delete, path)
    }
}
