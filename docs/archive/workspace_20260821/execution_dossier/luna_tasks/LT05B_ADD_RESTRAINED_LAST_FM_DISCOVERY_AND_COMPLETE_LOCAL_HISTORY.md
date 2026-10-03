# LT05B — Add restrained Last.fm discovery and complete local history

**Parent packet:** `WP05`  
**Execution wave:** 3  
**Dependencies:** `LT05A`, `LT03A`  
**Luna ownership:** Last.fm overview/discovery repositories, R16 Home/history/settings UI and tests.  
**Terra-owned/shared seams:** Terra owns shared Home shell/layout and provider-ingestion integration.

## Assignment

Connected users receive one restrained Last.fm Home/discovery module plus profile/top/recent/similar data; local history is paged, clearable and useful independently; recommendations remain transient until user intent.

## Why this task exists in the current worktree

R16 Home/history exists and Last.fm overview code exists in the legacy Shippy package, but the connected-only discovery/profile module, 365-day retention controls, aggregates, clear-history behavior and progressive recommendation-to-canonical resolution are incomplete.

## Locked decisions

- All Last.fm UI disappears when disconnected.
- Recommendation display does not create Library membership.
- Provider/source resolution is progressive and bounded.
- History clear does not silently clear Last.fm account history.
- History presentation survives transient catalogue GC.

## Explicit non-goals

- Do not create a Spotify-like endless feed.
- Do not make Home wait for provider or Last.fm network.
- Do not persist every recommendation durably.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/LastFmOverview.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/home/LastFmHomeViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/home/R16HomeFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/home/R16HomeHistoryViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/home/R16HistoryFragment.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/home/R16HomeReadRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/HistoryDao.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/source/R16ProviderObservationRepository.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Create connected-only discovery state

Profile/status, top/recent/similar/recommendation sections with bounded cached timestamps, refresh and isolated failures. Default to one calm recommendations module.

### 2. Resolve recommendations

Map Last.fm metadata/MBIDs into candidate observations, search enabled providers progressively, link conservatively through existing ingestion, and keep results transient until play/save.

### 3. History policy

Implement 365-day raw session retention and lightweight longer aggregates where schema supports. Add clear all/range controls, confirmation and operation truth.

### 4. Home composition

Continue Listening, horizontal Recently Played, recommendations when connected, smart recent. Remove redundant static Your Music/Recent Downloads patterns. Never block local Home on network.

### 5. Artwork

Use canonical art first, provider lookup fallback, then clean neutral fallback; no star placeholders.

## Edge cases that must be handled

- Last.fm partial failure
- no artwork
- recommendation has no playable source
- history Recording GC
- clear during active session
- large history
- account disconnect while Home visible

## Verification contract

- Repository cache/retention/clear tests.
- Home state and disconnected visibility tests.
- Recommendation cancellation/ingestion tests.
- Paging/large-history tests.
- Physical connected/disconnected journey near beta.

## Completion contract

Home/history are useful and responsive; Last.fm discovery is deep when connected and nonexistent when disconnected.

## Return to Terra instead of improvising when

- Last.fm API lacks required data under current credentials/terms.
- Owner wants a different default Home module.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
