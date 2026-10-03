# LT09A — Measure and fix R16 performance/backpressure hotspots

**Parent packet:** `WP09`  
**Execution wave:** 5  
**Dependencies:** `LT02B`, `LT04D`, `LT08A`  
**Luna ownership:** Performance tests, query/backpressure/allocation fixes in coordination with owning Terra.  
**Terra-owned/shared seams:** Production edits outside benchmark/diagnostic paths must be applied by the owning Terra or explicitly reserved.

## Assignment

Release-equivalent R16 meets the master latency/memory/ANR budgets on named fixtures/devices; event/backpressure and query/allocation costs are measured and corrected without weakening correctness.

## Why this task exists in the current worktree

R16 has performance fixtures, query-plan tests, macrobenchmark/baseline modules and several good bounded designs. Remaining known risks include whole-queue invariant set allocations, `Channel.UNLIMITED`, artwork/palette work, cache/background concurrency and unproven active-shell/device budgets.

## Locked decisions

- Measure before optimizing.
- Do not remove correctness guards blindly.
- No whole-catalogue rich hydration.
- No unbounded work queue without explicit bounded lossless justification.
- Performance evidence names build/device/dataset/method.

## Explicit non-goals

- Do not game benchmarks with debug-only shortcuts.
- Do not turn every helper into a micro-optimized abstraction.
- Do not clear caches to hide steady-state behavior.

## Start with these repository surfaces

- `macrobenchmark/`
- `baselineprofile/`
- `shippy-data/src/test/kotlin/app/shippy/data/performance/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackCoordinator.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/Media3PlayerAdapter.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/playback/PlaybackReducer.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/queue/QueueModels.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/`
- `docs/r16/PERFORMANCE.md`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Establish real journeys

Cold/warm launch, Library switch, 100/10k collection open, local/provider/cached first play, 10k shuffle/mutate, 50k FTS, Now Playing transition, artwork fast scroll, import, background work.

### 2. Profile main/allocation

Use Perfetto/gfxinfo/memory/query plans. Locate main-thread DB/network/JSON/image work, repeated queue set/map allocations, broad Room invalidations and N+1.

### 3. Event/backpressure

Classify coordinator/Media3 channels: lossless ordered transition/error versus conflatable state/position. Replace UNLIMITED with bounded/actor/conflation only where tests prove no lost intent/deadlock.

### 4. Optimize queries/state

Add indexes/query changes, cache stable queue indexes/invariants at mutation boundaries, avoid rich mapping, throttle progress writes and cancel stale requests.

### 5. Baseline profile

Generate from actual active R16 journeys, not legacy scaffold. Validate release-equivalent profile install/use.

### 6. Record budgets

Update PERFORMANCE with before/after numbers and remaining limitations; add regression threshold where stable.

## Edge cases that must be handled

- command burst
- 10k duplicates
- 50k library
- slow provider
- low memory/storage
- rapid artwork
- process death background work
- release R8/profile differences

## Verification contract

- Host stress/query-plan tests.
- Macrobenchmark and baseline generation.
- ANR/leak/frame/memory evidence on named device near beta.
- No weaker playback/queue invariants.

## Completion contract

Measured R16 satisfies the performance contract or has explicit release-blocking evidence; known hotspots are fixed at their actual source.

## Return to Terra instead of improvising when

- A required budget cannot be met without product-scope or architecture change.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
