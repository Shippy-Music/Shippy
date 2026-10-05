# Search and responsiveness review — 2026-10-04

Baseline: `92bb3c417` on `main`. Review only; no application fixes or builds performed.

The owner reports dead playlist search buttons, search leaving its collection,
and sluggish/stale screens. Search must remain within the collection where it
was invoked, including Liked, Downloads, Local, and user playlists.

## Runtime distinction

The APK version alone does not establish which implementation is running.
`MainActivity.kt:89` chooses a legacy or R16 host from the durable startup state.
`R16StartupState.kt:43` maps a missing bootstrap marker to LEGACY; the validated
active marker maps to ACTIVE in `R16AuthoritySelector.kt:91`.

The legacy collection screen has a toolbar search icon. The current R16
collection screens have inline text fields. These are different implementations.
The reported dead icon matches a confirmed legacy defect below, but the installed
build and runtime have not been identified. No phone was attached to ADB.

## Findings

### 1. High: legacy collection search cannot expand its input

**Source-confirmed integration defect.**

- `app/src/main/java/org/oxycblt/auxio/shippy/library/ui/ShippyCollectionDetailFragment.kt:215`
  calls `item.expandActionView()` and reports the click handled.
- `app/src/main/res/menu/shippy_collection_detail.xml:6` supplies an AppCompat
  SearchView as a collapsible action view.
- `app/src/main/java/org/oxycblt/auxio/ui/AuxioToolbar.kt:194` creates a bare
  MenuBuilder and material buttons. It has no menu presenter or implementation
  to attach, expand, focus, or collapse an action view.

The query listener exists, but the search view is not displayed. This affects the
shared legacy screen for Liked, Downloads, Local, and user playlists. Repair the
toolbar contract or use an explicit inline input owned by the collection screen.
Do not route this action to global Search.

### 2. High: the same toolbar loses dynamic menu changes

**Source-confirmed integration defect, independent of typing.**

`AuxioToolbar.kt:239` snapshots visible menu items only during inflation and
captures the overflow list. Collection rendering subsequently changes item
visibility (`ShippyCollectionDetailFragment.kt:329,346`), but no presenter or
rebuild observes those changes. The popup also does not re-filter visibility.

Actions initially hidden, such as playlist rename/delete and selection actions,
can remain absent when the collection loads. Buttons meant to disappear during
selection can remain visible. Fix this shared component rather than patching
each consumer independently; check its other consumers afterward.

### 3. High: R16 progress updates restart unchanged artwork

**Source-confirmed redundant work; device jank magnitude not measured.**

`R16NowPlayingFragment.kt:59,176,188,221,420` runs a 500 ms ticker through the full
screen render and unconditionally calls `bindArtwork`. `CoverView.kt:482,514,546`
constructs a fresh image request, disposes the previous request, and enqueues
another even when the artwork is identical. Cache hits do not eliminate request,
placeholder, allocation, and binding work. The ticker also schedules itself both
after `renderCurrentState()` and at the end of `render()`; callback removal keeps
this from being an exponentially growing loop, but scheduling has two owners.

Update progress/time/lyric position on ticks. Bind metadata and artwork only when
their identity changes, and give ticker scheduling one owner.

### 4. High: collection filtering does expensive work on every keystroke

**SQL plan reproduced on host SQLite; device timings not measured.**

R16 detail ViewModels create a new Pager immediately on each changed query, with
no debounce (`R16SystemCollectionDetailViewModel.kt:45` and
`R16LibraryPlaylistDetailViewModel.kt:123`). The repository's active filtering
path uses substring LIKE, not its FTS helpers (`R16LibraryReadRepository.kt:161`).

Using the exported schema v6 and the actual DAO SQL, host SQLite EXPLAIN reports:

- `filterSystemCollection`: `SCAN r USING INDEX sqlite_autoindex_recording_1`,
  correlated asset/artist lookups, and `USE TEMP B-TREE FOR ORDER BY`.
- `filterPlaylist`: an indexed playlist-entry restriction, followed by substring
  filtering and metadata lookups. It is collection-scoped, but text is not FTS
  indexed. Do not describe this as an unscoped whole-catalogue playlist query.

Paging bounds result delivery, not all database work required to locate results.
The legacy screen separately filters and sorts its complete in-memory collection
on the UI thread (`ShippyCollectionDetailFragment.kt:359`), including redoing this
work on collection render updates.

Preserve intended substring semantics when optimizing: simply substituting FTS
prefix matching changes which songs match. Scope candidates efficiently, add a
short cancellable typing debounce, and measure actual queries on large fixtures.
Move legacy whole-list projection off the UI thread if that runtime remains used.

### 5. Medium: a slow online provider delays all provider results

**Source-confirmed latency coupling, shared by both search implementations.**

`UnifiedSearchRepository.kt:69` starts provider requests concurrently but uses
`awaitAll()` before returning any sections. R16's coordinator waits for that
single response before publishing provider results
(`R16GlobalSearchCoordinator.kt:125`). Independent retry UI does not make initial
results independent. Local results can arrive earlier; this finding concerns
provider results. HTTP has 8-second connect and 10-second read timeouts.

