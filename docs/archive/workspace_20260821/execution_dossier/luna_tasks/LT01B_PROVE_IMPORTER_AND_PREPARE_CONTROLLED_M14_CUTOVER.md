# LT01B — Prove importer and prepare controlled M14 cutover

**Parent packet:** `WP01`  
**Execution wave:** 2  
**Dependencies:** `LT01A`  
**Luna ownership:** Migration fixtures/verifier/cutover preconditions and evidence docs.  
**Terra-owned/shared seams:** Terra owns real owner fixture handling, authority activation, and any schema migration conflicts.

## Assignment

The importer is exercised against production-like and safely redacted owner v10 data, produces zero unexplained loss, survives interruption/resume, and emits a machine-checkable cutover readiness result consumed by Terra later.

## Why this task exists in the current worktree

The importer, verifier, backup, and M14 model are extensive, but a synthetic suite is not equivalent to a real R15.3 owner database. M14 remains unreachable, correctly preventing premature authority switch.

## Locked decisions

- R15 remains untouched until M14.
- Counts alone are insufficient; relationships/order/identity/assets/outbox/checkpoint must verify.
- Warnings are explicit and classified.
- M14 requires verified backup and all M0–M13 gates.
- No test fixture is passed off as owner/device evidence.

## Explicit non-goals

- Do not activate runtime in this task.
- Do not modify owner data outside copied fixtures.
- Do not weaken verification to make counts green.

## Start with these repository surfaces

- `shippy-data/src/main/kotlin/app/shippy/data/migration/LegacyDatabaseReader.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/pipeline/R16MigrationPipeline.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/orchestration/R16MigrationOrchestrator.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/MigrationVerifier.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/R16M14Cutover.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/migration/`
- `docs/r16/MIGRATION.md`
- `docs/r16/DEVICE_TESTS.md`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Create fixture protocol

Define a redaction/copy procedure and hashes. Never commit private owner data. Keep a structurally faithful local fixture with expected evidence ledger.

### 2. Exercise complete phases

Run M0–M13, interrupt at representative boundaries, resume, repeat idempotently, and verify deterministic ID mapping/order/duplicates/download/lyrics/outbox/checkpoint/saved entities/local reindex audit.

### 3. Strengthen readiness result

Produce one typed result that lists every gate, warning, unexplained delta, backup identity, source/target versions, and cutover eligibility. M14 consumes this, not UI optimism.

### 4. Document evidence

Update MIGRATION/DEVICE_TESTS with exact fixture class, commands, counts, warnings, durations, hashes, and what remains physically unverified.

## Edge cases that must be handled

- malformed legacy row
- duplicate playlist occurrences
- missing provider metadata
- removed local file
- legacy download unavailable
- outbox retry rows
- unsupported Crew checkpoint
- process death between batch and marker

## Verification contract

- All migration unit/integration tests.
- Real-schema/owner-copy run recorded separately from committed synthetic fixtures.
- Backup export/import parity.
- M14 remains unreachable in production after this task.

## Completion contract

A reproducible readiness artifact proves or rejects cutover with zero unexplained loss; no unsupported claim is made.

## Return to Terra instead of improvising when

- No safely usable owner fixture is available.
- Verifier exposes an unavoidable ambiguous data mapping requiring owner choice.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
