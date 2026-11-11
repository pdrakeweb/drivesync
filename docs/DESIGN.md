# drivesync — design

Local-first, multi-device sync for Android apps through storage the user owns (Google Drive
first). No server to run, end-to-end encrypted, and deterministic when two devices edit offline.

Name chosen by Pete (D-006). The Maven namespace is decided before 0.1.0.

## 1. The problem

An app that keeps a person's data on their phone, and wants the same data on their tablet,
usually has three choices:

1. run a backend (cost, accounts, the developer holds the person's data);
2. back up and restore whole files (one device wins, edits made elsewhere are lost);
3. write its own merge logic on top of cloud storage (hard to get right; most attempts silently
   lose data in the cases nobody tested).

`drivesync` is option 3 done once, carefully, so apps don't have to. It is built for one person's
data across that person's devices, not for collaboration between people.

## 2. Goals and non-goals

**Goals**
- Every device works fully offline; sync is opportunistic and never blocks the app.
- **Deterministic convergence:** devices that have seen the same changes hold the same data,
  whatever order the changes arrived in, however often a change is re-delivered.
- **End-to-end encryption:** the storage provider sees only ciphertext and meaningless file names.
- **No overwrites in shared storage:** every file in the cloud is written by exactly one device and
  never rewritten by another, so the library never depends on the storage provider's conditional
  writes (§6).
- Pluggable storage (Google Drive app-data folder first; local folder for tests; WebDAV and
  S3-compatible later) and pluggable local stores (Room/SQLite first).
- A core in pure Kotlin (no Android types) so it can become Kotlin Multiplatform.
- Usable as a dependency, and documented well enough to copy as a pattern without one.

**Non-goals** — multi-user sharing or collaboration; real-time sync (minutes is fine); server-side
queries; large media libraries (attachments work, but aren't optimized); human-readable files in
the user's Drive (that's a different product — see §3, Property Manager).

## 3. Prior art (surveyed 11 Nov 2026)

| Project | What it gets right | Why it isn't the answer | Borrow |
|---|---|---|---|
| **Synk** (Kotlin Multiplatform, MIT/Apache) | State-based CRDT metadata with hybrid logical clocks; storage and transport are left to you | No tagged releases (JitPack only), change detection unfinished | Spike as the clock/merge core (S0) |
| **google-drive-sync** (TypeScript, MIT, v0.1.0) | SQLite ↔ Drive `appDataFolder` through immutable, deterministically named change batches; field-level merge by logical clock with device id as tie-break; deletes win; children of missing parents held back | Wrong language, two commits, no compaction or encryption | Batch format, idempotent naming, merge and delete rules — credited |
| **Automerge** (Android bindings) | Mature document CRDT | Merges JSON-like documents, not rows; heavy native dependency | — |
| **cr-sqlite** | SQLite extension with CRDT tables | No commits in about 1.5–2 years | — |
| **PowerSync, ElectricSQL, Firebase** | Mature sync | Need a server the developer operates | — |
| **Property Manager** (Pete's app, TypeScript) | Drive as the only persistence layer with no backend; an offline upload queue; a sync-state per record (`local_only / pending_upload / synced / conflict`); a "keep both" conflict copy (`_v2_`) when edits overlap; a dev-mode local adapter standing in for Drive | Files are human-readable Markdown in the user's own folders (a different goal); conflict detection compares wall-clock-free field values but treats "both non-null and different" as conflict, so concurrent edits to the same field always become copies; errors are swallowed and records stay pending forever without telling anyone; the `If-Match` header is sent on a file *create*, where it can't detect anything | The per-record sync state shown to the user; a local-folder backend for development; the honest lesson that **conditional writes on shared files are fragile** — so `drivesync` never shares a writable file between devices |

Sources: <https://github.com/CharlieTap/synk> · <https://github.com/hamadawan/google-drive-sync> ·
<https://github.com/automerge/automerge-java> · <https://repopilot.app/r/vlcn-io/cr-sqlite> ·
<https://github.com/pdrakeweb/property-manager> (`src/lib/syncEngine.ts`, `driveClient.ts`,
`offlineQueue.ts`, `planning/ADRs.md` ADR-002).

## 4. Concepts

| Term | Meaning |
|---|---|
| **Replica** | One install on one device, with a stable random `replicaId` |
| **HLC** | Hybrid logical clock timestamp (wall time, counter, replicaId) — a total order across devices that tolerates clocks that disagree |
| **Change** | One field-level write `(table, rowId, column, value, hlc)`, or a row tombstone `(table, rowId, DELETED, hlc)` |
| **Batch** | An immutable, encrypted bundle of one replica's changes, with a sequence number |
| **Cursor** | For each other replica, the highest batch this replica has applied |
| **Snapshot** | A compacted full state, so new devices don't replay all history |
| **Merge class** | Per table: `LWW_FIELDS` (each column resolves independently), `APPEND_ONLY` (insert and tombstone only), `LWW_ROW` (whole row replaced) |

## 5. Merge rules

- **LWW_FIELDS:** each column takes the value with the highest HLC. Ties can't happen (HLCs include
  the replicaId). Values that must change together (an ordered list, a structured blob) live in one
  column.
