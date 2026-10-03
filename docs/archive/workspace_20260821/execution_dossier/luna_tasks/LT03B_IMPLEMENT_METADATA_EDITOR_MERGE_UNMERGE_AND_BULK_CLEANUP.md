# LT03B — Implement metadata editor, merge/unmerge, and bulk cleanup

**Parent packet:** `WP03`  
**Execution wave:** 3  
**Dependencies:** `LT03A`  
**Luna ownership:** User override editor, merge/unmerge application logic/UI, cleanup review queue and tests.  
**Terra-owned/shared seams:** Terra owns schema/redirect transaction changes, Library navigation, and cross-feature bulk-operation integration.

## Assignment

Users can edit canonical presentation fields, inspect provenance, revert overrides, merge/unmerge exact same recordings reversibly, and run a bounded cleanup scan that auto-links only safe exact matches and queues probable cases for review.

## Why this task exists in the current worktree

User overrides and identity audit models exist, but R16 lacks the full editor, merge/unmerge workflow and conservative bulk Library cleanup required to make messy local libraries pleasant without silently rewriting user choices.

## Locked decisions

- User override wins until explicitly reverted.
- Raw observations are retained.
- Merge redirects are acyclic and auditable.
- PlaylistEntry/QueueEntry occurrences remain distinct.
- Automatic cleanup never metadata-merges ambiguous recordings.
- Physical tag writing is optional/fail-safe; DB remains authority.

## Explicit non-goals

- Do not silently overwrite local files.
- Do not bulk process the whole library on main thread.
- Do not treat source removal as Recording deletion.

## Start with these repository surfaces

- `shippy-core/src/main/kotlin/app/shippy/core/identitymatch/RecordingRedirects.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/music/MetadataModels.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/IdentityDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/entity/IdentityAuditEntities.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/transaction/CanonicalWriteTransactions.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/maintenance/R16CatalogueMaintenance.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/source/MusikrLocalMediaEngine.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Metadata editor

Expose title, artist credit, release, artwork, year/track/disc where supported, explicitness and version. Show current provenance/raw alternatives. Write field-level UserMetadataOverride and recompute presentation transactionally.

### 2. Revert and optional tag write

Reverting removes only the selected override and recalculates canonical value. Optional local tag writing occurs only to a writable exact Asset, reports partial failure, and never makes the file authoritative over DB.

### 3. Merge transaction

Choose survivor deterministically/user-selected; redirect loser; move/link sources/assets/library relationships/history/playlist occurrences without collapsing occurrences; merge provenance/audit; resolve all reads through redirect.

### 4. Unmerge

Use audit snapshot to recreate/restore entities and links only when no later incompatible mutations make it unsafe. Otherwise surface a review conflict.

### 5. Bulk cleanup

Scan only unresolved/suspicious/deduplicable rows in bounded WorkManager batches. Classify exact-safe, probable-review, ambiguous, rejected. Auto-apply only exact safe evidence; show review UI with accept/reject/never-suggest.

### 6. Diagnostics

Every operation records reason/evidence/actor/time and can export a redacted trace.

## Edge cases that must be handled

- same Recording in multiple playlists/history
- active queue contains loser ID
- two local files same bytes
- same title different version
- later edit after merge
- partial tag write
- process death during cleanup
- negative decision repeated

## Verification contract

- Transaction/redirect/undo tests.
- Migration/backup coverage for overrides/audits/redirects.
- Bulk batch idempotency/resume tests.
- UI editor/review state tests.
- Randomized redirect acyclic test.

## Completion contract

Messy metadata can be safely cleaned manually or in conservative batches; user choices remain stable, provenance is inspectable, and wrong decisions are reversible.

## Return to Terra instead of improvising when

- Unmerge cannot be made lossless after later data mutations.
- Physical tag semantics require an owner behavior beyond “best effort, DB authoritative.”

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
