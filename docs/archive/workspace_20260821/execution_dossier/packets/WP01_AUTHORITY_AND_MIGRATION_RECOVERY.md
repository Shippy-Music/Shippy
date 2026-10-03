# WP01 — Authority and Migration Recovery

**Priority:** P0 before activation  
**Primary owner:** Terra: migration/authority owner  
**Dependencies:** WP00  
**Parallel safety:** Do not parallelize central startup/cutover edits. Luna may own isolated recovery UI/tests or fixture tooling.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C04_DATA_MIGRATION.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Finish the safe path from legacy R15.3 through resumable verified migration to exactly one durable R16 authority. Keep activation fail-closed until the complete gate is satisfied.

## Current repository state

M0–M13 importer, backup, audit, migration UI, process gate, isolated ACTIVE composition, and M14 code exist. Persisted `R16_ACTIVE` still maps to `ACTIVE_UNAVAILABLE`; M14 has no production caller. Corrupt bootstrap state is detected but recovery actions are incomplete. No real owner v10 fixture has been run.

## Target outcome

Interrupted/corrupt migration has a recoverable UI and deterministic state transition; an owner-shaped real fixture proves zero unexplained loss; M14 atomically activates R16 only after backup/verification; legacy authority cannot start afterward; rollback/recovery truth is documented and tested.

## Locked packet invariants

- Never mutate the source R15 database during import.
- Never activate both authorities.
- Never use destructive migration or silent count loss.
- Cutover is atomic and preceded by verified backup/audit.
- An unreadable marker fails closed but offers a safe recovery route.
- Application-ID choice remains an explicit owner gate.

## Non-goals

Do not activate R16 merely to make progress metrics look better. Do not delete legacy code in this packet until WP10 final deletion gate. Do not fabricate “real fixture” evidence from synthetic data.

## Current code map

- `shippy-data/.../migration/**` including `R16M14Cutover.kt`
- `app/.../r16/migration/**`
- `app/.../r16/authority/R16AuthoritySelector.kt`
- `app/MainActivity.kt`, `app/AuxioService.kt` (Terra-only)
- `shippy-data/R16DataRuntime.kt`
- backup runtime/importer and migration audit DAOs
- migration/authority/instrumentation tests

## Implementation work

### A. Recovery-state model

Define explicit user-safe actions for each startup state: continue, retry current phase, restore verified backup, discard only an uncommitted R16 attempt and return to untouched R15, export diagnostics, or stop. Validate marker/audit/database consistency before an action. Never loop Retry against a corrupt marker with no repair.

### B. Real-fixture harness

Provide a documented, redaction-safe way to copy the owner v10 database and related expected artifacts into a local test/device fixture without secrets. Record source hashes/count evidence. Keep the actual owner fixture out of source control. Run the importer and compare counts, playlist occurrences/order, local/download assets, Last.fm outbox, lyrics, checkpoint, saved sources, and warnings.

### C. M14 readiness check

Create one pre-cutover result that proves: M0–M13 complete, audit verified, backup readable, R16 DB opens, derived indexes verified, no active legacy lease, no unsafe pending sidecars, and application-ID strategy recorded. M14 consumes only that result.

### D. Atomic authority transition

After approval, close/stop legacy owners, write durable ACTIVE marker atomically, reopen/select R16, and verify startup. A crash before marker commit remains recoverable to legacy/recovery; a crash after commit must select R16 or explicit recovery, never legacy silently.

### E. Rollback semantics

Before public beta, rollback means reinstall/restore from verified backup or a documented package transition—not runtime dual authority. Do not add a hidden toggle that lets both databases diverge.

### F. Application ID gate

Continue all non-dependent work. At final lock, request the exact reverse-domain string if still absent. Apply the master-spec selected strategy consistently to authorities, deep links, backup/export, signing/update behavior, and migration UX.

## Edge cases and failure behavior

- Power loss/cancellation during every migration phase.
- Missing/corrupt marker, DB, backup, or audit.
- Revoked SAF grant and missing managed assets.
- Existing R16 partial attempt plus intact R15.
- R16 marker written but runtime fails to open.
- Application-ID migration/install coexistence and update path.

## Performance constraints

Importer pages remain bounded; preflight avoids loading whole DB; file copies stream; verification reports peak/duration later. Startup classification stays cheap and read-only.

## Verification

Host migration tests, Room migration tests, backup round trip, corrupt-state tests, process-gate race tests, instrumentation startup tests, and eventually real owner fixture/device evidence. Do not claim cutover complete without the real fixture and active startup proof.

## Done means

Every non-owner migration/recovery mechanic is complete and tested; the only remaining pre-activation blockers are explicit owner application-ID choice and unavailable physical/owner evidence. After that evidence, M14 can activate exactly one authority without data loss.

## Escalate only when

The exact application ID is needed; real owner data reveals an unmapped product semantic; or recovery would require discarding data not safely covered by backup.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
