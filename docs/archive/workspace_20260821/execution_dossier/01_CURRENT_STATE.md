# Current Repository State

## Executive summary

R16 has a strong central architecture but remains an inactive parallel system. R15.3 is still the production database, playback, Library, and UI authority. An isolated R16 composition now exists with one process-shared data runtime, one PlaybackCoordinator, one Media3 projection, one MediaSession/system-surface route, and multiple R16 Library/Search/Home/player surfaces. Durable `R16_ACTIVE` still maps to `ACTIVE_UNAVAILABLE`, and M14 cutover has no production caller.

The implementation has moved beyond architecture scaffolding. Major product paths now exist in code, including canonical Songs, Playlists, Artists, system collections, local and global search, Home/history, Now Playing, queue display/go-to, one-way Like, add-to-playlist, provider observation ingestion, playback checkpointing, listening-session persistence, Last.fm outbox delivery, and a substantial R16 download pipeline.

The remaining work is not “invent R16.” It is to complete missing product semantics, repair several foundation defects, integrate all features onto the same contracts, safely activate the new authority, delete superseded authorities, and prove the final release contract.

## Architecture that should be preserved

### Pure core

`:shippy-core` is deliberately small and remains free of Android, Room, Media3, Musikr, and provider DTO dependencies. It owns typed IDs, Recording/Source/Asset/PlaylistEntry/QueueEntry models, conservative identity matching, redirects, queue reducers, deterministic shuffle, playback reducers, checkpoints, and listening-session logic.

### Normalized persistence

`:shippy-data` owns the independent R16 Room database, scoped repositories, FTS/Paging read models, backup/export, migration M0–M14 machinery, source/asset state, playback checkpoints, history, Last.fm outbox, lyrics cache, download state, identity audit, and catalogue maintenance.

### Source boundary

`:shippy-sources` owns provider/local observation contracts, idempotent ingestion orchestration, managed-asset recognition, source discovery, and event-driven enrichment policy. Android/Musikr/provider-specific adaptation remains in `:app`.

### Playback authority

The R16 playback spine is correctly built around:

- one process-scoped `PlaybackCoordinator`;
- immutable `PlaybackSnapshot`;
- `QueueEntryId` as occurrence identity;
- Shippy-owned traversal and shuffle;
- bounded source preparation and Media3 engine window;
- generation/revision guards against stale async results;
- source-neutral checkpoint/restore;
- system surfaces submitting commands through the same path.

### Scoped product reads

R16 Library and Search generally use Room/Paging/FTS or lightweight browser seeds rather than whole-catalogue in-memory reconciliation. Playlist duplicate occurrences retain `PlaylistEntryId`; queue occurrences receive fresh `QueueEntryId` while retaining origin references.

### Download publication

The latest source contains a serious durable download pipeline: exact requested source/variant/destination identity, private staging, transfer, verification, SAF pending publication, Media3 readability verification, managed-asset publication, scanner suppression, retry/cancel/remove semantics, and WorkManager scheduling. This should be completed and exposed, not replaced.

## Product surfaces already represented

- R16 Home and history/Continue Listening.
- Songs with contextual local search.
- Playlists and playlist detail with search and seven sort modes.
- Artists and artist detail.
- Liked, Local, and Downloads system collections.
- Dedicated Library Search.
- Global Search with progressive provider sections.
- Mini player and full Now Playing.
- Queue paging and exact occurrence GoTo.
- One-way save to Liked.
- Add current recording to one playlist.
- MediaBrowser/MediaSession/system-surface adapters.
- Last.fm durable outbox delivery infrastructure.
- R16 backup and migration UI/runtime.

## Material gaps

1. **Foundation correctness:** local version parsing, candidate retrieval breadth, external-ID trust, disconnected Last.fm intent, Songs contextual playback, and transient retention semantics need correction.
2. **Authority:** corrupt-state recovery, owner-data migration proof, M14 activation, rollback, and final legacy retirement remain open.
3. **Offline ownership:** no complete R16 streaming-cache product; download UX and all user actions are incomplete.
4. **Identification:** manual Identify Track, metadata editor, merge/unmerge UI, negative decisions, and bulk cleanup are absent despite core/schema foundations.
5. **Library completeness:** Releases/Albums and Genres are absent; full Save Destinations, unlike, playlist membership editing, batch actions, import/export, and full queue manipulation are incomplete.
6. **Last.fm:** optional product integration, disconnected invisibility, discovery/profile/history controls, and real-account validation remain incomplete.
7. **Lyrics:** R16 has schema/migration support but no canonical R16 lyrics repository/coordinator/player UI path.
8. **Crew:** legacy Crew is extensive, but it has not been migrated to portable R16 identity and the single R16 playback command path.
9. **UI:** the isolated ACTIVE shell is scaffolding, not fidelity-equivalent to the original Auxio/Shippy shell. M3 Expressive, accessibility, adaptive layout, and consistency work remain.
10. **Performance/release:** benchmarks exist, but final runtime measurements, device evidence, migration evidence, release signing/update path, and Appendix V closure remain open.

## Concrete defects verified in source

### Local version parsing

`MusikrLocalMediaEngine` passes the entire song title to `RecordingVersionParser.parse(...)` in both snapshot fingerprint generation and `SourceTrackObservation`. This can classify an ordinary title such as “Live Forever” as a live version and can classify unqualified titles as `OTHER`. Version parsing must consume explicit version qualifiers or trusted version metadata, defaulting to original when none exists.

### Candidate retrieval is narrower than matching

`RoomR16IngestionRepository.identityCandidates(...)` retrieves metadata candidates through `RecordingDao.candidatesByTitle(...)`, which is exact case-insensitive title equality. The later matcher is more capable, but it never receives variants such as “See You Again” and “See You Again (feat. Charlie Puth)” unless an external ID/fingerprint also connects them. Candidate retrieval must become bounded normalized/FTS retrieval while merge policy remains conservative.

### Last.fm work is not explicitly connection-scoped

`RoomR16ListeningSessionRepository` persists history and creates a Last.fm outbox row when metadata/listen policy is eligible. The data layer does not know whether Last.fm was connected for that listening session. A disconnected user can therefore accumulate work that may become retroactive after connection. Local play history must remain independent, while Last.fm delivery intent must be captured explicitly at session time.

### Songs row playback lacks collection context

`R16LibrarySongsFragment.play(...)` sends only a Recording media ID. Unlike playlist, artist, and system-collection playback, it does not construct the complete current visible Songs context. Unless the router supplies context elsewhere, a row tap becomes a one-item queue. Songs playback should preserve the current sort/filter context without rich-page hydration.

### Transient recordings become effectively permanent through history

Catalogue GC excludes any Recording referenced by `play_history`. Since every listened provider track creates history, a one-off streamed recording can become permanently retained even when not in Library, cache, download, queue, or pending work. Preserve history display/audit without forcing every played Recording to remain fully canonical forever.

## Important owner gates

Only the following should stop ordinary implementation:

- final public application ID/reverse-domain choice;
- unavailable owner migration fixture when the work reaches real migration proof;
- genuinely incompatible product behavior not covered by master-spec recommended defaults;
- legal/provider constraints;
- final Crew exposure if its physical matrix remains incomplete.

All other remaining work has enough direction in the master specification and this dossier to proceed autonomously.
