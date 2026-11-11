# ADR-0001 — Every cloud file has exactly one writer

**Status:** proposed (D-004).

**Context.** Cloud storage like Google Drive offers weak or inconsistently documented
compare-and-swap. Property Manager relied on `If-Match` with ETags, and sent it on a file create,
where it cannot detect a conflict; two devices writing one shared index file is last-write-wins
by accident.

**Decision.** No file in the sync folder is written by more than one replica. Each replica owns
its manifest, batches and snapshots. The only shared file, `meta.json`, is created once with
create-if-absent and changed only during an explicit re-key, which takes a user action on one
device.

**Consequences.** Correctness rests on the merge rules, not on the provider. Listing every
replica's manifest costs one call per replica per sync (fine for one person's handful of
devices). Deleting old batches is done only by their owner, so a retired device's files linger
until it returns or the person clears it from the device list.
