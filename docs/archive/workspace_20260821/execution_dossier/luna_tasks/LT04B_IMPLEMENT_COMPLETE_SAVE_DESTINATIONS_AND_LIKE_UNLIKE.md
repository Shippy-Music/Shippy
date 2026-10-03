# LT04B — Implement complete Save Destinations and Like/Unlike

**Parent packet:** `WP04`  
**Execution wave:** 2  
**Dependencies:** `LT00C`  
**Luna ownership:** Save Destinations sheet/ViewModel, Library mutation transactions, and focused UI/data tests.  
**Terra-owned/shared seams:** Terra owns shared bottom-sheet styling/navigation and cross-packet Now Playing edits.

## Assignment

The plus/heart action and row menus open one consistent bottom sheet that atomically edits Liked and multiple playlist destinations for the captured Recording, with stale playback protection and undoable removals.

## Why this task exists in the current worktree

R16 currently supports one-way Save to Liked and adding the current Recording to one playlist. The master behavior requires a captured-identity bottom sheet showing Liked plus all playlist memberships, multi-destination add/remove, playlist creation, explicit Unlike and Undo.

## Locked decisions

- The sheet captures RecordingId and optional QueueEntryId at open time.
- Playback changes cannot retarget the sheet.
- Liked and playlist membership are separate durable relationships.
- Duplicate playlist occurrences are explicit; adding may create one occurrence, removal semantics are visible.
- Operation state survives configuration changes and is acknowledged once.

## Explicit non-goals

- Do not make the sheet depend on provider source.
- Do not silently remove every duplicate occurrence without explicit semantics.
- Do not add a new parallel Like cache.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16NowPlayingLikeViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16PlaylistDestinationPickerFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16PlaylistDestinationPickerViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16PlaylistDestinationPagingAdapter.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/library/R16LibraryMutationRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/LibraryDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/PlaylistDao.kt`
- `app/src/main/java/org/oxycblt/auxio/playback/SavedDestinationsSheet.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define captured sheet state

Header metadata/artwork, Liked state, paged playlist memberships, duplicate occurrence count where relevant, create-playlist action, in-flight/error/effect state.

### 2. Add transactional mutation API

Apply Liked plus selected destination delta in one transaction where feasible; create playlist + add occurrence safely. Return exact change set for Undo.

### 3. Define duplicate removal

Default removal from a playlist removes the membership occurrence(s) explicitly represented by the UI. If multiple duplicates exist, show count and let user remove one/all rather than guessing.

### 4. Like control semantics

Default plus opens sheet. Filled heart reflects Liked. Tapping filled heart opens sheet with explicit Unlike/removal; do not instant-destruct without the documented path. Undo restores exact relationships/order.

### 5. Reuse consistently

Now Playing, mini/row overflow, search/provider detail use the same contract and sheet host where appropriate.

## Edge cases that must be handled

- sheet open while playback skips
- playlist deleted/renamed while open
- duplicate occurrences
- create failure
- partial transaction failure
- rapid repeated tap
- configuration change
- Undo after later membership edit

## Verification contract

- Repository transaction/rollback/duplicate tests.
- ViewModel captured-ID/effect restoration tests.
- Sheet UI state tests.
- Now Playing mapper/control tests.

## Completion contract

Save/Like behavior is consistent, captured, transactional, duplicate-aware, and reversible across all primary song surfaces.

## Return to Terra instead of improvising when

- Owner wants different duplicate-removal or filled-heart semantics than the master default.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
