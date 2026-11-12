package io.github.pdrakeweb.drivesync.core

/** Why a change is being kept aside instead of merged. */
public sealed interface QuarantineReason {
    /** The table is not in this app version's registry. */
    public data object UnknownTable : QuarantineReason

    /** The app's [ChangeValidator] rejected it. Like every quarantined change, retried on every apply. */
    public data class Rejected(val reason: String) : QuarantineReason
}

public data class Quarantined(val change: Change, val reason: QuarantineReason)

/**
 * The merge metadata a local store keeps, as the merge engine sees it. A store implements this
 * over its own tables and calls [MergeEngine.apply] inside one transaction.
 */
public interface MergeState {
    /** Advance the store's clock past a timestamp seen from another replica. */
    public fun observe(hlc: Hlc)

    public fun tombstone(row: RowRef): Hlc?

    /** Record the tombstone and discard the row's cells. */
    public fun putTombstone(row: RowRef, hlc: Hlc)

    /** All stored cells of a row, including columns this app version does not know. */
    public fun cells(row: RowRef): Map<String, Cell>

    public fun putCell(row: RowRef, column: String, cell: Cell)

    public fun clearCells(row: RowRef)

    public fun quarantined(): Collection<Quarantined>

    /** One entry per change: quarantining a change again replaces its earlier entry. */
    public fun quarantine(item: Quarantined)

    public fun release(item: Quarantined)

    /**
     * Adds to the conflict log. Returns false, changing nothing, if this lost value (same row,
     * column and timestamp) is already in the log, so redelivery never duplicates an entry.
     */
    public fun recordConflict(conflict: Conflict): Boolean
}

/** What one [LocalStore.apply] call did, and what is still set aside afterwards. */
public data class ApplyReport(
    /** Changes that altered the stored state. */
    val applied: Int = 0,
    /** Changes that were already known or lost to a newer write or a tombstone. */
    val ignored: Int = 0,
    /** Changes currently quarantined, by table (totals, not just from this call). */
    val quarantined: Map<String, Int> = emptyMap(),
    /** Rows currently waiting for a parent that has not arrived. */
    val held: List<RowRef> = emptyList(),
    /** Rows whose parent was deleted; the app decides what to do with them. */
    val orphaned: List<RowRef> = emptyList(),
    /** Conflicts first recorded by this call (tables that opted in only). */
    val conflicts: List<Conflict> = emptyList(),
)

/**
 * The merge rules. Pure and deterministic: the stored result depends only on the set of changes
 * applied, never on their order or on how often each was delivered (invariant C-1).
 */
public object MergeEngine {
    /** Merges [changes] into [state]. The report's held and orphaned lists are left to the store. */
    public fun apply(state: MergeState, changes: List<Change>, registry: MergeRegistry): ApplyReport {
        var applied = 0
        var ignored = 0
        val found = ArrayList<Conflict>()
        // Everything set aside is tried again on every apply: an app upgrade may have taught the
        // registry a table, or relaxed the validator. What is still refused is quarantined again.
        val retry = state.quarantined().toList()
        retry.forEach(state::release)

        for (change in retry.map { it.change } + changes) {
            state.observe(change.hlc)
            val spec = registry.table(change.table)
            if (spec == null) {
                state.quarantine(Quarantined(change, QuarantineReason.UnknownTable))
                continue
            }
            val rejection = registry.validator?.validate(change)
            if (rejection != null) {
                state.quarantine(Quarantined(change, QuarantineReason.Rejected(rejection)))
                continue
            }
            if (mergeOne(state, change, spec, found)) applied++ else ignored++
        }
        val totals = state.quarantined().groupingBy { it.change.table }.eachCount()
        return ApplyReport(applied = applied, ignored = ignored, quarantined = totals, conflicts = found)
    }

    /**
     * Records a conflict when the author of [kept] cannot have seen [lost]: the value it replaced
     * ([Cell.prev]) is older than [lost], or there was none. A knowing overwrite, a redelivered
     * change and an older ancestor all fail that test, so none of them is reported.
     */
    private fun noteConflict(state: MergeState, spec: TableSpec, row: RowRef, column: String, kept: Cell, lost: Cell, found: MutableList<Conflict>) {
        if (!spec.recordConflicts || kept.hlc.replica == lost.hlc.replica || kept.value == lost.value) return
        val seenByAuthor = kept.prev
        if (seenByAuthor != null && seenByAuthor >= lost.hlc) return
        val conflict = Conflict(row, column, kept, lost)
        if (state.recordConflict(conflict)) found += conflict
    }

