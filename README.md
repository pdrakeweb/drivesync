# drivesync

Local-first sync for Android apps through storage the user owns. Every device works offline;
changes merge deterministically; the cloud copy is end-to-end encrypted; there is no server.

**Status:** in development, not released. S0 (merge-core decision) and S1 (core, local-folder backend, simulator, test vectors) are built; see `docs/ROADMAP.md`.

Build and test: `./gradlew build` (JDK 17+). The long simulation: `./gradlew :testing:test -PsimRuns=10000`.

| Doc | What it answers |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | The problem, goals, prior art (including Property Manager), merge rules, storage layout, keys |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Modules, interfaces, wire formats, Drive specifics, testing, release |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | Milestones S0–S4 and what proves each |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | What is settled and who settled it |
| [`docs/handoff/IMPLEMENT.md`](docs/handoff/IMPLEMENT.md) | Instructions for the implementation session |

Apache 2.0.
