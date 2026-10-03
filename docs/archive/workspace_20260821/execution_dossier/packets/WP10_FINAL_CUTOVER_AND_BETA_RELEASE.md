# WP10 — Final Cutover and Beta Release

**Priority:** Release gate  
**Primary owner:** Terra + Sol final integration  
**Dependencies:** WP01, WP02, WP03, WP04, WP05, WP06, WP07, WP08, WP09  
**Parallel safety:** No broad parallel editing. Parallelize only independent evidence collection after the code is frozen.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C02_IDENTITY_AND_METADATA.md`, `contracts/C03_PLAYBACK.md`, `contracts/C04_DATA_MIGRATION.md`, `contracts/C05_OFFLINE_CACHE.md`, `contracts/C06_INTEGRATIONS.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Activate the finished R16 system, remove superseded authorities, complete first-party naming/legal/release engineering, and prove every Appendix V beta requirement honestly.

## Current repository state

R16 is inactive/fail-closed; R15 remains production authority. The master spec and operating model are preserved. Build/test scaffolding and many focused tests exist, but final real-data migration, device matrix, performance, signing/update path, Crew truth, accessibility, and full feature journeys are not proven.

## Target outcome

One signed beta candidate installs/upgrades through the chosen identity strategy, imports owner data without unexplained loss, runs only R16 authorities, passes product/device/performance/integration journeys, publishes limitations, and is independently audited.

## Locked packet invariants

- No dual authority.
- No false evidence.
- No user-data loss.
- No legacy fallback that can diverge after cutover.
- No release label before Appendix V evidence.
- Legal attribution and historical license truth remain correct.

## Non-goals

Do not use final release to add new architecture. Do not hide failures with ignored tests. Do not keep dead legacy code “just in case” after verified replacement. Do not ship debug signing or untested application-ID transition as beta.

## Current code map

- authority/cutover/migration/backup
- app/package/namespace/manifest/authorities/deep links/signing
- all R16 active composition and shell
- legacy playback/library/database/UI paths and adapters
- release build/R8/CI/baseline profile
- docs/r16 release evidence and known limitations
- final audit output

## Implementation work

### A. Freeze candidate and decisions

Record source snapshot/hash, approved ADRs, final application ID, provider/cache/history/Crew defaults, package/signing/update strategy, and feature gates. Stop feature expansion except release-blocking fixes.

### B. Run real migration and backup

Use redacted owner v10 fixture and physical owner copy as permitted. Verify backup, M0–M13 counts/relationships/warnings, M14 activation, restart, rollback/restore, revoked permissions, and package transition. Record zero unexplained loss.

### C. Activate R16

Enable durable `R16_ACTIVE` selection only after gate. Ensure MainActivity, AuxioService, MediaBrowser, WorkManager, widgets, notification, and startup all share one R16 runtime/coordinator. Remove ACTIVE_UNAVAILABLE scaffolding when no longer needed.

### D. Delete superseded authority

Use caller/dependency evidence to remove legacy playback/library/state/database authority, temporary active host, one-way adapters after consumers migrate, stale resources/tests/docs, and duplicated actions. Preserve Musikr/Media3/Auxio infrastructure behind Shippy interfaces where still valuable.

### E. First-party naming/legal

Make user-facing product and new first-party internals Shippy while preserving package/license/copyright/attribution history truth. Update application ID/namespace separately per decision. Verify content-provider authorities, deep links, backup file types, and update compatibility.

### F. Full verification

Run formatting, unit suites, Room migrations, integration, lint, debug/release assembly, R8/resource shrink, instrumentation, baseline profile, macrobenchmarks, physical Android/device/storage/route matrix, Last.fm real account, provider/offline/cache, process death, accessibility, and Crew stable/experimental gate.

### G. Final independent audit

Provide final repository + master spec + evidence ledger to independent Sol Pro. Classify findings as release blocker, near-term refinement, or post-beta cleanup. Apply blocker fixes through Terra/Luna and rerun affected gates.

### H. Release truth

Populate `RELEASE_HANDOFF.md`, `KNOWN_ISSUES.md`, `PERFORMANCE.md`, `DEVICE_TESTS.md`, migration evidence, hashes, signing certificate/update path, rollback, and exact Appendix V disposition. Owner runs final acceptance journeys.

## Edge cases and failure behavior

- clean install and every supported upgrade path.
- interrupted migration and corrupt recovery.
- package identity coexistence/update.
- offline first launch, process death, low storage/memory.
- provider/Last.fm/Crew partial failures.
- Android 7 through current supported matrix.
- release R8 stripping required reflection/Room/Hilt/Media components.

## Performance constraints

Performance evidence is final and measured on the release-equivalent build. Baseline profile is generated from real active journeys. No debug-only configuration masks release behavior.

## Verification

Run the full release-equivalent command, migration, physical-device, offline, provider, Last.fm, accessibility, performance, signing/update, and owner-acceptance matrix. Record exact evidence and rerun every affected gate after release-blocking fixes.

## Done means

Every Appendix V line is linked to concrete evidence or an honestly gated/published limitation consistent with the master spec; release APK/AAB is signed and update-tested; owner can use Shippy daily without competing state or known beta-severity defect.

## Escalate only when

Final application ID or signing ownership is missing; real migration/device evidence reveals irreversible data/product risk; or a release-contract item cannot be met without owner scope decision.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