- **APPEND_ONLY:** rows are inserted once; the only later change is a tombstone. Inserts from
  different devices both survive.
- **Deletes win:** a tombstone beats any edit to that row, earlier or concurrent. Restoring creates
  a new row with a new id. Ids are never reused.
- **Missing parents:** a child whose parent hasn't arrived yet is held back; if the parent is
  deleted, the child is reported to the app as orphaned and the app decides.
- **Validation hook:** the app may reject an incoming change; rejected changes are quarantined and
  reported, never silently dropped.
- **Schema versions:** every batch carries the app's schema version. Unknown columns are kept and
  re-emitted; unknown tables are quarantined until the app upgrades; a replica never writes with a
  schema older than one it has already applied.
- **Conflicts the app wants to see:** optional per table — when two devices edit the same column
  concurrently, the losing value is recorded in a conflict log the app can show (Property Manager's
  "keep both" idea, without creating duplicate records).

## 6. Storage layout

```
drivesync/v1/
  meta.json                              format version, created, key-derivation params, wrapped data key
  replicas/<replicaId>.json              written only by that replica: label, platform, last seen, last batch, cursors
  batches/<replicaId>/<seq>-<hash>.bin   immutable encrypted batches
  snapshots/<replicaId>-<hlc>.bin        encrypted compacted state
  attachments/<sha256>.bin               encrypted, content-addressed
```

- **Single writer per file.** A replica writes only its own `replicas/` file, its own batches, its
  own snapshots. `meta.json` is created once (create-if-absent) and changed only during a deliberate
  re-key. So concurrent devices never overwrite each other, and correctness doesn't depend on Drive
  honouring preconditions.
- **Idempotent writes:** names are deterministic; a retried upload finds the existing object.
- **Eventual-consistency tolerant:** listings may lag; an expected batch that isn't listed yet is
  retried with backoff, never treated as permanently missing.
- File names carry only ids, sequence numbers, hashes and clocks — no content.

## 7. Encryption and keys

- A random **data key** encrypts every object (AEAD, random nonce, object path as associated data).
- `meta.json` stores the data key **wrapped** by a key derived from the person's **sync passphrase**
  (memory-hard key derivation). Each device keeps the unwrapped key in Keystore-protected storage,
  so the passphrase is entered once per device.
- **Joining a device:** sign in to the same storage account, then enter the passphrase or scan a QR
  code from a device that already has the key.
- **Lost passphrase:** the cloud copy can't be decrypted. Existing devices keep working and can
  re-key (new data key, fresh snapshot). This is the honest cost of no server.

## 8. The sync cycle

1. **Capture:** the local-store adapter records each change into an outbox in the same database
   transaction as the app's write.
2. **Push:** pack outbox changes into a batch (size-capped), encrypt, upload if absent, update this
   replica's file, clear the sent outbox rows.
3. **Pull:** list `replicas/`; for each other replica with batches past our cursor, fetch, decrypt,
   validate, apply in sequence order in one local transaction, advance the cursor.
4. **Compact:** write a snapshot periodically; delete a replica's old batches only when every
   active replica's cursor has passed them and a snapshot covers them (only the owning replica
   deletes its own files).
5. **Retire stale devices:** a replica unseen beyond a retention horizon (default 180 days) stops
   blocking compaction; if it returns, it rebuilds from the latest snapshot.

Scheduling belongs to the app; the Android module ships a WorkManager helper (after local changes
with a debounce, on app foreground, periodically, network-constrained, with backoff).

## 9. What the app sees

```kotlin
sync.state: StateFlow<SyncState>   // Idle(lastSync) · Syncing(progress) · Error(kind, fix) · NeedsKey · Offline
sync.pending: StateFlow<Int>       // local changes not yet pushed
sync.devices(): List<ReplicaInfo>  // label, last seen, how far behind
sync.conflicts(): Flow<Conflict>   // only for tables that opted in
```

Errors are typed (`AuthExpired`, `QuotaExceeded`, `StorageUnavailable`, `KeyMissing`,
`Quarantined(table, count)` …) so apps can show an action, never a silent stuck state — the failure
Property Manager's swallowed errors produce.
