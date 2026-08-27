# Wave 1B - Context Playback, Playlist Batch Move, and Global Queue Anchors

## Outcome

Finish the already-started Library/queue product contracts without altering playback authority.

## Required behavior

1. Album and Genre row taps submit `AlbumRecording` / `GenreRecording`, not a bare Recording ID.
2. Resolving either occurrence builds the full ordered album/genre context, selects the exact
   tapped occurrence, validates membership, and preserves duplicate-safe occurrence identity.
3. Plain Album/Genre commands resolve their own ordered context, never the generic Songs context.
4. Fix ReleaseId-vs-RecordingId lookup misuse.
5. Make Genre browser IDs round-trip arbitrary UTF-8 genre text, including `:`.
6. Wire multi-selection move in playlist detail to the existing `movePlaylistEntries` repository
   operation. Preserve selected relative order and exact `PlaylistEntryId`s.
7. Queue Move to Top/Bottom targets the canonical global queue boundaries, not
   `adapter.currentList`. Use the existing command/page endpoint and `QueueEntryId`; do not hydrate
   the full queue in UI and do not add a second queue model.

## Owned files

- `shippy-data/src/main/kotlin/app/shippy/data/browser/**`
- matching browser tests under `shippy-data/src/test/**/browser/**`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/browser/**`
- album/genre/playlist detail fragments and playlist detail ViewModel
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16QueueFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/service/R16QueuePageEndpoint.kt`
- directly matching focused tests

Do not edit `R16LibraryMutationRepository.kt`; its batch-move primitive already exists and Wave 1D
owns that file.

## Acceptance

- Album and Genre selection produce full context with correct selected occurrence.
- Genre codec round-trips a value containing `:`.
- Two duplicate playlist entries can be selected/moved without collapsing.
- On a paged 10k queue, Top means global index 0 and Bottom means the canonical final position.
- Run only browser, playlist-detail, queue endpoint/reducer focused tests.
