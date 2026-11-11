# ADR-0002 — Write our own clock and merge core; do not adopt Synk

**Status:** proposed (D-008). Measured 11 Nov 2026. Not a close call.

**Context.** D-002 asked for a spike of Synk (`CharlieTap/synk`) as the clock/merge core before
writing one. The spike (`spikes/s0-merge-core/`, a standalone Gradle build so JitPack never enters
the library build) runs one candidate-neutral property harness against both:

- **Synk 0.38** from JitPack, through its public API (`outbound` / `inbound`), with an adapter that
  gives every column its own clock and uses the soft-delete column its README recommends.
- **Own core**: a hybrid logical clock with an injectable wall clock, field-level last-writer-wins,
  and a tombstone set. About 70 lines, no dependencies (`OwnCore.kt`).

Each seeded run has 2–4 replicas making random edits, inserts and deletes with partial, duplicated
and reordered delivery, followed by a full exchange. Six properties are checked: convergence,
idempotency, commutativity (two fresh observers fed in different orders), a deleted row never
visible again, an append-only row never lost, and a causally-last write wins (the last writer of a
field had already seen every other write to it).

Reproduce: `./gradlew -p spikes/s0-merge-core test` (10,000 seeds per suite; `-Pseeds=N` to
change). Raw output: `spikes/s0-merge-core/RESULTS-2026-11-11.txt`.

## Measured results

Runs with at least one violation, out of 10,000 per row:

| Candidate | Scenario | Converge | Idempotent | Commute | Deleted stays deleted | Append-only kept | Causally-last wins |
|---|---|---|---|---|---|---|---|
| Own core | rows created on one device, no skew | 0 | 0 | 0 | 0 | 0 | 0 |
| Own core | same row id created on several devices, no skew | 0 | 0 | 0 | 0 | 0 | 0 |
| Own core | both of the above with clock skew of ±3 days | 0 | 0 | 0 | 0 | 0 | 0 |
| Synk | rows created on one device, fixed columns, no skew | 0 | 0 | 0 | 0 | 0 | 0 |
| Synk | same row id created on several devices, no skew | 0 | 0 | 0 | **1,370** | 0 | **3,731** |
| Synk | a column that appears after the row was created | **8,890** | 0 | **5,540** | **9,559** | 0 | **8,539** |
| Synk | clock skew | cannot be run: the wall clock is not injectable (see 2) | | | | | |

Synk's counts move by a few tens between runs because it reads the real system clock; the own
core's runs are bit-for-bit repeatable from the seed.

Other measurements:

| | Own core | Synk 0.38 |
|---|---|---|
| Wire size, one-column edit of a ten-column row | 82 B | 1,349 B (the whole row plus a clock per column) |
| Emit + merge of that edit | 0.4 µs | 53 µs |
| Runtime dependencies added to `:core` | none beyond the Kotlin standard library | 15 more jars, 5.9 MB, including `kotlin-reflect` 1.8.10 (3 MB), okio, atomicfu, kotlinx-datetime 0.4.0 |
| With kotlinx-datetime 0.8.0 on the classpath (current stable) | unaffected | every call fails: `NoClassDefFoundError: kotlinx/datetime/Clock$System` |
| Upstream | — | last commit 22 Nov 2023 (Kotlin 1.9.20); no Maven Central release, JitPack only |

## Why Synk fails, in its own code

1. **Creating a row stamps every column, including the delete flag.** `meta()` gives all encoded
   keys the creation clock. When a row id is created independently on two devices (a settings row,
   seeded defaults, any deterministic id), the later creation overwrites columns the other device
   edited and clears its soft delete. That breaks C-1 twice: a deleted row comes back, and an edit
   is lost.
2. **Clock skew loses edits and leaves replicas permanently different.** `remoteTock` rejects a
   peer clock more than 60 seconds ahead, and `inbound`/`outbound` swallow the error
   (`getOr(atomicHlc)`). The local clock stays behind, `outbound` then overwrites the column's
   stored clock with a *lower* one, and the peer discards the edit. Test
   `synk - a later edit loses to a peer whose clock is ahead and replicas diverge` shows two
   replicas that have seen the same two changes holding different values. With immutable batches
   applied once, nothing would ever repair it. The wall clock is a hard-wired `Clock.System`
   default argument, so the simulator's ±days skew runs (ARCHITECTURE §5) cannot be driven through
   Synk at all.
3. **Columns added later are dropped.** `outbound` copies clocks only for keys already in the
   stored metadata, so a column that first appears in an update has no clock and is silently
   removed by the receiver's merge. DESIGN §5 requires unknown columns to be kept and re-emitted.
4. **Shape mismatch.** Synk is state-based per object: every message carries the whole row. Our
   wire format is field-level changes in batches, with row tombstones, merge classes per table,
   held-back children, quarantine and a conflict log. None of that exists in Synk; all of it
   would be written around it, and items 1–3 would need a fork anyway.

## Decision

Write the clock and merge core in `:core`. Borrow from Synk only the idea already in DESIGN §4
(per-field hybrid logical clocks); the batch format and delete rules continue to follow
`google-drive-sync` as credited in DESIGN §3.

Two rules the spike showed are load-bearing, carried into S1 with test vectors:

- **A remote clock is always absorbed, never rejected.** A device with a fast clock drags
  everyone's logical clock forward; ordering then no longer matches real time, but nothing is lost
  and replicas converge. A large skew is reported to the app as a warning state (C-3) rather than
  enforced by dropping changes.
- **A change stamps only the columns it writes.** Creating a row is just writing some columns;
  there is no whole-row stamp for field-merged tables, and a tombstone wins regardless of clock.

## Consequences

- We own roughly a few hundred lines of clock and merge code and its proofs. The property harness
  from this spike becomes the seed of the `:testing` simulator in S1.
- `:core` keeps no third-party runtime dependencies beyond kotlinx (coroutines, serialization).
- The spike directory stays in the repository as the evidence for this ADR. It is not part of the
  root build and nothing depends on it.
