# LT00D — Preserve full Songs playback context

**Parent packet:** `WP00`  
**Execution wave:** 0  
**Dependencies:** None  
**Luna ownership:** Songs query/context construction and MediaBrowser extras/tests.  
**Terra-owned/shared seams:** Terra owns shared browser contract/router edits and PlaybackCoordinator central files.

## Assignment

A Songs row, Play, or Shuffle action submits one complete lightweight context matching the current visible search/sort and exact selected Recording, with stale-query protection and no rich full-list hydration.

## Why this task exists in the current worktree

`R16LibrarySongsFragment.play(row)` currently sends only a Recording media ID. Playlist, artist, and system-collection surfaces already resolve complete lightweight contexts. A Songs row tap can therefore collapse playback into a one-song queue instead of the visible canonical Songs ordering/filter.

## Locked decisions

- Queue entries get fresh QueueEntryId values.
- The selected Recording and context generation/filter must be revalidated.
- Search/filter order used for playback equals the visible order.
- Context queries return lightweight IDs/origins, not artwork-rich rows.
- No direct UI access to PlaybackCoordinator.

## Explicit non-goals

- Do not add new Songs sorting UX unless already required by the active design.
- Do not load all rich Song rows into memory.
- Do not change playlist/artist contexts.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16LibrarySongsFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/R16LibrarySongsViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/browser/R16MediaBrowserContract.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/browser/R16BrowserQueueResolver.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/browser/R16MediaBrowserServiceAdapter.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/browser/R16MediaBrowser.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/library/R16LibraryReadRepository.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/r16/browser/R16BrowserQueueResolverTest.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define Songs context extras

Encode only the current normalized query/sort/generation data required by the service to reconstruct the ordered context. Keep extras strict and bounded.

### 2. Resolve context in data/browser layer

Add or reuse a lightweight ordered Recording seed query that applies the same membership/search semantics as the PagingSource. Resolve selected Recording to an index by ID, not current adapter position.

### 3. Route through one command path

The fragment/controller sends media ID plus context. The service/router resolves and submits one `PlayContext`; optional Shuffle supplies deterministic seed using existing command semantics.

### 4. Reject stale/malformed context

Invalid extras, missing selected Recording, changed generation where required, or empty results fail closed with a user-visible/traceable rejection rather than playing a different track.

## Edge cases that must be handled

- duplicate sources representing same Recording
- query changes before service handles tap
- selected row removed
- empty query vs filtered query
- 10k Songs context
- controller connects late

## Verification contract

- Browser resolver/router tests for selected index, search filter, malformed extras, stale selected ID, and 10k lightweight context.
- ViewModel/query tests prove Paging and context semantics align.
- Focused app/data tests and compile.

## Completion contract

Tapping any Songs row starts the complete intended canonical Songs context and the visible selected Recording without whole-rich-list work.

## Return to Terra instead of improvising when

- The current Songs sort/filter state cannot be reconstructed without a cross-packet Library product decision.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