    private fun mergeOne(state: MergeState, change: Change, spec: TableSpec, found: MutableList<Conflict>): Boolean {
        val row = change.row
        val tombstone = state.tombstone(row)
        if (change.isTombstone) {
            // Keeping the highest tombstone timestamp makes the stored metadata itself converge.
            if (tombstone != null && tombstone >= change.hlc) return false
            state.putTombstone(row, change.hlc)
            return true
        }
        // Deletes win: a tombstone beats any edit, earlier, later or concurrent.
        if (tombstone != null) return false

        val column = change.column!!
        val cell = Cell(change.value!!, change.hlc, change.prev)
        val cells = state.cells(row)
        val current = cells[column]
        return when (spec.mergeClass) {
            MergeClass.LWW_FIELDS ->
                if (current == null || change.hlc > current.hlc) {
                    if (current != null) noteConflict(state, spec, row, column, kept = cell, lost = current, found)
                    state.putCell(row, column, cell)
                    true
                } else {
                    if (change.hlc < current.hlc) noteConflict(state, spec, row, column, kept = current, lost = cell, found)
                    false
                }

            // An insert stamps all its columns with one timestamp. The row is the columns of the
            // earliest insert seen, whole: a later write to the same id, from any device, is not an
            // edit we accept, and two inserts are never mixed.
            MergeClass.APPEND_ONLY -> {
                val rowClock = cells.values.minOfOrNull { it.hlc }
                when {
                    rowClock == null || change.hlc < rowClock -> {
                        state.clearCells(row)
                        state.putCell(row, column, cell)
                        true
                    }
                    change.hlc == rowClock && current == null -> {
                        state.putCell(row, column, cell)
                        true
                    }
                    else -> false
                }
            }

            // All columns of one whole-row write share a timestamp. The row is the set of columns
            // carrying the highest timestamp seen.
            MergeClass.LWW_ROW -> {
                val rowClock = cells.values.maxOfOrNull { it.hlc }
                when {
                    rowClock == null || change.hlc > rowClock -> {
                        state.clearCells(row)
                        state.putCell(row, column, cell)
                        true
                    }
                    change.hlc == rowClock && current == null -> {
                        state.putCell(row, column, cell)
                        true
                    }
                    else -> false
                }
            }
        }
    }
}

/** Whether a row can be shown to the app, given its parents (DESIGN.md section 5, missing parents). */
public enum class RowStatus {
    ACTIVE,

    /** A parent has not arrived yet (or is itself held). */
    HELD,

    /** A parent was deleted. */
    ORPHANED,
}

/** Derives [RowStatus] from merged state alone, so every replica reaches the same answer. */
public class RowStatusResolver(private val registry: MergeRegistry, private val state: MergeState) {
    public fun status(row: RowRef): RowStatus = status(row, HashSet())

    private fun status(row: RowRef, visiting: MutableSet<RowRef>): RowStatus {
        val spec = registry.table(row.table) ?: return RowStatus.ACTIVE
        if (spec.parents.isEmpty() || !visiting.add(row)) return RowStatus.ACTIVE
        val cells = state.cells(row)
        var result = RowStatus.ACTIVE
        for (ref in spec.parents) {
            val parentId = when (val v = cells[ref.column]?.value) {
                is Value.Text -> v.value
                is Value.Integer -> v.value.toString()
                else -> continue // no parent set
            }
            val parent = RowRef(ref.table, parentId)
            when {
                state.tombstone(parent) != null -> return RowStatus.ORPHANED
                state.cells(parent).isEmpty() -> result = RowStatus.HELD
                else -> when (status(parent, visiting)) {
                    RowStatus.ORPHANED -> return RowStatus.ORPHANED
                    RowStatus.HELD -> result = RowStatus.HELD
                    RowStatus.ACTIVE -> Unit
                }
            }
        }
        return result
    }
}
