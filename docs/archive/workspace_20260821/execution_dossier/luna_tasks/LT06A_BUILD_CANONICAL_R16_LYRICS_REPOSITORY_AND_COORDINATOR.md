# LT06A — Build canonical R16 lyrics repository and coordinator

**Parent packet:** `WP06`  
**Execution wave:** 2  
**Dependencies:** `LT00C`  
**Luna ownership:** Lyrics data/source/coordinator code and focused tests, excluding final visual polish.  
**Terra-owned/shared seams:** Terra owns R16DataRuntime and shared playback presentation integration.

## Assignment

R16 lyrics requests are canonical, cancellable and stale-safe; source chain and cache work offline; unrelated playback is never overwritten by late results.

## Why this task exists in the current worktree

The R16 database already carries lyrics cache/migration support and legacy LyricsRepository/Musixmatch source/parser exist, but the active R16 playback path lacks a RecordingId/QueueEntryId/generation-safe lyrics repository/coordinator.

## Locked decisions

- Request identity includes RecordingId, QueueEntryId and playback generation.
- Lyrics failure never affects audio.
- Cache records source/version/timing and is bounded.
- Provider credentials/config remain outside pure core/data.
- Local/provider metadata is normalized before lookup.

## Explicit non-goals

- Do not redesign Now Playing.
- Do not scrape or store unauthorized lyric content.
- Do not reuse legacy Song identity.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/lyrics/LyricsRepository.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lyrics/LyricsCache.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lyrics/MusixmatchBrokerLyricsSource.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lyrics/LrcParser.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/LyricsDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/entity/IntegrationEntities.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/`
- `shippy-data/src/main/kotlin/app/shippy/data/playback/R16PlaybackPresentationRepository.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define R16 request/result

Canonical metadata request plus queue/generation token; states None/Loading/Synced/Unsynced/Unavailable/Failed with source/cache metadata.

### 2. Adapt source chain

Local embedded/sidecar if available, validated cache, configured licensed source(s). Keep cancellation and typed failure. Do not let source DTOs leak upward.

### 3. Cache

Read/write by Recording plus normalized metadata/source version, validate freshness/content, preserve synced timestamps, bound/GC entries, include backup/migration behavior per master.

### 4. Coordinator

Observe PlaybackSnapshot presentation, cancel on identity/generation change, emit only if token still matches, retry/refresh explicit, prefetch only current if justified.

### 5. Parser

Reuse LRC parser with malformed/duplicate/timestamp tests and safe unsynced fallback.

## Edge cases that must be handled

- rapid A→B→C
- same Recording duplicate queue occurrence
- metadata edit while loading
- offline cache
- malformed LRC
- no duration
- source auth/network failure
- process death

## Verification contract

- Repository/cache/parser tests.
- Coordinator stale-result/cancellation/retry tests.
- Migration/backup coverage.
- No audio-state dependency failures.

## Completion contract

The R16 runtime can request and cache lyrics for the exact current occurrence without stale overwrite or playback coupling.

## Return to Terra instead of improvising when

- Lyrics source licensing/API behavior is unclear.
- A source requires storing credentials in a way that violates security contract.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
