# Execution DAG and Recommended Waves

## Guiding rule

Finish the full R16 scope, but grow it through coherent product paths rather than implementing every named abstraction independently. Each wave retires a concrete set of risks and leaves the repository runnable. Phase labels do not substitute for evidence.

## Dependency graph

```text
WP00 Foundation corrections
   ├──> WP02 Offline + streaming cache
   ├──> WP03 Identify/Edit/Dedupe
   ├──> WP04 Library/Playlist/Queue completion
   └──> WP05 Last.fm + History

WP01 Authority + migration recovery preparation
   └──────────────────────────────────────────────┐
                                                  v
WP02 Offline/cache ─┐                        WP10 Final activation,
WP03 Identity UI ───┼──> WP08 Shell/UI ───>      cleanup and beta
WP04 Library ───────┤         polish               release
WP05 Last.fm ───────┤
WP06 Lyrics ────────┤
WP07 Crew ──────────┘

WP09 Performance/diagnostics begins opportunistically after WP00,
but final measurements occur after WP08 and before WP10 closure.
```

## Recommended waves

### Wave A — Correct the shared foundation

- `WP00_FOUNDATION_CORRECTIONS`
- Narrow documentation reconciliation by Terra after integration

Do not begin schema-heavy identity UI work until the candidate-retrieval and version-boundary corrections are settled.

### Wave B — Independent product verticals

Up to three concurrent Terra owners if file ownership is kept separate:

1. `WP02_OFFLINE_AND_STREAMING_CACHE`
2. `WP05_LASTFM_AND_HISTORY`
3. `WP06_LYRICS`

`WP01_AUTHORITY_AND_MIGRATION_RECOVERY` can proceed in parallel only if it does not edit the same database/runtime choke points. Terra must serialize shared `R16DataRuntime`, database version, app startup, and service composition edits.

### Wave C — Identity and Library product completion

- `WP03_IDENTIFY_EDIT_DEDUPE`
- `WP04_LIBRARY_PLAYLIST_QUEUE_COMPLETION`

These are coupled through Library membership, provenance, playlist actions, and read models. Use one Terra owner or explicit schema/API sequencing. Luna workers may split UI, repository/use-case, and tests after interfaces are fixed.

### Wave D — Crew migration

- `WP07_CREW_R16_INTEGRATION`

Crew should not be parallelized across uncontrolled shared playback/protocol files. One Terra owns the migration and delegates only non-overlapping subareas.

### Wave E — Product shell and quality

- `WP08_SHELL_M3_QOL_ACCESSIBILITY`
- `WP09_PERFORMANCE_DIAGNOSTICS_CLEANUP`

UI work follows stable state/read models. Performance work may run earlier for known hotspots, but final budgets require the real integrated path.

### Wave F — Final activation and release

- `WP01` real-owner migration proof and cutover preparation complete
- `WP10_FINAL_CUTOVER_AND_BETA_RELEASE`

This is where durable R16 activation, one-way migration, legacy deletion, naming/application-ID decision, release signing, device matrix, performance evidence, and Appendix V closure happen.

## Parallelism rules

- A work packet is not automatically a parallel task. Terra may subdivide it only after interfaces and ownership paths are fixed.
- No two agents may concurrently modify `ShippyR16Database.kt`, `ShippyR16DatabaseMigrations.kt`, `R16DataRuntime.kt`, `MainActivity.kt`, `AuxioService.kt`, central navigation resources, `PlaybackCoordinator.kt`, or `R16AuthoritySelector.kt` without explicit Terra coordination.
- Database schema changes are serialized through one owner per wave.
- Parent agents wait for delegated implementation rather than duplicating it.
- Maximum concurrency is determined by independent file ownership, not by available slots.

## Completion order inside every packet

1. Read packet and listed contracts.
2. Inspect listed code surfaces and direct callers.
3. Confirm packet assumptions against current code.
4. Add/adjust focused tests where they protect a real risk.
5. Implement the complete vertical behavior.
6. Run the packet's focused verification.
7. Integrate at shared seams through Terra.
8. Update current-state docs honestly.
9. Report evidence and remaining uncertainty upward.
