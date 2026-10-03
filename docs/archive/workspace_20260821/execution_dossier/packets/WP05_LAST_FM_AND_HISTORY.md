# WP05 — Last.fm and History

**Priority:** P1  
**Primary owner:** Terra: Last.fm/history owner  
**Dependencies:** WP00  
**Parallel safety:** Parallel-safe with offline/lyrics; serialize Home/settings/listening-session shared edits.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C02_IDENTITY_AND_METADATA.md`, `contracts/C06_INTEGRATIONS.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Finish optional, deeply integrated Last.fm and local history without making disconnected users pay any UI, network, storage-work, or cognitive cost.

## Current repository state

R16 listening sessions finalize with canonical occurrence/source identity; play history and Last.fm outbox repositories exist; WorkManager delivery/Last.fm client foundations exist; Home history/Continue Listening exists. Product connection state, disconnected intent, recommendations/profile modules, complete status, history retention/clear, and real-account validation are incomplete.

## Target outcome

Disconnected Last.fm disappears. Connected Last.fm reliably sends now-playing/scrobbles from canonical sessions, survives offline, exposes restrained discovery/profile/history, handles reauth/ignored truth, and never duplicates or scrobbles sessions completed while disconnected.

## Locked packet invariants

- Local history always remains independent of Last.fm.
- Scrobble eligibility uses monotonic audible time and canonical metadata.
- Queue reorder/shuffle does not change session identity.
- Outbox is account/session-aware, idempotent, bounded, and FIFO.
- Provider/source changes do not change the Recording being scrobbled.
- Disconnect stops new Last.fm work and removes Last.fm UI.

## Non-goals

Do not make Last.fm an audio provider. Do not show empty Last.fm cards when disconnected. Do not infer connection state in the data repository by reading UI preferences. Do not create a live-feed-heavy Home.

## Current code map

- R16 listening session delivery/repository
- `R16LastFmOutboxRepository`, DAO/entity
- app Last.fm client/credentials/worker/scheduler and settings
- R16 Home/history read models/UI
- canonical provider observation/artwork fallback for recommendations
- migration/backup/clear-history support
- Last.fm tests

## Implementation work

### A. Connection-scoped policy

Capture account ID/session generation and scrobbling-enabled intent when a listening session begins or before finalization. Persist local history regardless; enqueue only for the captured connected account. Disconnect invalidates future intent but does not delete already-authorized pending outbox unless owner explicitly clears/signs out per policy.

### B. Now Playing and scrobble delivery

Send now-playing when audible playback starts with usable metadata. Final scrobble uses canonical artist/title, optional release, duration, start timestamp, and stable listening session ID. Parse accepted versus ignored, retry/reauth/drop truth, prevent duplicate delivery, and expose a small status model to relevant UI.

### C. Discovery/profile/Home

When connected, add one restrained Home module by default (recommended), profile/play-count/top/recent/similar data with bounded cache and manual refresh. Resolve recommendation metadata to canonical/source observations progressively; do not block Home or create Library membership. Artwork falls back through canonical/provider search.

### D. History

Implement 365-day raw-session default, lightweight aggregates longer, clear-history controls, and privacy truth. Continue Listening uses current/checkpoint/history appropriately and starts playback. History rows remain displayable even if transient catalogue rows are GC'd.

### E. Account transitions

Sign-in, reauth, sign-out, account change, offline, invalid session, clock changes, and network recovery are explicit. Outbox rows cannot cross accounts accidentally. Last.fm settings/UI vanish or reset appropriately after disconnect.

### F. Identity support

Last.fm metadata/MBID may contribute evidence to Identify Track, with provenance/trust and conflict handling. It never supplies a playable source.

## Edge cases and failure behavior

- Local unidentified filename metadata.
- Pause, seek, buffering, speed changes, replay, rapid skip.
- Shuffle/reorder and duplicate queue occurrences.
- Offline completion then reconnect.
- Ignored response, partial batch acceptance, invalid session.
- Disconnect with pending authorized outbox.
- Account switch and stale worker.

## Performance constraints

One-second audible tracking is low-frequency and does not reconstruct rich queues. Delivery is batched/bounded with WorkManager constraints/backoff. Home cache is bounded and not refreshed on every open. Recommendation provider resolution is capped/cancellable.

## Verification

Listening policy tests, account-generation/outbox idempotency tests, accepted/ignored/reauth parsing, shuffle/reorder tests, history retention/clear tests, Home state tests, migration/backup, and physical LFM-001–LFM-018 on a real account near beta.

## Done means

Disconnected users see/produce no Last.fm behavior; connected users get reliable canonical scrobbles and restrained discovery; history is useful, private, clearable, and independent.

## Escalate only when

Last.fm API credentials/terms require an owner/legal decision, or disconnect semantics for already-authorized pending scrobbles are explicitly changed.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
