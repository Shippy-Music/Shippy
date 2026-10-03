# LT09B — Consolidate diagnostics and remove unjustified complexity after integration

**Parent packet:** `WP09`  
**Execution wave:** 6  
**Dependencies:** `LT09A`  
**Luna ownership:** Trace/error/debug export and evidence-based simplification/deletion map.  
**Terra-owned/shared seams:** Terra owns deletions or changes in another packet’s production paths.

## Assignment

Supportable redacted diagnostics exist for playback/identity/migration/download/provider failures, and duplicated/transitional code is removed or consolidated only with caller/evidence proof.

## Why this task exists in the current worktree

The staged rewrite temporarily contains R15 authority, R16 authority, importer, adapters and substantial new infrastructure. R16 already has bounded playback trace and migration/download audit pieces, but final troubleshooting and complexity cleanup must happen after consumers converge, not by arbitrary LOC targets.

## Locked decisions

- Diagnostics are bounded and redact paths/tokens/credentials.
- One authority per concern after cutover.
- Delete transitional code only after replacement and migration evidence.
- Keep boundaries that buy correctness/testability.
- LOC reduction is information, not the goal.

## Explicit non-goals

- Do not rewrite stable code for aesthetics.
- Do not keep dead legacy “just in case.”
- Do not export private source locators.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackTrace.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/diagnostics/CrewDiagnostics.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/`
- `shippy-data/src/main/kotlin/app/shippy/data/offline/`
- `docs/r16/STATUS.md`
- `docs/r16/KNOWN_ISSUES.md`
- `docs/r16/RELEASE_HANDOFF.md`
- `app/src/main/java/org/oxycblt/auxio/playback/`
- `app/src/main/java/org/oxycblt/auxio/home/`
- `app/src/main/java/org/oxycblt/auxio/shippy/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Unify structured errors

Stable codes/category/retryability/user action/source/queue/recording IDs and safe message. UI sees presentation model; diagnostics retain technical cause.

### 2. Bounded trace/export

Playback events include generation/revision/QueueEntry/source/engine commit; identity includes candidates/evidence/decision; migration/download include phase/transition/count. Add redacted export with size/time bounds.

### 3. Caller/dependency map

Generate actual references from R16 active composition. Classify keep-behind-interface, temporary adapter, superseded authority, dead resource/test/doc.

### 4. Simplify

Collapse duplicate repositories/helpers only when they own the same responsibility. Split very large classes internally if it improves state-machine clarity without adding layers.

### 5. Update docs

Status and known issues reflect source/evidence, not aspirational phase labels.

## Edge cases that must be handled

- trace during rapid queue
- PII/local path
- huge migration log
- legacy code used only by importer
- reflection/R8 false dead-code appearance

## Verification contract

- Redaction/size tests.
- Structured error mapping tests.
- Build/tests after deletion.
- Dependency/LOC report before/after.
- No active/migration caller broken.

## Completion contract

A beta user can export useful safe diagnostics, and the final codebase no longer carries duplicate authorities or purposeless scaffolding.

## Return to Terra instead of improvising when

- A legacy path appears required for update/data compatibility and deletion consequence is uncertain.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
