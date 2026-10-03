# LT10C — Run final Appendix V evidence and independent audit loop

**Parent packet:** `WP10`  
**Execution wave:** 8  
**Dependencies:** `LT10A`, `LT10B`  
**Luna ownership:** Evidence collection/fix loop and release truth docs; no new architecture.  
**Terra-owned/shared seams:** Terra/Sol coordinate; Luna workers receive only isolated release-blocking fixes.

## Assignment

Every Appendix V line has concrete release-equivalent evidence or an explicitly permitted/gated limitation; independent audit blockers are fixed and affected gates rerun; owner receives a daily-drivable beta handoff.

## Why this task exists in the current worktree

The master Appendix V and exact acceptance journeys are the release contract. Source presence and green host tests are not physical migration/device/performance proof. Final verification must classify evidence honestly and use independent Sol Pro review without reopening completed architecture gratuitously.

## Locked decisions

- No false completion claim.
- Fixes are scoped to blockers; no feature gardening.
- Evidence records source/build/device/fixture/method/result.
- Crew is experimental if stable matrix incomplete.
- Known non-blockers are published honestly.

## Explicit non-goals

- Do not add new architecture during release freeze.
- Do not ignore/flakify tests to turn green.
- Do not call debug/manual spot checks release proof.

## Start with these repository surfaces

- `docs/r16/DEVICE_TESTS.md`
- `docs/r16/PERFORMANCE.md`
- `docs/r16/MIGRATION.md`
- `docs/r16/KNOWN_ISSUES.md`
- `docs/r16/RELEASE_HANDOFF.md`
- `macrobenchmark/`
- `baselineprofile/`
- `.github/workflows/`
- `fastlane/`
- `app/build.gradle`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Freeze candidate

Record Git/tree/source hashes, dependency lock/state, schema, app/signing identity and artifact hashes. Only blocker fixes change it.

### 2. Run automated matrix

Formatting, all host/unit/Room/migration/integration tests, lint, release compile/R8, instrumentation, baseline profile/macrobenchmarks and security/static checks.

### 3. Run physical matrix

Owner migration/backup, local/provider/cache/download/offline, playback torture, process death, system surfaces, Last.fm real account, lyrics, accessibility/adaptive, storage/routes/Android versions, Crew stable or gated.

### 4. Independent Sol Pro audit

Provide final repository, unchanged master spec, dossier and evidence ledger. Classify release blocker/near-term/post-beta. Terra dispatches only blocker corrections to Luna and reruns affected/full gates.

### 5. Owner acceptance

Run exact daily-driver journeys and record acceptance. Publish known issues, rollback, support/diagnostic path and evidence distinction.

## Edge cases that must be handled

- flaky network/provider
- device-specific issue
- migration warning
- performance outlier
- optional integration outage
- fix invalidates earlier evidence

## Verification contract

- Every Appendix V/acceptance row linked to evidence.
- All release-blocking findings closed and rerun.
- Signed artifact hashes and update path recorded.
- Owner acceptance complete.

## Completion contract

Shippy R16 is a hardened beta by evidence: installable, migratable, coherent, performant, accessible and dependable for daily use.

## Return to Terra instead of improvising when

- A contract item cannot be met without owner scope decision.
- Real evidence reveals irreversible user-data or signing risk.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
