# Decisions

Append-only. Source key: **Pete (words)** quoted, **Pete (picked)** chose an offered option,
**Proposed** awaiting confirmation.

| ID | Decision | Source |
|---|---|---|
| D-001 | A standalone library usable by any Android app, Apache 2.0; consuming apps may depend on it or copy the pattern. | Pete (words): "we should write these as libraries that can be leveraged by ANY android app (with a permissive license) and then the skill should recommend them as dependencies or possible implementation pattern if the author doesn't want to take on the dependency" |
| D-002 | Use an existing library if one fits; none does today (DESIGN §3), so write it, spiking Synk as a possible core. | Pete (words): "we should use a library if there is one that makes sense for this, and if not then we should write one. The semantics of drive interaction should not be unique to this app only." |
| D-003 | Google Drive app-data folder is the first backend; local folder for tests; others later. | Pete (picked), via NextRep D-018 |
| D-004 | Single writer per cloud file; never depend on storage preconditions (ETag/If-Match). | Proposed (from the Property Manager review) |
| D-005 | End-to-end encryption with a passphrase-wrapped data key; lost passphrase = cloud copy unrecoverable. | Proposed (NextRep Q-3) |
| D-006 | Repository and library name `drivesync`; repository public from the start; Maven namespace decided before 0.1.0. | Pete (picked) "drivesync + llm-tiers", "Public from the start" |
| D-007 | First consumer: NextRep (`pdrakeweb/nextrep`). | Pete (words) |
| D-008 | Write our own clock and merge core; do not adopt Synk. Measured in ADR-0002 (Synk resurrects deleted rows and loses edits when a row id is created on two devices, diverges under clock skew, drops late-added columns). | Proposed (S0 spike, 11 Nov 2026) |
| D-009 | Maven namespace and Kotlin package root: `io.github.pdrakeweb` (artifacts `io.github.pdrakeweb:drivesync-<module>`, packages `io.github.pdrakeweb.drivesync.*`). | Pete (picked), 11 Nov 2026 |
| D-010 | Additions to `LocalStore` beyond ARCHITECTURE §2: `pendingCount()` (C-3), `highestSchemaSeen()`/`noteSchemaSeen()` (schema rule survives restart), and `saveCursor` never moves backwards. | Proposed (S1) |
| D-011 | Additions to the app-facing surface beyond DESIGN §9: `held` and `orphaned` row lists, `ReplicaInfo.batchesToFetch`, errors `ReplicaStateBehind` and `Unexpected`, fix `ResetThisDevice`. Names are open. | Proposed (S1 review) |
| D-012 | `APPEND_ONLY` inserts are whole-row (one timestamp); on an id collision the earliest insert wins whole. | Proposed (S1 review) |
