# LT04C — Complete playlist management and batch operations

**Parent packet:** `WP04`  
**Execution wave:** 3  
**Dependencies:** `LT04B`  
**Luna ownership:** Playlist/detail operations, batch selection, import/export/share/artwork/download actions and tests.  
**Terra-owned/shared seams:** Terra owns shared schema/browser/shell integration and any new external file format contract.

## Assignment

User playlists expose the full master operation set while preserving PlaylistEntryId duplicates, canonical custom order and paged performance.

## Why this task exists in the current worktree

R16 playlists already support create/rename/delete/pin, seven sorts, contextual search, exact occurrence removal, adjacent custom-order movement, and correct context playback. Long-press/action coverage, artwork, multi-select, arbitrary reorder accessibility, import/export/share and download-missing remain incomplete.

## Locked decisions

- PlaylistEntryId, not RecordingId, owns occurrence operations.
- CUSTOM order is the only persisted reorder authority.
- Temporary sort/search never rewrites custom order.
- System collections are protected.
- Batch actions are bounded/transactional and survive process/config state honestly.

## Explicit non-goals

- Do not materialize 10k rich rows to select/reorder.
- Do not invent cloud sync.
- Do not silently dedupe playlist imports.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16LibraryPlaylistDetailFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16LibraryPlaylistDetailViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16LibraryPlaylistsViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16PlaylistSortSheet.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/library/R16LibraryMutationRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/PlaylistDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/util/SparseOrderKey.kt`
- `app/src/main/java/org/oxycblt/auxio/home/list/ShippyPlaylistDragCallback.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Long-press/action sheet

Pin/unpin, rename, artwork set/clear, export/share, delete, download missing, selection entry. Hide invalid destructive actions for system/protected collections.

### 2. Batch selection

Stable selected PlaylistEntryIds across Paging invalidation; select all visible/query with explicit scope; add to another playlist, remove, like/unlike, download/remove download as supported. Avoid storing rich row objects.

### 3. Reorder

Retain sparse keys. Add drag where reliable plus accessible move-to-position/up/down alternative. Disallow or clearly define reorder under filter/non-CUSTOM sort. Rebalance only affected playlist when necessary.

### 4. Artwork

Persist user-selected URI/reference with permission/validation and fallback collage; clearing returns derived art. Do not copy huge image blobs into DB.

### 5. Import/export/share

Define versioned portable playlist document containing occurrence order and source/canonical hints, not transient URLs. Import preserves duplicates and reports unresolved rows; share can use existing deep-link/source codec where appropriate.

### 6. Download missing

Resolve eligible sources per Recording and enqueue exact jobs without blocking UI; report unsupported/failed entries.

## Edge cases that must be handled

- 10k playlist
- duplicates
- playlist deleted during selection
- Paging invalidation
- reorder with tied keys
- invalid artwork permission
- import same playlist twice
- unresolved provider entries
- batch partial failure

## Verification contract

- Mutation/DAO sparse-order/batch tests.
- Paging selection state tests.
- Import/export round trip and malformed input tests.
- Permission/artwork state tests.
- Large playlist query/reorder benchmark.

## Completion contract

Playlists are fully manageable, occurrence-correct, accessible, portable, and performant without weakening context playback.

## Return to Terra instead of improvising when

- Portable playlist format/provider-link policy needs an owner decision.
- Android document permission cannot satisfy artwork persistence safely.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
