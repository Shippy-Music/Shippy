# LT01A — Implement recoverable migration/bootstrap state

**Parent packet:** `WP01`  
**Execution wave:** 1  
**Dependencies:** `LT00C`  
**Luna ownership:** Migration bootstrap store, authority selector recovery state, migration ViewModel/UI, and tests.  
**Terra-owned/shared seams:** Terra owns MainActivity/AuxioService startup integration, DB lifecycle, and destructive/reset authorization.

## Assignment

Corrupt/inconsistent migration state can be diagnosed, exported, reconstructed from trustworthy DB/audit evidence where possible, or safely reset/restore through an explicit user-approved path while preserving R15 data and backup.

## Why this task exists in the current worktree

The authority selector correctly fails closed when persisted migration/bootstrap state is corrupt, but the recovery host can repeatedly retry the same unreadable marker without a complete repair path. Before any real cutover, corruption must lead to a safe, explicit recovery choice rather than permanent purgatory or silent legacy/R16 access.

## Locked decisions

- Fail closed on ambiguity.
- Never delete R15 or R16 user data merely to clear a marker.
- M0–M13 recovery cannot invoke M14.
- Recovery actions are idempotent and process-death safe.
- Audit evidence and backup remain inspectable.

## Explicit non-goals

- Do not activate R16.
- Do not redesign the importer.
- Do not add a hidden “ignore corruption” bypass.

## Start with these repository surfaces

- `shippy-data/src/main/kotlin/app/shippy/data/migration/R16MigrationBootstrapStore.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/R16StartupState.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/orchestration/R16MigrationOrchestrator.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/authority/R16AuthoritySelector.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/migration/R16MigrationViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/migration/R16MigrationFragment.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/r16/authority/R16AuthoritySelectorTest.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/migration/R16MigrationBootstrapStoreTest.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define recovery diagnoses

Distinguish unreadable marker, checksum/version mismatch, impossible phase transition, missing backup, missing DB, and resumable interrupted phase. Surface structured reason and safe available actions.

### 2. Add non-destructive reconstruction

Where DB `migration_audit` and verified backup evidence can deterministically reconstruct phase/checkpoint, write a repaired marker transactionally and record an audit event.

### 3. Add explicit restore/reset paths

Restore from verified backup or restart import only after preserving current files and explicit confirmation. Marker reset alone is allowed only when evidence proves no R16 mutation/cutover risk; otherwise remain blocked.

### 4. Make UI honest

Recovery screen shows what is preserved, what action will do, progress, errors, export diagnostics, and retry. Process death returns to the same durable state.

## Edge cases that must be handled

- partial marker write
- unsupported marker version
- R16 DB exists but audit missing
- backup corrupt/missing
- R15 permission unavailable
- process death during repair
- repeated action tap

## Verification contract

- Store/orchestrator tests for each diagnosis/action/idempotency.
- Authority selector tests prove no legacy/R16 access in unresolved corruption.
- ViewModel state tests.
- Backup round-trip/restore tests where used.

## Completion contract

Every corrupt migration state has a documented safe path or an explicit genuinely blocking state; retry cannot loop uselessly and no data is discarded.

## Return to Terra instead of improvising when

- A recovery option would irreversibly delete owner data.
- Available evidence cannot distinguish safe reset from possible partial cutover.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
