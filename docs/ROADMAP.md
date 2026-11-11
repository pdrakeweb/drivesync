# drivesync — roadmap

Each milestone ends with something proven, not just written.

| Milestone | Delivers | Proven by |
|---|---|---|
| **S0 · Spike** (≈ 1 week) | Decide: adopt Synk for the clock/merge core, or write our own | ADR-0002 with measured results: the merge property tests run against both candidates |
| **S1 · Core** | `:core`, `:backend-local`, `:testing` simulator, merge test vectors | Property tests and 10,000 simulated runs pass; no lost or resurrected rows |
| **S2 · Room + Android** | `:room` change capture and apply; `:android` scheduler; `:sample` app | Two emulators sync through a local folder; migration with pending changes passes |
| **S3 · Drive + encryption** | `:backend-drive`, `:crypto`, passphrase and QR join | Sample app syncs two emulators through a real Drive test account; no plaintext in any stored object |
| **S4 · Compaction + retirement** | Snapshots, safe batch deletion, stale-device retirement, conflict log | Simulator covers a device offline for 200 days rejoining correctly |
| **0.1.0 release** | Maven Central, docs site, a "use it or copy it" guide | First consumer (NextRep) integrated and syncing a real phone and tablet for 30 days |

After 0.1.0: WebDAV and S3-compatible backends; Kotlin Multiplatform targets; the Android app
development skill recommends the library (or its pattern) for "both devices write" apps.
