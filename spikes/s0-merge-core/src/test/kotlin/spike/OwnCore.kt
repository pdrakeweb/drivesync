package spike

/** Candidate B: the smallest own clock and merge core that fits DESIGN.md sections 4-5. No dependencies. */
data class Hlc(val wall: Long, val counter: Int, val replica: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, Hlc::wall, Hlc::counter, Hlc::replica)
}

class HlcClock(private val replica: String, private val wallClock: () -> Long) {
    private var last = Hlc(0, 0, replica)

    /** Local event: strictly greater than anything this replica has issued or observed. */
    fun tick(): Hlc {
        val now = wallClock()
        last = if (now > last.wall) Hlc(now, 0, replica) else Hlc(last.wall, last.counter + 1, replica)
        return last
    }

    /** Remote event: always absorbed, however far ahead the peer's clock is (no drift rejection). */
    fun observe(remote: Hlc) {
        if (remote.wall > last.wall || (remote.wall == last.wall && remote.counter > last.counter)) {
            last = Hlc(remote.wall, remote.counter, replica)
        }
    }
}

/** column == null is a row tombstone. */
data class Change(val key: RowKey, val column: String?, val value: String?, val hlc: Hlc)

class OwnReplica(override val id: String, wallClock: () -> Long) : Replica {
    private val clock = HlcClock(id, wallClock)
    private val cells = HashMap<RowKey, HashMap<String, Pair<Hlc, String>>>()
    private val tombstones = HashSet<RowKey>()

    fun apply(change: Change) {
        clock.observe(change.hlc)
        if (change.column == null) {
            tombstones += change.key // deletes win, whatever their clock
            cells.remove(change.key)
            return
        }
        if (change.key in tombstones) return
        val row = cells.getOrPut(change.key) { HashMap() }
        val current = row[change.column]
        if (current == null || change.hlc > current.first) row[change.column] = change.hlc to change.value!!
    }

    private fun emit(changes: List<Change>): Msg {
        changes.forEach(::apply)
        return Msg(id, changes, changes.sumOf { wireSize(it) })
    }

    override fun write(key: RowKey, fields: Map<String, String>): Msg =
        emit(fields.map { (col, v) -> Change(key, col, v, clock.tick()) })

    override fun delete(key: RowKey): Msg = emit(listOf(Change(key, null, null, clock.tick())))

    @Suppress("UNCHECKED_CAST")
    override fun receive(msg: Msg) = (msg.payload as List<Change>).forEach(::apply)

    override fun visible(): Map<RowKey, Map<String, String>> =
        cells.mapValues { (_, row) -> row.mapValues { it.value.second } }

    // Uncompressed text estimate comparable with Synk's JSON message:
    // table|row|col|value|wall:counter:replica
    private fun wireSize(c: Change) =
        c.key.table.length + c.key.id.length + (c.column?.length ?: 0) + (c.value?.length ?: 0) +
            15 + 5 + c.hlc.replica.length + 6
}

object OwnCandidate : Candidate {
    override val name = "own-core"
    override fun replica(id: String, wallClock: () -> Long): Replica = OwnReplica(id, wallClock)
}
