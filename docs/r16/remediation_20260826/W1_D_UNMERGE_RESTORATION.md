# Wave 1D - Exact Merge-Audit Unmerge

## Outcome

Make unmerge restore the relationship graph recorded by the merge audit instead of only flipping
an audit flag.

## Required behavior

1. Inspect the existing merge snapshot/audit schema and `RecordingMergePlanner`; reuse them.
2. In one Room transaction, restore the retired Recording and the exact pre-merge ownership of
   source references, media assets, Library relationships, playlist occurrences, external IDs,
   history links, redirects, and user overrides represented by the audit.
3. Preserve every `PlaylistEntryId`; never collapse duplicates.
4. Reject unmerge when audit data is incomplete rather than inventing state.
5. Mark the audit reversed only after restoration succeeds. A failed restoration changes nothing.
6. Do not add a generic undo framework or redesign identity ingestion.

## Owned files

- `shippy-data/src/main/kotlin/app/shippy/data/library/R16LibraryMutationRepository.kt`
- existing merge/redirect/audit DAO or transaction helpers directly required
- `shippy-data/src/test/kotlin/app/shippy/data/library/R16LibraryMutationRepositoryTest.kt`

Do not edit Identify UI/ViewModel; Wave 2A owns that product wiring.

## Acceptance

- Build a pre-merge graph with sources, asset, liked/saved state, duplicate playlist occurrences,
  identifier, history, redirect, and override.
- Merge then unmerge restores exact pre-merge ownership and removes/marks the redirect correctly.
- Incomplete/corrupt audit produces no partial mutation.
- Run the focused library mutation repository test only.