Publish each provider completion into its existing stable section while slower
providers retain their own loading/error state. Keep generation cancellation.

### 6. Medium: R16 recent listening can stay stale after tab switches

**Source-confirmed refresh/lifecycle mismatch.**

`R16HomeHistoryViewModel.kt:56` loads a one-shot recent list. Its only caller is
`R16HomeFragment.onStart()` (`:108`). `MainActivity.kt:159` hides tabs at STARTED,
then shows them at RESUMED. Returning Home does not call onStart again, and the
list is not subscribed to history changes. New listening history can therefore
remain missing until another lifecycle restart.

Use a bounded observable history projection, or an explicit visible-screen
refresh policy. This is stale state, not evidence that the database is slow.

### 7. Medium: hidden R16 screens remain active consumers

**Source-confirmed lifecycle mismatch; total performance cost not measured.**

Both tab switching and `pushR16Destination` keep hidden origins STARTED
(`MainActivity.kt:159,380`). Home and detail collectors use STARTED, and media
connections disconnect only in onStop. Now Playing clears its ticker only in
onStop/onDestroyView, so covering it with a detail such as Queue can leave it
rendering artwork and progress behind the visible screen.

There is also a behavioral risk with a concrete path: Library Songs and playlist
details register lifecycle Back callbacks. Those remain active at STARTED.
A hidden Songs screen with a nonempty query can consume Back on another tab;
a hidden playlist can clear selection/query when Back should close its overlay.
See `R16LibrarySongsFragment.kt:71,107` and
`R16LibraryPlaylistDetailFragment.kt:89,146,478`.

Align UI collectors, tickers, and Back handling with the visible RESUMED screen,
or lower hidden lifecycles while preserving view/state. Playback service work
must remain independent of screen visibility.

### 8. Medium: system collection playback discards its visible filter

**Source-confirmed inconsistency.**

R16 system-collection rows filter correctly within their collection, but Play,
Shuffle, and row clicks send only collection/recording identity, without the
query (`R16SystemCollectionDetailFragment.kt:115-137`). The resolver then loads
the full collection (`R16BrowserQueueResolver.kt:83`). User playlists do pass
their filter and sort (`R16LibraryPlaylistDetailFragment.kt:302`).

Consequently, a filtered Local/Liked/Downloads list can start a queue containing
hidden nonmatching songs, unlike a filtered user playlist. Use the same explicit
search/playback context contract across both collection types.

### 9. Medium: Library search entry points are inconsistent

**Source-confirmed wiring; exact reported redirect not device-reproduced.**

The legacy Library toolbar explicitly navigates to the shared Search screen with
`localOnly=true` (`HomeFragment.kt:184`). That avoids provider calls, but it has no
collection ID and does not mean “search this playlist.” It explains reuse of the
main search surface, not a proven redirect from the collection's own dead icon.

In R16, inline playlist/system-collection fields do retain their collection
scope. A dedicated `R16LibrarySearchFragment` exists, but a reference search
found no production construction/navigation to it; `R16LibraryFragment` embeds
six tabs without a Library-wide search action. Global bottom-tab Search is a
separate route and clears the detail back stack.

Make three scopes explicit: global discovery, Library, and this collection.
Keep collection search in-place and preserve its query, scroll, and Back behavior.

## Verification and coverage gaps

- Inspected both runtime selectors/hosts, collection navigation and filtering,
  Library read models/SQL, online search aggregation, Home refresh, player
  rendering/artwork, lifecycle ownership, browser queue context, and relevant
  tests/performance evidence. This is not a line-by-line audit of every subsystem.
- The existing graph was queried as a navigation aid. It contains old paths and
  legacy nodes; conclusions above were checked against current source.
- Reproduced the actual filter query plans using in-memory host SQLite created
  from the exported v6 schema. This is plan evidence, not Android timing data.
- Existing performance-plan tests still exercise FTS queries, while the current
  collection filtering repository selects LIKE queries. A passing older plan
  assertion does not verify this active path.
- No attached ADB device. No Android build, instrumentation, benchmarks, or JVM
  suite was run during this review. `docs/r16/PERFORMANCE.md` records no physical
  runtime measurements; historical green builds are not responsiveness evidence.
- UI integration coverage is especially important here: a ViewModel/DAO search
  test cannot catch a toolbar that never displays its input.

## Recommended order

1. Identify the installed build and active runtime; reproduce the collection
   search and navigation behavior on that path.
2. Repair collection search and the shared toolbar integration; cover the actual
   tap → input → scoped results → clear/Back sequence for all collection types.
3. Remove repeated player artwork binding and hidden-screen UI work; fix Home
   refresh ownership.
4. Optimize and measure the actual collection filter queries while preserving
   matching semantics; stream provider sections independently.
5. Make filtered playback context consistent, then run a bounded device pass for
   typing, list scrolling, Now Playing/Queue, tab changes, and slow-provider cases.

No app source changes, commits, or pushes were made by this review.
