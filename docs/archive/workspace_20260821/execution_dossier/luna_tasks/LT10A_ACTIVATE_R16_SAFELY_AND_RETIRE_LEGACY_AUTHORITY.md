# LT10A — Activate R16 safely and retire legacy authority

**Parent packet:** `WP10`  
**Execution wave:** 7  
**Dependencies:** `LT01B`, `LT02B`, `LT03B`, `LT04D`, `LT05B`, `LT06B`, `LT07B`, `LT08C`, `LT09B`  
**Luna ownership:** M14/authority/runtime startup integration and evidence-based legacy deletion.  
**Terra-owned/shared seams:** Terra/Sol only for central choke points; Luna may implement isolated subchanges under reserved ownership.

## Assignment

Verified M14 makes R16 the sole durable data/playback/Library/UI authority on startup; rollback/restore is documented; obsolete legacy authorities/adapters are deleted after caller proof.

## Why this task exists in the current worktree

The full R16 runtime/service/shell exists but durable `R16_ACTIVE` intentionally selects `ACTIVE_UNAVAILABLE`; R15 remains production authority. Final activation must occur only after importer, feature and evidence gates, then legacy competing authorities must be removed without deleting useful Auxio/Media3/Musikr infrastructure.

## Locked decisions

- No dual authority after activation.
- M14 requires verified backup and zero unexplained loss.
- Startup opens only the selected authority.
- Useful low-level infrastructure survives behind Shippy interfaces.
- No hidden fallback can diverge state.

## Explicit non-goals

- Do not activate before prerequisites/evidence.
- Do not delete R15 data before rollback window/policy.
- Do not add new product architecture here.

## Start with these repository surfaces

- `shippy-data/src/main/kotlin/app/shippy/data/migration/R16M14Cutover.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/migration/R16StartupState.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/authority/R16AuthoritySelector.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/authority/R16ActiveDataRuntimeOwner.kt`
- `app/src/main/java/org/oxycblt/auxio/MainActivity.kt`
- `app/src/main/java/org/oxycblt/auxio/AuxioService.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/service/`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/org/oxycblt/auxio/playback/`
- `app/src/main/java/org/oxycblt/auxio/home/`
- `app/src/main/java/org/oxycblt/auxio/shippy/playback/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Freeze readiness input

Consume LT01B typed readiness plus feature/evidence gates. Record exact source/build/backup hashes and block otherwise.

### 2. Perform M14 transaction

Persist authority marker only after all durable writes/backup verification; make repeat idempotent. Restart into R16 and verify one shared runtime/spine/session.

### 3. Startup integration

MainActivity/AuxioService/MediaBrowser/WorkManager/widget/notification/deep links route solely to R16 under active marker. Migration/recovery remain isolated.

### 4. Rollback/restore

Define pre-release rollback window and backup restore path that cannot create split-brain. After stable use, old DB may be archived/deleted only per documented policy.

### 5. Delete legacy authority

Use dependency map to remove old playback state/queue/library DB/UI authority and temporary ACTIVE scaffold/adapters. Retain Media3/Musikr/Auxio audio/system primitives through R16-owned interfaces.

### 6. Verify process boundaries

Cold start, process death, task removal, media button, widget, auto/notification and background work all bind same R16 coordinator/data runtime.

## Edge cases that must be handled

- interrupted M14
- marker write then crash
- old/new DB mismatch
- app update/downgrade
- background worker during cutover
- media service start before UI
- rollback after new R16 changes

## Verification contract

- M14/startup/cutover tests.
- Real owner migration and restart evidence.
- Instrumentation/system-surface tests.
- Full compile/lint/build after legacy deletion.
- No legacy active caller in dependency scan.

## Completion contract

A normal installed app starts and operates entirely through R16 with preserved data and no competing legacy state; rollback truth is documented and tested.

## Return to Terra instead of improvising when

- Readiness evidence is incomplete or data loss appears.
- Application ID/signing/update choice blocks safe activation.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
