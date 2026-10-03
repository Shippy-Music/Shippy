# LT02A — Complete download actions and durable user-facing state

**Parent packet:** `WP02`  
**Execution wave:** 1  
**Dependencies:** `LT00B`  
**Luna ownership:** R16 offline app UI/actions, scheduler/repository APIs, and focused tests.  
**Terra-owned/shared seams:** Terra owns DB schema/runtime composition, shared Now Playing/sheet resources, and cache source-ranking integration.

## Assignment

Users can request, observe, pause/resume/retry/cancel/remove and repair a download from canonical R16 surfaces; every action targets the captured Recording/source/variant/destination job and preserves truthful durable state.

## Why this task exists in the current worktree

The low-level exact-source download pipeline is substantial: durable job identity, resolver, WorkManager, private staging, verification, SAF publication, managed-asset registry, scanner suppression, retry/cancel/remove states. It is not yet a complete user-reachable product across track rows, Now Playing, Downloads, permissions, and state restoration.

## Locked decisions

- A download is an Asset/source of the same Recording.
- No search/fallback or variant guessing after a job is requested.
- UI actions are keyed to captured IDs and cannot retarget.
- Publication remains verify-first and fail closed.
- Cache removal, download removal, and Library removal remain distinct.

## Explicit non-goals

- Do not implement streaming cache in this task.
- Do not adopt same-name files.
- Do not add provider fallback to download resolver.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/offline/`
- `shippy-data/src/main/kotlin/app/shippy/data/offline/R16OfflineRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/DownloadDao.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16NowPlayingFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16SystemCollectionDetailFragment.kt`
- `app/src/main/res/layout/fragment_r16_now_playing.xml`
- `app/src/test/java/org/oxycblt/auxio/shippy/r16/offline/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define a presentation model

Expose job/asset state by Recording and exact active job: unavailable, ready-to-request, requested/progress, paused, retryable, awaiting permission/storage, available, removing, final failure. Avoid UI parsing raw enum/error strings.

### 2. Wire request flow

From selected source/quality/destination construct and persist the exact job before scheduling unique WorkManager work. Reuse configured provider priority only before request, never after job identity is frozen.

### 3. Wire lifecycle actions

Implement pause/resume/retry/cancel/remove/permission repair and operation-token completion with stale-ID guards. Physical deletion/reconciliation retains existing noncancellable safety.

### 4. Expose across surfaces

Now Playing, relevant song/collection row action sheet, and Downloads system collection show consistent state/progress and messages. Avoid permanent Home Recent Downloads section.

### 5. Make restart truthful

On process restart reconcile worker/job/stage/pending SAF/published asset and resume or await user without duplicate work.

## Edge cases that must be handled

- double tap/duplicate work
- same Recording multiple variants/destinations
- revoked tree grant
- low storage
- cancel at each phase
- worker killed
- shared published asset
- remove while playing
- provider URL expiry

## Verification contract

- Existing offline unit tests plus presentation/action tests.
- Worker unique-work/restart/idempotency tests.
- SAF instrumentation later; fake destination tests now.
- App compile/lint for UI integration.

## Completion contract

Download is a complete, restart-safe R16 feature with truthful UI and no duplicate Recording/local scan.

## Return to Terra instead of improvising when

- Destination selection UX requires an owner decision beyond documented defaults.
- Android storage behavior forces destructive or ambiguous file ownership.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
