# CLAUDE.md — drivesync

A standalone Kotlin library: local-first sync for Android apps through user-owned storage (Google
Drive app-data folder first), end-to-end encrypted, no server. Apache 2.0. First consumer:
NextRep (`pdrakeweb/nextrep`). Nothing in this repo may know about any consumer app.

Read `docs/DESIGN.md`, `docs/ARCHITECTURE.md`, `docs/DECISIONS.md` before changing anything.

## Invariants (paste verbatim into every review and fix prompt)

> **C-1 · NO LOST OR RESURRECTED DATA.** Any set of replicas that has exchanged the same changes
> converges to the same state, whatever the order or duplication; a deleted row never comes back;
> an append-only row is never lost.
>
> **C-2 · NOTHING READABLE LEAVES THE DEVICE.** Every object written to storage is encrypted except
> `meta.json`, which holds only a wrapped key and derivation parameters. File names carry no content.
>
> **C-3 · NEVER SILENTLY STUCK.** Every failure surfaces as a typed state the app can show, with
> a fix where one exists. Pending changes are counted and visible.

## Working rules

- Tests first. Merge behaviour changes only with a new test vector.
- `:core` stays free of Android, Room and network types (guarded in the build).
- Every guard is shown failing on a planted violation before it counts.
- Product-level choices (naming, defaults that affect users of consuming apps) go to Pete as
  questions; record answers in `docs/DECISIONS.md`.

Pete is a professional software engineer, newer to Android as a builder: skip general engineering
explanations, explain Android-specific terms on first use, no emojis.
