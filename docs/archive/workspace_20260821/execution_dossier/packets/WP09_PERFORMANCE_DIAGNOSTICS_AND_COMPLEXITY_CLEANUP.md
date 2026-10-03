# WP09 — Performance, Diagnostics, and Complexity Cleanup

**Priority:** P1 before beta  
**Primary owner:** Terra: performance/reliability owner  
**Dependencies:** WP00  
**Parallel safety:** Benchmark work can run in parallel; production fixes return to the packet that owns the code.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Measure and harden the real integrated R16 paths, reduce runtime waste and transitional complexity, and provide actionable diagnostics without weakening correctness.

## Current repository state

Macrobenchmark/baseline-profile modules, 50k fixture generation, playback/identity/migration traces, many large-queue tests, scoped Paging, and bounded source preparation exist. Final active-path measurements do not. Some channels are `UNLIMITED`, queue invariants allocate full sets on snapshots/mutations, large classes contain multiple internal responsibilities, and transitional legacy+R16 code inflates complexity.

## Target outcome

Reference datasets/devices meet recorded budgets; no main-thread/blocking/full-catalogue regressions remain; channels/backpressure and queue operations are intentional; diagnostics are bounded/redacted; dead transitional code is removed after cutover; complexity is reduced based on evidence rather than LOC vanity.

## Locked packet invariants

- Never remove identity/generation/data-safety checks just to improve a microbenchmark.
- Measure the real active path.
- Performance claims include device/dataset/method.
- Diagnostics never expose credentials/private URLs.
- Cleanup follows authority cutover and usage evidence.

## Non-goals

Do not create benchmarks for hypothetical paths. Do not split classes merely to reduce line count. Do not replace bounded correctness with lossy event handling. Do not clear Gradle/build caches without diagnosed corruption.

## Current code map

- macrobenchmark/baselineprofile modules and benchmark fixture
- PlaybackCoordinator, Media3 adapter, snapshot/reducer/window
- Room/Paging/FTS queries and migration importer
- image/palette/cache paths
- provider/enrichment/WorkManager concurrency
- traces/error models/debug report
- legacy/transitional dependency graph after cutover

## Implementation work

### A. Establish measurement baselines

Run cold/warm launch, Library switch, 100/10k playlist open, local/provider/cached first play, 10k shuffle, 50k FTS, provider local-first search, Now Playing transition, artwork fast scroll, background work, and import duration/peak memory using documented fixtures/devices.

### B. Main-thread and allocation audit

Trace Room/network/JSON/fingerprint/artwork/queue work. Move blocking work off main; remove whole-catalogue mapping. Profile `PlaybackSnapshot`/QueueState invariant set allocations and validate heavy structural properties at mutation boundaries or maintain indexed state without weakening guards.

### C. Event/backpressure audit

Review `Channel.UNLIMITED` in coordinator/Media3 and any other flow. Classify events: ordered lossless transitions/errors versus conflatable state/position. Use bounded channels/actors or explicit backpressure where safe; prove no deadlock/lost intent under bursts.

### D. DB/query optimization

Inspect query plans/indexes for Library, playlist, FTS, source selection, history, download, candidate retrieval, and migration. Avoid SQLite variable limits, N+1, broad invalidation, and huge transactions. Keep PagingSource invalidation correct.

### E. Image/cache/UI performance

Bound artwork/palette cache, cancel stale requests, use thumbnails, avoid rebinding unchanged rows, include mini/full player insets, and measure frame timing/memory.

### F. Background budget

Enrichment, GC, Last.fm, download, cache eviction, and migration work use unique identities, constraints, limited concurrency, cancellation, and observable progress/backoff.

### G. Diagnostics

Provide bounded playback/identity/migration/download traces, structured error codes, source/queue IDs, generation/revision, and redacted export. Add debug guards for main-thread violations and oversized queue/catalogue operations.

### H. Complexity cleanup

After active cutover, map actual callers and delete legacy authorities/adapters/tests/resources no longer required. Consolidate repeated UI/DAO code when responsibility is truly duplicate. Retain clear boundaries whose value is correctness/testability. Record before/after LOC and dependency reduction as information, not a target.

## Edge cases and failure behavior

- burst commands and callbacks.
- 10k duplicate-heavy queues.
- 50k Library with artwork.
- slow/malformed providers and network flapping.
- low memory/storage.
- process death during background work.
- benchmark variant differing from real release path.

## Performance constraints

This packet defines the budgets: measure target values from master spec, record deviations, and fix until beta severity is closed. No unmeasured “fast enough” claim.

## Verification

Unit/integration stress, Macrobenchmark, baseline profile generation, Perfetto/gfxinfo as appropriate, query-plan checks, memory/leak/ANR inspection, and PERF-001–PERF-015. Record device/OS/build/dataset/result in `docs/r16/PERFORMANCE.md`.

## Done means

Measured active R16 satisfies performance contract; diagnostics explain failures safely; no beta-severity ANR/leak remains; transitional bloat and duplicate authority are removed without losing necessary boundaries.

## Escalate only when

A required budget cannot be met without changing product scope/behavior, or platform/device evidence contradicts the architecture materially.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
