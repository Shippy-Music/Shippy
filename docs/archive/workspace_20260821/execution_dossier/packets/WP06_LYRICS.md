# WP06 — Lyrics

**Priority:** P1  
**Primary owner:** Terra: lyrics owner  
**Dependencies:** WP00  
**Parallel safety:** Strongly parallel-safe until final Now Playing/shared resource integration.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C06_INTEGRATIONS.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Migrate lyrics onto canonical R16 identity, cache, stale-result guards, and polished preview/full-player behavior.

## Current repository state

R16 database contains `LyricsCacheEntity`, DAO, backup/migration import. Legacy lyrics repository/sources and R15 player UI exist. No R16 lyrics repository/coordinator or R16 Now Playing lyrics presentation exists.

## Target outcome

R16 resolves synced/plain/instrumental/no-result lyrics from a bounded source chain, caches them against canonical metadata fingerprint, rejects stale results after playback changes, works offline, and presents stable preview/full lyrics consistent with the player.

## Locked packet invariants

- Lyrics result never overwrites a newer QueueEntryId/generation.
- Cache is validated against Recording metadata fingerprint/source evidence.
- Lyrics do not become Recording identity evidence without explicit provenance policy.
- Preview has stable height.
- Network/source failure does not destabilize playback.

## Non-goals

Do not couple lyrics fetching to Fragment lifetime alone. Do not use legacy `Song` as R16 key. Do not refetch on every position tick. Do not redesign the whole player.

## Current code map

- legacy `org/oxycblt/auxio/shippy/lyrics/**` and `PlaybackViewModel` for reusable source/parser evidence
- `shippy-data` lyrics DAO/entity/migration/backup
- new R16 lyrics repository/coordinator/state
- `R16NowPlayingFragment` and layouts/resources
- playback snapshot/presentation mapping
- lyrics tests

## Implementation work

### A. Canonical repository

Define R16 request key from RecordingId plus canonical metadata fingerprint and optional source evidence. Query valid cache first. Fetch from the retained source chain in bounded order, classify synced/plain/instrumental/not-found/retryable/final failures, sanitize and persist with provider/source metadata and expiry/fingerprint.

### B. Generation-safe coordinator

Observe current PlaybackSnapshot presentation. Start/cancel work on QueueEntryId/RecordingId/generation change. Every completion carries the captured key and is applied only if still current. Retry is explicit. Position sampling selects current line without network/DB work.

### C. Preview

Add stable fixed/bounded preview in R16 Now Playing: current and adjacent lines, loading/retry/instrumental/no-lyrics states, tap to full view. Height does not change with line count. Theme follows readable player palette.

### D. Full lyrics

Large active line, matte inactive lines, synchronized scroll, user-scroll suspension, return-to-current affordance, edge fade, back/collapse behavior, offline cache, large text/TalkBack. Sharing only where source/licensing permits.

### E. Migration compatibility

Use migrated R15 lyrics cache when fingerprint-valid. Do not silently present stale lyrics for changed canonical identity. Preserve backup/export coverage.

## Edge cases and failure behavior

- Rapid A→B→C skips and slow A response.
- Same title/different version.
- Metadata override invalidates cache.
- Instrumental/no lyrics.
- Offline cache.
- Unsynced lyrics and malformed timestamps.
- User scroll versus auto-scroll.
- process/configuration change.

## Performance constraints

DB/network off main thread. Cache lookup indexed. Position updates only change current-line state; no full parse/layout every tick. Large lyric lists use efficient RecyclerView/text handling. Requests are cancellable and deduplicated.

## Verification

Repository/cache/fingerprint tests, stale-result coordinator tests, parser tests, player state tests, UI state/layout regression tests, migration/backup, and LYR-001–LYR-009 physical acceptance.

## Done means

Lyrics are canonical, offline-capable, stale-safe, visually integrated, stable in size, and accessible without creating playback state or performance regressions.

## Escalate only when

A lyrics source's licensing forbids required cache/share behavior, or canonical request identity cannot safely distinguish versions.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
