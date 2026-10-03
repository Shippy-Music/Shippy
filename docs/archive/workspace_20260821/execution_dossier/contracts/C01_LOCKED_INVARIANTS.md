# C01 — Locked R16 Invariants

These are binding across every packet.

## Identity

- A Recording represents one specific audible recording/version.
- Provider rows, local files, caches, downloads, and Crew media are sources/assets beneath a Recording.
- Exact source identity is stable and idempotent.
- Local file identity is exact; local assets are never merged solely by title/artist.
- `PlaylistEntryId` and `QueueEntryId` preserve duplicate occurrences.
- Automatic matching is conservative, conflict-aware, auditable, and reversible.
- User overrides/manual decisions outrank later metadata refresh.
- Redirect graphs are acyclic; unmerge/undo preserves user data.

## Playback

- One process-scoped PlaybackCoordinator owns logical queue/playback state.
- Media3 is an engine/projection, never a second product authority.
- Every surface derives current identity from the same QueueEntryId/generation.
- Shippy owns traversal/shuffle; Media3 shuffle remains disabled.
- Async work carries generation/queue/entry identity and stale results are discarded.
- Source fallback may change the source, never the uncertain Recording.
- Large queues use lightweight intent and bounded preparation/presentation.

## Data and Library

- Catalogue knowledge, Library membership, cache, download, and availability are distinct.
- Search results do not enter Library automatically.
- Library/system collections are derived from durable relationships/assets.
- Playlist custom order is canonical; temporary sorting never mutates it.
- Database operations are scoped/indexed/paged; presentation does not reconcile the full catalogue.
- Downloads are verified managed assets and cannot reappear as independent local tracks.
- No destructive migration or unexplained data loss.

## Integrations and UX

- Last.fm is effectively absent when disconnected.
- Lyrics/Last.fm/Crew use canonical identity and cannot create competing playback state.
- Provider failure remains local to that provider/source.
- R16 preserves Shippy/Auxio visual fidelity while modernizing internals.
- M3 Expressive is restrained and purposeful; no visual rewrite for its own sake.

## Release truth

- “Implemented” is not “device tested” or “beta ready.”
- Legacy authority is removed only after R16 replacement and migration/rollback proof.
- Appendix V must be evidenced, not checked by assertion.
