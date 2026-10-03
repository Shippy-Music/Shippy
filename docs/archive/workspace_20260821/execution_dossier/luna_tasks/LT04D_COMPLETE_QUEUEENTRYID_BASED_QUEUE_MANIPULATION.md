# LT04D — Complete QueueEntryId-based queue manipulation

**Parent packet:** `WP04`  
**Execution wave:** 3  
**Dependencies:** `LT00D`  
**Luna ownership:** Core/coordinator command additions if needed, queue endpoint/UI/actions and tests.  
**Terra-owned/shared seams:** Terra owns PlaybackCommand/Reducer/Coordinator central edits and MediaSession command routing.

## Assignment

Queue is fully manipulable by QueueEntryId through the single PlaybackCoordinator, paged in UI, deterministic under shuffle, and safe under concurrent engine/source updates.

## Why this task exists in the current worktree

R16 Queue V1 pages around the current item and supports exact GoTo. Remove, move/drag, Play Next, Add, clear/replace and multi-select are deferred. These operations must preserve base queue/traversal/shuffle invariants and reject stale revisions rather than addressing by presentation index alone.

## Locked decisions

- QueueEntryId is the command target.
- Base queue and traversal/shuffle are distinct.
- Current occurrence remains stable when legal.
- Commands carry/validate revision where stale UI could be destructive.
- Media3 projects the resulting engine window; it does not author queue order.

## Explicit non-goals

- Do not expose coordinator directly to Fragment.
- Do not richly map the entire queue.
- Do not use RecordingId as occurrence identity.

## Start with these repository surfaces

- `shippy-core/src/main/kotlin/app/shippy/core/playback/PlaybackCommand.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/playback/PlaybackReducer.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/queue/QueueReducer.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackCoordinator.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/service/R16QueuePageEndpoint.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/system/R16MediaSessionAdapter.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16QueueFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16QueueAdapter.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/r16/playback/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Specify commands in core

Add only required pure commands/results for remove, move before/after, Play Next/Add, selected batch removal, and clear/replace where product permits. Reducer preserves invariants under shuffled/unshuffled/current removal.

### 2. Coordinator integration

Serialize commands, cancel/ignore stale preparation effects, update generation/revision, plan bounded engine transaction, checkpoint, and emit structured rejection.

### 3. Endpoint/MediaSession

Expose strict custom actions/extras or endpoint methods with QueueEntryId and expected revision. Validate IDs and page scope.

### 4. Paged UI

Long-press/action sheet, drag/accessibility move, multi-select and empty state. Keep current anchored page; load neighboring pages as needed, not full presentation.

### 5. Context actions

Play Next/Add from search/library/provider rows create fresh QueueEntryIds with origin/source identity and route through coordinator.

## Edge cases that must be handled

- remove current first/middle/last
- duplicate Recordings
- move under shuffle
- toggle shuffle after moves
- engine window contains removed entry
- command during preparation
- stale page revision
- 10k queue
- batch includes current

## Verification contract

- Pure randomized queue/reducer tests.
- Coordinator stale-effect/window/checkpoint tests.
- Endpoint/MediaSession validation tests.
- Queue UI state tests.
- 10k performance test.

## Completion contract

All required queue operations work on exact occurrences, preserve deterministic playback, remain paged, and reject stale/invalid intent cleanly.

## Return to Terra instead of improvising when

- Product semantics for clear/replace/current removal are not covered by master defaults.
- A core change conflicts with Crew command requirements under active WP07.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
