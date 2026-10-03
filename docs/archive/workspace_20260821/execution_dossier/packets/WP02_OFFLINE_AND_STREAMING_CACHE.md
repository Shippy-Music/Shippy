# WP02 — Offline and Streaming Cache

**Priority:** P1  
**Primary owner:** Terra: offline owner  
**Dependencies:** WP00  
**Parallel safety:** Parallel-safe with Last.fm/Lyrics if shared runtime/schema edits are serialized.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C05_OFFLINE_CACHE.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Turn the existing durable download pipeline into a complete user-facing offline product and add the missing bounded R16 streaming-cache layer.

## Current repository state

The latest source has a durable offline repository, worker, scheduler, execution coordinator, exact-source resolver, private transfer staging reuse, SAF pending publication, destination/Media3 verification, managed asset storage, scanner suppression, and focused tests. It lacks a complete R16 cache, cache settings/promotion, product actions/progress surfaces, and final active-runtime wiring.

## Target outcome

Provider streams cache transparently under bounded policy; complete cache can become a verified permanent download; users can request, observe, pause/retry/cancel/remove downloads; Downloads collection and Now Playing/track actions reflect exact durable truth; scanner never duplicates managed downloads.

## Locked packet invariants

- Cache and download remain distinct.
- Download jobs resume the exact persisted source/variant/destination.
- Publication order remains private-stage → verify → pending SAF → verify → durable asset.
- No filename adoption.
- Source fallback for playback does not rewrite a download job.
- Remove download never deletes Recording, likes, playlists, or unrelated assets.

## Non-goals

Do not replace the current coordinator with a generic download framework. Do not move provider search into the worker. Do not add a separate local scanner. Do not block first playback on complete caching.

## Current code map

- `app/.../r16/offline/**`
- `shippy-data/.../offline/R16OfflineRepository.kt` and download DAO/entities
- playback locator/source repository and Media3 adapter/cache configuration
- managed asset registry/filtering filesystem
- R16 Downloads system collection and Now Playing/row action surfaces
- settings and WorkManager composition
- offline/cache tests, benchmark journeys

## Implementation work

### A. Finish download product wiring

Expose exact download request construction from selected Recording/Source/variant/destination. Persist before scheduling unique work. Add observable job state/progress to Now Playing, collection rows, Downloads, and an action sheet. Implement pause, resume, retry, cancel, remove, grant repair, and truthful failure messages. Keep UI keyed to captured Recording/QueueEntry/job identity.

### B. Add R16 streaming cache

Use one process-owned Media3 cache behind the R16 player engine. Cache key is stable source identity plus media variant/quality, never an expiring URL. Persist only bounded metadata needed for retention/promotion; cache bytes remain disposable. Integrate source/header refresh without changing key.

### C. Policy/settings

Implement adaptive default from master spec: 2 GB target, safe free-space floor, 30-day unused eviction, configurable size and Never/7/30/90-day age. Clear cache is explicit and does not remove downloads. Eviction never removes actively read/pinned/promotion-in-progress entries.

### D. Cache promotion

Detect a complete verified cache span. On Download request, reuse/copy it into private staging, then follow the same publication verifier/transaction as a network transfer. Partial cache may seed transfer only if byte-range correctness is proven; otherwise redownload safely.

### E. Source selection

Feed verified cache availability into source preparation while preserving ranking: best permanent verified asset by preference/quality/reliability, then complete cache/provider as policy dictates. Cache is not a new Recording or durable Library relationship.

### F. Recovery and cleanup

On restart reconcile WorkManager, jobs, private stages, pending SAF docs, published assets, and scanner suppression. Clean orphan private stages/pending docs conservatively. Low storage and revoked grants produce user-action state, not tight retry loops.

## Edge cases and failure behavior

- Same filename in destination.
- Duplicate jobs for same Recording/source/variant/destination.
- Shared published asset referenced by multiple jobs.
- Restart/cancel during transfer, verify, or publish.
- Provider URL expires mid-transfer.
- Destination grant revoked or low storage.
- Scanner runs during pending publication.
- Cache eviction during playback/promotion.

## Performance constraints

No full-file memory buffering. Transfer, checksum, copy, and verify stream. Progress writes are throttled. Cache index and eviction are bounded/indexed. Playback cache read remains off main thread. Work is unique/idempotent with constraints/backoff.

## Verification

Repository/state-machine tests, worker restart/cancellation tests, SAF fake/instrumentation tests, cache key/eviction/promotion tests, scanner duplicate tests, source-selection tests, and later physical offline journeys OFF-001–OFF-014. Build/lint after integration.

## Done means

A user can stream, hit cache, promote/download, restart, go offline, play the same canonical Recording, remove cache/download independently, and never see a duplicate local track. Every failure has durable recoverable truth.

## Escalate only when

The selected download destination policy needs a new owner behavior not covered by defaults, or Android storage behavior forces a user-data tradeoff.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
