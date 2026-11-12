package io.github.pdrakeweb.drivesync.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A column value, in SQLite's storage classes. */
@Serializable
public sealed interface Value {
    @Serializable
    @SerialName("null")
    public data object Null : Value

    @Serializable
    @SerialName("int")
    public data class Integer(val value: Long) : Value

    @Serializable
    @SerialName("real")
    public data class Real(val value: Double) : Value

    @Serializable
    @SerialName("text")
    public data class Text(val value: String) : Value

    @Serializable
    @SerialName("bytes")
    public class Bytes(public val value: ByteArray) : Value {
        override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): String = "Bytes(${value.size})"
    }
}

/** Identifies one row. */
@Serializable
public data class RowRef(val table: String, val rowId: String) {
    override fun toString(): String = "$table/$rowId"
}

/**
 * One field-level write, or a row tombstone when [column] is null.
 * Creating a row is just writing some of its columns; there is no separate insert.
 */
@Serializable
public data class Change(
    val table: String,
    val rowId: String,
    val column: String?,
    val value: Value?,
    val hlc: Hlc,
    /**
     * For a field write: the timestamp of the value this write replaced on the device that made it,
     * null if there was none. It is what tells a knowing overwrite from a concurrent edit.
     */
    val prev: Hlc? = null,
) {
    init {
        require((column == null) == (value == null)) { "a tombstone has neither column nor value" }
    }

    val isTombstone: Boolean get() = column == null
    val row: RowRef get() = RowRef(table, rowId)

    public companion object {
        public fun tombstone(table: String, rowId: String, hlc: Hlc): Change =
            Change(table, rowId, null, null, hlc)
    }
}

/** A stored column value with the timestamp of the write that set it. */
public data class Cell(val value: Value, val hlc: Hlc, val prev: Hlc? = null)

/**
 * Two devices edited the same column without either having seen the other's edit. [kept] is the
 * value that remains everywhere; [lost] is the one it replaced, kept here so an app can show it.
 */
public data class Conflict(val row: RowRef, val column: String, val kept: Cell, val lost: Cell)
