# drivesync — architecture

How the library is built. Behaviour is defined in `DESIGN.md`; when the two disagree, fix one of
them in the same change.

## 1. Modules

```
:core            Pure Kotlin. HLC, change/batch model, merge registry, merge engine, sync cycle,
                 BlobStore and LocalStore interfaces, KeyManager interface, typed errors.
:crypto          AEAD (Tink) + passphrase key derivation; KeyManager implementation; key
                 storage behind an interface (Android Keystore implementation lives in :android).
:room            LocalStore for Room/SQLite: trigger-based change capture into an outbox table,
                 apply-with-triggers-suppressed, merge registry from annotations or a DSL.
:android         WorkManager scheduler, network monitor, Keystore-backed key storage, QR join helper.
:backend-drive   BlobStore over the Google Drive app-data folder (Drive REST v3, OkHttp),
                 authorization through an app-supplied token provider.
:backend-local   BlobStore over a directory (tests, USB/manual transfer, development).
:testing         In-memory BlobStore with fault injection, deterministic simulator, test vectors.
:sample          Minimal Android app: a notes table synced between two emulators.
```

Dependency rule: everything depends on `:core`; nothing in `:core` depends on Android, Room, or a
network library. Backends never see plaintext (they get encrypted bytes from `:core`).

## 2. Key interfaces

```kotlin
interface BlobStore {
    suspend fun list(prefix: String, pageToken: String? = null): Page<BlobInfo>
    suspend fun get(path: String): ByteArray?
    suspend fun putIfAbsent(path: String, bytes: ByteArray): PutResult   // Created | AlreadyExists
    suspend fun putOwned(path: String, bytes: ByteArray)                 // only for this replica's own files
    suspend fun delete(path: String)                                     // only for this replica's own files
}

interface LocalStore {
    suspend fun drainOutbox(max: Int): List<Change>
    suspend fun ackOutbox(upTo: Long)
    suspend fun apply(changes: List<Change>, registry: MergeRegistry): ApplyReport
    suspend fun snapshot(): Sequence<Change>
    suspend fun cursors(): Map<ReplicaId, Long>
    suspend fun saveCursor(replica: ReplicaId, seq: Long)
}

interface KeyManager {
    suspend fun state(): KeyState                      // Ready | NeedsPassphrase | NeedsJoin
    suspend fun create(passphrase: CharArray): WrappedKey
    suspend fun unlock(passphrase: CharArray, wrapped: WrappedKey)
    suspend fun encrypt(path: String, plain: ByteArray): ByteArray
    suspend fun decrypt(path: String, cipher: ByteArray): ByteArray
}
```

## 3. Wire formats (versioned)

- **Batch** — header `{format: 1, replicaId, seq, schemaVersion, hlcFrom, hlcTo, count}` followed
  by changes, serialized with kotlinx.serialization (CBOR), then encrypted. Path:
  `batches/<replicaId>/<seq zero-padded>-<sha256 of plaintext, 16 hex>.bin`.
- **Replica file** — JSON, encrypted: `{format, replicaId, label, platform, lastSeen, lastSeq,
  cursors{replicaId: seq}, schemaVersion, retired}`.
- **Snapshot** — header plus a stream of changes representing the full state at `hlc`.
- **meta.json** — the only plaintext file: `{format, created, kdf{alg, params, salt},
  wrappedKey}`.

Any reader rejects an unknown `format` with a typed error; formats only ever add fields.

## 4. Drive backend specifics

- Space: `appDataFolder` (hidden from the person's Drive UI; scope `drive.appdata`, which Google
  classes as non-sensitive). Paths are mapped to file names inside the space (Drive has no real
  paths); a small local cache maps path → file id.
- Uploads: simple upload for objects ≤ 5 MB, resumable above. `putIfAbsent` checks the cache, then
  lists by name before creating, and treats a duplicate-name race as `AlreadyExists` after
  comparing content hashes.
- Rate limits and quota: honour `429`/`403 rateLimitExceeded` with exponential backoff and jitter;
  surface `QuotaExceeded` when the person's storage is full.
- Authorization is the app's job: the backend takes `suspend () -> AccessToken` and reports
  `AuthExpired` when a token is refused.
- **Never relies on `If-Match`/ETag preconditions** (see `DESIGN.md` §6).

## 5. Testing

| Layer | What | How |
|---|---|---|
| Merge properties | Convergence, idempotency, commutativity, tombstones never resurrect, append-only rows never lost | Kotest property tests over random change sets |
| Simulation | N replicas, random edits, partitions, duplicated and reordered delivery, lagging listings, clock skew of ±days, crashes mid-push | Deterministic simulator in `:testing`, seeded; 10,000 runs in CI nightly, 500 per PR |
| Room adapter | Trigger capture, apply without echo, migration with a non-empty outbox | Robolectric |
| Backend contract | Same test suite against every BlobStore | `:backend-local` always; Drive against a real test account, opt-in |
| Crypto | No plaintext marker strings in any stored object; tampered or misplaced objects rejected | Unit tests |
| Sample app | Two emulators sync notes through a local folder and through Drive | Journey scenario |

Shared **test vectors** (JSON: input change sets → expected final state) document the merge
rules by example and let a copy of the pattern in another language check itself.

## 6. Build and release

Kotlin, Gradle version catalog, JDK 17, Android library modules targeting minSdk 26 (lowest that
the Keystore and WorkManager features need), Apache 2.0, published to Maven Central under a
namespace Pete chooses. Binary-compatibility validator on public APIs from 0.1. Dokka for API docs.
