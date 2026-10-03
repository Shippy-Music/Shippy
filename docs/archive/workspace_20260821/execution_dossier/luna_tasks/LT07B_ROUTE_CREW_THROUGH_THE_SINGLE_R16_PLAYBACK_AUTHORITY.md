# LT07B — Route Crew through the single R16 playback authority

**Parent packet:** `WP07`  
**Execution wave:** 4  
**Dependencies:** `LT07A`, `LT04D`  
**Luna ownership:** Crew playback/source bridge and integration tests.  
**Terra-owned/shared seams:** Terra exclusively integrates PlaybackCoordinator/Spine/Service shared files and Crew service retention.

## Assignment

Active Crew projects portable canonical queue/playback state into R16 commands; local/system controls submit Crew intent when appropriate; device source resolution remains private; no second player or queue authority exists.

## Why this task exists in the current worktree

Legacy `CrewPlaybackBridge` intercepts the legacy PlaybackStateManager and resolves/installs queues itself. R16 requires Crew commands and remote state to flow through the same PlaybackCoordinator as local/system intent, while each device resolves its own sources and temporary Push & Pull assets.

## Locked decisions

- PlaybackCoordinator remains the only local playback authority.
- Crew session engine remains canonical group authority only while Crew active.
- Remote application is generation/sequence safe and does not echo as new intent.
- Each device resolves sources locally; Push & Pull assets are temporary and session-scoped.
- Coordinator-only automatic transition policy remains deterministic.

## Explicit non-goals

- Do not replace Crew transport/election/session engine.
- Do not let Crew call Media3 directly.
- Do not publish private asset locators.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/crew/playback/CrewPlaybackBridge.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/runtime/ActiveCrewRuntime.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/session/CrewSessionEngine.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/media/CrewPrivateSourceRegistry.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/cache/CrewTemporaryMediaIndex.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackCoordinator.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/R16PlaybackLocatorResolver.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/system/R16SystemPlaybackBridge.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Create R16 Crew adapter

Translate active Crew actions/state into pure R16 PlaybackCommands/contexts keyed by QueueEntryId. Translate local/system user intent into Crew actions when Crew owns group intent; avoid legacy mutation interceptor.

### 2. Remote projection safety

Use session/term/sequence/generation markers. Apply remote state without echo; wait for prepared current source; update queue/current/repeat/shuffle/position/play mode coherently.

### 3. Source resolution

Overlay private local/download/cache/temporary Crew sources per device. Unresolved entries remain in logical queue with honest unavailable state; Push & Pull completion re-prepares exact occurrence.

### 4. Drift and end behavior

Reuse tested clock/drift policies through coordinator commands/speed/seek effects. Only coordinator member authors group automatic next/end.

### 5. Lifecycle

Attach/detach with R16 service owner and feature gate. Ensure task removal/foreground retention considers active Crew without restoring legacy playback authority.

## Edge cases that must be handled

- join mid-play
- host empty queue seeding
- coordinator change
- network partition/rejoin
- unavailable source
- temporary transfer completion
- duplicate occurrence
- shuffle
- system media button on member
- process death

## Verification contract

- Crew reducer/session existing tests remain green.
- New bridge tests with fake coordinator/source resolver.
- No-echo/sequence/drift/unresolved/PushPull tests.
- System-control integration tests.
- Physical multi-device matrix before stable exposure.

## Completion contract

Crew uses the R16 identity and playback spine end to end with no duplicate authority and preserves the proven distributed-session machinery.

## Return to Terra instead of improvising when

- Stable multi-device matrix cannot be completed; Terra must gate Crew experimental.
- A legacy compatibility requirement conflicts with one-authority invariant.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
