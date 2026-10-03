# LT04A — Complete Releases/Albums and Genres read models

**Parent packet:** `WP04`  
**Execution wave:** 2  
**Dependencies:** `LT00B`  
**Luna ownership:** Release/genre schema/read models/Paging/UI and context playback, with Terra applying shared schema/nav integration.  
**Terra-owned/shared seams:** Terra owns schema version/migrations, Library shell tabs, browser contract, and shared resources.

## Assignment

Canonical Release and Genre facets are queryable, paged, searchable, playable and saved according to durable Library semantics, with lightweight complete playback contexts.

## Why this task exists in the current worktree

R16 Songs, Playlists, Artists and system collections exist. The Release graph is not live in the product, Library Search visibly disables Releases, and Genres are absent. The master product requires Albums and Genres without making provider catalogue observations automatic Library membership.

## Locked decisions

- Release identity is distinct from Recording.
- Saving an Album is a durable release relationship, not liking every Recording.
- Genre is metadata/facet, never identity evidence.
- Provider release observations do not enter Library automatically.
- Context playback uses Recording IDs/origins only.

## Explicit non-goals

- Do not make a remote provider catalogue cache into Library.
- Do not normalize genres so aggressively that unrelated facets collapse.
- Do not hydrate full releases for playback queues.

## Start with these repository surfaces

- `shippy-core/src/main/kotlin/app/shippy/core/music/MusicModels.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/entity/RecordingEntities.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/ReadModelDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/view/LibraryViews.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/library/R16LibraryReadRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/browser/R16MediaBrowser.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/browser/`
- `app/src/main/res/layout/fragment_r16_library_search.xml`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Finish canonical release persistence

Use existing release/release-track/artist-credit model if present; add only missing durable saved-release relation and derived read/FTS structures. Terra performs migration/backup updates.

### 2. Add read models

Paged Albums, release detail recordings, saved status, artwork/artist/year/kind; bounded Genre facets and detail. Use deterministic orders and indexes.

### 3. Add UI

Albums and Genres tabs/list/detail, search integration, empty/error/loading, long-press actions, save/unsave for release. Reuse R16 adapters/tokens instead of legacy domain objects.

### 4. Add context playback

Play/Shuffle exact visible release/genre order through MediaBrowser extras and complete lightweight context. Fresh QueueEntryIds, origin references retained.

### 5. Integrate Library Search

Enable release/genre targets only when live; Global Search remains provider-aware and distinct.

## Edge cases that must be handled

- compilations/multiple artists
- same release title different artists/year
- unknown release
- recording on multiple releases
- genre aliases/casing
- empty/unresolved release
- 10k genre context

## Verification contract

- DAO/Paging/query-plan tests.
- Schema migration/backup if changed.
- Browser context tests.
- UI ViewModel/state tests.
- Large fixture checks.

## Completion contract

Albums and Genres are first-class performant R16 Library surfaces with correct durable membership and playback context.

## Return to Terra instead of improvising when

- The existing schema cannot express saved Release without a broader owner decision.
- Genre product behavior conflicts with authorized defaults.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
