# Handoff — implement drivesync

For the Claude Code session that builds this library. You have no memory of the planning
conversation; this repo's docs are the spec.

1. Read `CLAUDE.md`, `docs/DESIGN.md`, `docs/ARCHITECTURE.md`, `docs/ROADMAP.md`,
   `docs/DECISIONS.md`.
2. Use the Android app development skill's dependency process (`platform-currency.md` §10) to
   choose versions; prove the empty multi-module build before writing library code.
3. Work through the roadmap in order: **S0 spike first** (write ADR-0002 with measurements and
   stop for Pete's decision if the result is close), then S1 → S4. One branch and one pull request
   per milestone; don't merge.
4. Every milestone ends with its "proven by" column demonstrated, not asserted.
5. The three invariants in `CLAUDE.md` are pasted verbatim into any review or fix prompt.
6. Stop and ask Pete before choosing the Maven namespace.
7. At the end of each milestone, summarise: what was built, what was proven and how, what is
   blocked, and the PR link.
