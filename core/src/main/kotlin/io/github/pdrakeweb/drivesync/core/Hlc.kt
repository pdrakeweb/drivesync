package io.github.pdrakeweb.drivesync.core

import kotlinx.serialization.Serializable

/** Stable random identity of one install on one device. Safe to use in a storage path. */
@Serializable
@JvmInline
public value class ReplicaId(public val value: String) {
    init {
        require(PATTERN.matches(value)) { "ReplicaId must match ${PATTERN.pattern}" }
    }

    override fun toString(): String = value

    private companion object {
        val PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * Hybrid logical clock timestamp. [time] packs wall-clock milliseconds (upper 48 bits) and a
 * counter (lower 16 bits), so comparing [time] compares (wall, counter). Ties between replicas are
 * broken by [replica], which makes the order total: two different changes never share an Hlc
 * unless one replica deliberately stamped them together (a whole-row write).
 */
@Serializable
public data class Hlc(val time: Long, val replica: ReplicaId) : Comparable<Hlc> {
    val wallMillis: Long get() = time ushr COUNTER_BITS
    val counter: Int get() = (time and COUNTER_MASK).toInt()

    override fun compareTo(other: Hlc): Int {
        val byTime = time.compareTo(other.time)
        return if (byTime != 0) byTime else replica.value.compareTo(other.replica.value)
    }

    /** `<wall millis>:<counter>:<replica>`, the form used in test vectors. */
    override fun toString(): String = "$wallMillis:$counter:${replica.value}"

    public companion object {
        internal const val COUNTER_BITS: Int = 16
        internal const val COUNTER_MASK: Long = 0xFFFF
        internal const val MAX_WALL: Long = (1L shl 47) - 1

        public fun of(wallMillis: Long, counter: Int, replica: ReplicaId): Hlc {
            require(wallMillis in 0..MAX_WALL) { "wallMillis out of range" }
            require(counter in 0..COUNTER_MASK.toInt()) { "counter out of range" }
            return Hlc((wallMillis shl COUNTER_BITS) or counter.toLong(), replica)
        }

        public fun parse(text: String): Hlc {
            val parts = text.split(':', limit = 3)
            require(parts.size == 3) { "Hlc must be <wall>:<counter>:<replica>" }
            return of(parts[0].toLong(), parts[1].toInt(), ReplicaId(parts[2]))
        }
    }
}

/**
 * Issues timestamps for one replica.
 *
 * A remote timestamp is always absorbed, however far ahead the peer's wall clock is. Rejecting it
 * would let a causally later local edit carry a lower timestamp and be discarded elsewhere
 * (ADR-0002). The cost: after meeting a fast device, order no longer follows real time.
 *
 * Not thread-safe; the owning local store serialises access and persists [last].
 */
public class HlcClock(
    private val replica: ReplicaId,
    private val wallClock: () -> Long,
    last: Long = 0,
) {
    /** Packed time of the latest timestamp issued or observed. Persist it with the data. */
    public var last: Long = last
        private set

    /** A timestamp strictly greater than every one this replica has issued or observed. */
    public fun tick(): Hlc {
        val now = wallClock().coerceIn(0, Hlc.MAX_WALL) shl Hlc.COUNTER_BITS
        // When the counter is exhausted the increment carries into the wall bits, which keeps
        // the clock monotonic without failing.
        last = if (now > last) now else last + 1
        return Hlc(last, replica)
    }

    public fun observe(remote: Hlc) {
        if (remote.time > last) last = remote.time
    }
}
