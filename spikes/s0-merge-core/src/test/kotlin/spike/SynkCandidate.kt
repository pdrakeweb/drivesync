package spike

import com.tap.synk.Synk
import com.tap.synk.adapter.SynkAdapter
import com.tap.synk.config.ClockStorageConfiguration
import com.tap.synk.inbound
import com.tap.synk.outbound
import com.tap.synk.relay.Message
import com.tap.synk.relay.encodeAsString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Candidate A: Synk 0.38 from JitPack, adapted as favourably as its public API allows.
 * Each column gets its own HLC (field-level LWW). Deletion is the soft-delete column Synk's README
 * recommends; it is only ever set, never cleared.
 *
 * Two adapters. fixedSchema = true encodes every column of the table on every row (unset = ""),
 * which is how Synk is meant to be used. fixedSchema = false encodes only the columns a row has,
 * which shows what happens to a column that appears after the row was created (schema evolution).
 */
data class SynkRow(val table: String, val id: String, val fields: Map<String, String>)

private const val F_TABLE = "_table"
private const val F_ID = "_id"
private const val F_DELETED = "_deleted"
private val SCHEMA = mapOf(NOTES to listOf("c0", "c1", "c2"), LOG to listOf("body"))

class SynkRowAdapter(private val fixedSchema: Boolean) : SynkAdapter<SynkRow> {
    override fun resolveId(crdt: SynkRow) = crdt.table + "/" + crdt.id

    override fun encode(crdt: SynkRow): Map<String, String> {
        val base = if (fixedSchema) {
            SCHEMA.getValue(crdt.table).associateWith { "" } + (F_DELETED to "0")
        } else {
            emptyMap()
        }
        return base + crdt.fields + mapOf(F_TABLE to crdt.table, F_ID to crdt.id)
    }

    override fun decode(map: Map<String, String>) =
        SynkRow(map.getValue(F_TABLE), map.getValue(F_ID), map - F_TABLE - F_ID)
}

class SynkReplica(override val id: String, fixedSchema: Boolean = true) : Replica {
    private val adapter = SynkRowAdapter(fixedSchema)

    // Synk reads Clock.System internally; no parameter lets a caller inject a wall clock.
    private val synk = Synk.Builder(
        object : ClockStorageConfiguration {
            override val fileSystem: FileSystem = FakeFileSystem()
            override val filePath: Path = "/synk-$id".toPath()
            override val clockFileName = "clock.hlc"
        },
    ).registerSynkAdapter(adapter).build()
    private val rows = HashMap<String, SynkRow>()

    private fun local(new: SynkRow): Msg {
        val key = adapter.resolveId(new)
        val message = synk.outbound(new, rows[key])
        rows[key] = new
        return Msg(id, message, message.encodeAsString(adapter).length)
    }

    override fun write(key: RowKey, fields: Map<String, String>): Msg {
        val old = rows[key.table + "/" + key.id]
        return local(SynkRow(key.table, key.id, (old?.fields ?: emptyMap()) + fields))
    }

    override fun delete(key: RowKey): Msg {
        val old = rows[key.table + "/" + key.id]
        return local(SynkRow(key.table, key.id, (old?.fields ?: emptyMap()) + (F_DELETED to "1")))
    }

    @Suppress("UNCHECKED_CAST")
    override fun receive(msg: Msg) {
        val message = msg.payload as Message<SynkRow>
        val key = adapter.resolveId(message.crdt)
        rows[key] = synk.inbound(message, rows[key])
    }

    override fun visible(): Map<RowKey, Map<String, String>> =
        rows.values.filter { it.fields[F_DELETED] != "1" }.associate {
            RowKey(it.table, it.id) to (it.fields - F_DELETED).filterValues { v -> v.isNotEmpty() }
        }

    override fun close() = synk.finish()
}

class SynkCandidate(private val fixedSchema: Boolean) : Candidate {
    override val name = if (fixedSchema) "synk-0.38" else "synk-0.38-sparse-rows"
    override fun replica(id: String, wallClock: () -> Long): Replica = SynkReplica(id, fixedSchema)
}
