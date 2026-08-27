# Wave 1 review correction — Library and queue

Read master specification sections 31, 49, 74-75 and the existing W1_B packet before editing.

## Outcome

Finish the already-started album/genre context and global queue work, and expose practical
playlist batch movement without changing the original Auxio/Shippy visual language.

## Required corrections

- Preserve the implemented full album/genre playback contexts and selected-occurrence identity.
- Preserve global queue top/bottom semantics against the full queue snapshot.
- Correct the edit-order/search guard: movement is allowed only in edit-order mode and only when
  no search filter is active.
- Add Move to top and Move to bottom actions to the existing playlist multi-select bar using the
  existing compact action/button patterns. No redesign or new navigation pattern.
- Pass the exact selected `PlaylistEntryId` values, preserving duplicate recording occurrences.
- Use loaded adapter order for selected entries and the existing repository transaction.
- Add only focused regression coverage needed for the guard/order behavior.

## Boundaries

- Own playlist detail Fragment/ViewModel/layout/strings and queue/browser focused tests.
- Do not edit `R16LibraryMutationRepository.kt`; the identity/unmerge owner has that file.
- No Gradle invocation, clean, commit, push, schema change, or broad UI restyling.
- Do not touch identity merge logic, GC/cache, Last.fm, or native/taglib paths.

