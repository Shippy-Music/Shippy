# LT02B — Add bounded R16 streaming cache and promotion

**Parent packet:** `WP02`  
**Execution wave:** 2  
**Dependencies:** `LT02A`  
**Luna ownership:** New cache package/repository, Media3 cache wiring, settings/policy, promotion tests.  
**Terra-owned/shared seams:** Terra owns PlaybackSpine/Media3 adapter integration, R16DataRuntime composition, schema migration, and shared settings navigation.

## Assignment

Provider playback uses one bounded process-owned cache keyed by stable source+variant, with adaptive 2 GB/30-day policy, explicit clear, safe eviction, and complete-cache promotion that reuses the existing verify/publication transaction.

## Why this task exists in the current worktree

R16 has permanent-download infrastructure but no complete bounded streaming-cache product. The master contract requires stable source-based cache keys, automatic eviction, optional age/size settings, and promotion of verified complete cache content into the permanent download pipeline.

## Locked decisions

- Cache bytes are disposable; identity/Library/download state is durable.
- Expiring URLs never define cache identity.
- Cache and download remain distinct user actions.
- Eviction never removes active/pinned/promotion-in-progress data.
- No full-file buffering.
- A promoted download remains the same Recording.

## Explicit non-goals

- Do not create a second playback engine.
- Do not persist raw temporary URLs as identity.
- Do not expose partial cache as verified offline download.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/Media3PlayerAdapter.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackLocatorRegistry.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/R16PlaybackSpineFactory.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/playback/R16PlaybackSourceRepository.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/offline/`
- `app/src/main/java/org/oxycblt/auxio/settings/`
- `app/build.gradle`
- `shippy-data/src/main/kotlin/app/shippy/data/db/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define stable cache identity

Key by SourceReferenceId plus normalized media variant/quality. Store enough metadata for completeness, size, last access, and promotion; never key by locator URL.

### 2. Wire Media3 cache

Use one application/process-owned Media3 cache/data-source path in the R16 engine. Preserve request headers and locator refresh. Reads/writes stay off main thread.

### 3. Implement policy

Adaptive default 2 GB with free-space floor and 30-day unused eviction; settings Never/7/30/90 and size options. Clear cache does not remove downloads. Run bounded maintenance with constraints.

### 4. Implement promotion

If a complete verified span exists, copy/stream it into the existing private stage and continue through checksum/readability/SAF publication. Partial data may seed only when range correctness is proven; otherwise safely fetch.

### 5. Feed availability into source selection

A complete cache can satisfy playback offline but does not become Library membership or a permanent Asset. Verified permanent assets still rank ahead according to the master policy.

## Edge cases that must be handled

- locator expiry while cache fills
- range/unknown length
- eviction during playback
- low disk
- clear while active
- quality change
- same source different variant
- process death during promotion
- corrupt cached bytes

## Verification contract

- Cache-key/policy/eviction tests.
- Media3 data-source integration test where host feasible.
- Promotion uses existing download verifier tests plus new complete/partial/corrupt cases.
- Settings serialization and maintenance tests.
- Physical offline/cache journeys near beta.

## Completion contract

Streaming is bounded and reusable, cache can be cleared/evicted independently, and a complete verified cache can become a permanent download without duplicate identity or unnecessary re-download.

## Return to Terra instead of improvising when

- The selected Media3 cache dependency/API conflicts with existing fork and needs a central build decision.
- Storage policy must deviate from authorized defaults.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
