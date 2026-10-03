# WP04 — Library, Playlist, and Queue Completion

**Priority:** P1  
**Primary owner:** Terra: Library product owner  
**Dependencies:** WP00  
**Parallel safety:** May split Release/Genre reads, save/actions, and queue interactions after DB/browser contracts are fixed.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C04_DATA_MIGRATION.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Complete the R16 Library and organization experience around canonical Recording identity, preserving exact occurrence order and large-list performance.

## Current repository state

Songs, Playlists, Artists, system collections, Global/Library/playlist search, playlist sorting, create/rename/delete/pin, entry add/remove/adjacent move, one-way Like, single-playlist destination picker, and queue paging/GoTo exist. Releases/Albums and Genres are absent. Save Destinations, Unlike, membership editing, batch actions, full queue mutation, import/export, artwork editing, and some playlist operations are incomplete.

## Target outcome

The recommended Songs/Playlists/Albums/Artists/Genres Library is coherent and customizable; playlists expose every required operation; Save/Like is transactional and occurrence-safe; queue supports real manipulation; all collection playback preserves visible context and scales to 10k entries.

## Locked packet invariants

- Playlist duplicates remain legal and distinguishable.
- Temporary search/sort never overwrites custom order.
- Library membership follows durable relationships, not provider catalogue presence.
- System collections cannot be renamed/deleted.
- Context play uses lightweight complete order and exact origin IDs.
- No UI action retargets when current playback changes.

## Non-goals

Do not replace Paging with in-memory lists. Do not duplicate pinned/order authority in preferences. Do not make every provider release automatically a saved Library album. Do not solve queue UX by mapping the entire queue richly.

## Current code map

- R16 Library fragments/ViewModels/adapters
- `R16LibraryReadRepository`, mutation repository, media browser, read-model DAO/views/FTS
- playlist DAO/sparse order, library membership/layout triggers
- browser queue resolver/router and queue page endpoint
- Now Playing Like/destination picker and action resources
- new Release/Genre read models and UI
- backup/migration coverage for new durable relationships

## Implementation work

### A. Releases/Albums

Complete canonical Release graph/read models and Library semantics. Separate saved Release relationship from liking all tracks. Add paged Albums tab, release detail with complete lightweight context Play/Shuffle, save/unsave, artwork/metadata, and Library Search integration. Provider release observations may enrich catalogue but do not enter Library until saved/owned by durable rules.

### B. Genres

Model genre/tag facets conservatively as metadata, not identity. Use normalized bounded facets from local/trusted observations. Add paged Genres tab/detail and context playback. Do not let genre normalization merge recordings.

### C. Library tab management

Implement hide/reorder nonessential tabs via Settings while preserving core navigation and state/scroll restoration. Persist one authority. Temporary active-shell buttons are replaced later by WP08 shell integration.

### D. Full Save Destinations and Like

Replace the single-playlist picker with the full bottom-sheet workflow: captured RecordingId/QueueEntryId header, Liked selected state, current playlist memberships, create playlist, add/remove multiple destinations transactionally, explicit Unlike with Undo per recommended default, loading/failure truth, and stale-identity protection.

### E. Playlist operations

Complete artwork set/clear, export/share/import, download missing eligible entries, full custom reorder/drag with accessible alternative, batch selection/actions, pin/order editing, and long-press sheet. Maintain sparse order and exact PlaylistEntryId duplicates. Reorder while filtered is either safely prohibited with explanation or applied according to explicit visible-order semantics from the master spec.

### F. Queue operations

Add QueueEntryId-based remove, move/drag, Play Next, Add, clear/replace where product allows, and multi-select. Preserve current occurrence and deterministic traversal/shuffle semantics. Load pages/chunks around visible entries; command results reject stale revision/entry.

### G. Context play consistency

Verify Songs, release, genre, artist, system collection, playlist, history, and provider entity contexts all build complete lightweight queues from current visible order. Continue Listening resumes/starts audio, not merely opening the player.

### H. Search and empty/error states

Ensure Global, Library, Songs-context, and playlist-context scopes are visibly distinct; cancellation/generation prevents stale provider results. Add releases/artists/genres/system targets to Library Search as implemented. Preserve local-results-first Global Search and partial provider failure.

## Edge cases and failure behavior

- Duplicate occurrences and same Recording in multiple lists.
- Deleting/renaming currently open playlist.
- Membership sheet stays open while playback changes.
- Reorder during search/filter/sort.
- Queue changes while page/result is in flight.
- Empty release/genre or unresolved entries.
- Import collisions and invalid external playlist data.

## Performance constraints

All tabs/collections use indexed Paging. Full context queries return lightweight IDs/origins only. Ordinary playlist moves touch moving/anchor rows; rebalance is exceptional. Queue presentation is paged. Search is cancellable and provider concurrency capped.

## Verification

DAO/repository/Paging tests, duplicate-occurrence/order tests, membership transaction tests, queue reducer/coordinator tests, browser routing tests, UI state tests, large fixtures, and manual journeys LIB-001–LIB-015/PB queue journeys. Build/lint after integration.

## Done means

A user can organize the entire canonical Library, manage playlists and duplicates, save/unlike across destinations, manipulate the queue, search in the right scope, and play every context without identity mismatch or whole-catalogue work.

## Escalate only when

Songs-tab membership default is explicitly changed by owner, or a Release/Genre product behavior is not covered by recommended defaults.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
