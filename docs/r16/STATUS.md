# Shippy R16 Status

**Updated:** 2026-08-19  
**Active phase:** Phase 3 — R16 Database and Importer  
**Authority:** `../Shippy_R16_Master_Architecture_and_Implementation_Spec.md`

## Execution mandate

The product owner permanently authorized execution of the complete R16 program.
Do not ask for another general "go" after context compaction. Stop only for an
owner decision that the master specification explicitly marks as blocking.

Build and Gradle caches must be retained between builds. Clear them only after a
specific cache-corruption diagnosis.

Treat the master specification as a compass, not a literal transcription task:
its invariants, data-safety rules, authority boundaries, and required outcomes
are binding, while suggested class names, exact commit counts, and implementation
details may adapt to current evidence. Prefer practical coherent slices and
avoid checklist theatre.

## Current authority state

- R15.3 legacy database, playback manager, Library, and UI remain active.
- No R16 runtime authority is active yet.
- R16 feature flags have not been introduced.
- The master specification supersedes conflicting pre-R16 product,
  architecture, implementation, UX, stabilization, and completion claims.

## Last passing gate

- The Aug-19 source archive matches all 1,235 files in the imported worktree.
- The imported snapshot is committed at `491a1abc0` and tagged
  `r15.3-stabilized-20260819` on `r16/architecture-reset`.
- Source ZIP and APK checksums match the documented stabilization evidence.
- The master specification was copied byte-for-byte into the repository and its
  checksum was verified.
- The tall-player seek bar again uses content height while the 304dp preview
  remains owned by the lyrics container.
- Playlist order controls now live in a playlist-only layout and no longer
  inflate on Songs, Albums, Artists, or Genres.
- The focused layout regression suite passes both ownership invariants.
- New playback now validates its target and records the expected queue-item ID
  before any Media3 mutation can synchronously publish a transition callback.
- The service-owned Last.fm observer samples the monotonic playback progression
  each second, counts only advancing position deltas, and refreshes its canonical
  queue snapshot after reorder or shuffle changes.
- Collection detail now observes only its referenced track metadata and download
  jobs, with duplicate-safe ID queries chunked below SQLite's bind limit.
- Now Playing projects at most the current queue item and its two adjacent
  items into artwork display models, then recenters that bounded window after a
  transition; the full canonical queue remains owned by playback and the queue
  screen.
- Local indexing now snapshots exact Shippy-managed artifact and in-progress
  document identities before Musikr ingestion. Exact URI matches are filtered;
  verified artifacts also match the same stable Musikr path and length across
  SAF/MediaStore aliases. Unrelated files in the selected download folder pass
  through normally.
- `:shippy-core` now exists as an independent Kotlin/JVM module with a forbidden
  dependency gate. Its first model slice defines canonical UUID identities,
  Recording/Artist/Release, source, media-asset, Library, playlist-entry, and
  queue-entry types without Android, Room, Media3, Musikr, provider DTOs, or UI.
- Core metadata now retains observations/provenance separately, resolves fields
  by stable trust precedence with user overrides first, parses material version
  traits, and returns evidence-backed conservative identity decisions with
  exact-key/strong-ID paths and version/duration vetoes.
- Core source selection now rejects unverified identity/assets and unavailable
  or policy-blocked candidates before deterministic ranking. Explicit source
  preference is honored first, followed by Crew-required media, verified
  downloads, linked local assets, complete cache, providers, and Crew peers.
  Exact source-key linking is idempotent and reports conflicts instead of
  silently reassigning a source to another recording.
- The pure queue reducer now separates base order from traversal order, owns a
  stable seed-based shuffle, preserves duplicate recordings by QueueEntryId,
  keeps the current occurrence across shuffle toggles, supports explicit
  add-next/add-end/remove behavior, and rejects stale identity anchors rather
  than applying index-based moves.
- Pure redirect rules now resolve chains, reject cycles/conflicting reassignment,
  choose merge survivors deterministically, and retain exact moved-reference
  data for unmerge. Redirect compaction is deliberately deferred to the audited
  transactional data layer.
- Listening sessions now accumulate only monotonic audible intervals, account
  for playback speed, exclude stopped/buffering time by construction, discard
  process-local anchors on restore, and apply the half-duration/four-minute
  Last.fm threshold only to recordings longer than 30 seconds.
- Crew recording descriptors now carry only portable metadata, stable provider
  hints, external identifiers, and public HTTPS artwork. Their types reject
  local/download/cache/Crew source namespaces and obvious device-private
  locators.
- The immutable playback reducer now separates intended queue selection from
  engine-committed current identity. Queue replacement advances generation,
  every mutation advances revision, source and engine work is tagged, stale
  results are ignored, and only a matching QueueEntryId engine commit publishes
  current playback state.
- `:shippy-data` now exists as an isolated Android/Room module depending only on
  core. Schema v1 exports all 29 normalized identity, source/asset, Library,
  playlist, metadata authority, redirect/audit, history/checkpoint, download,
  integration, saved-source, and migration tables. Exact-source uniqueness,
  duplicate playlist occurrences, ownership, checksum, and referential
  constraints are exercised in-memory; `:app` does not depend on or read this
  database yet.
- The first five internal DAOs are scoped by canonical identity: transactional
  recording/artist graphs, idempotent exact-source observation ingestion,
  verified assets, per-recording Library relationships, and duplicate-safe
  playlist creation/reorder. Source refresh cannot silently reassign recording
  identity, and playlist reorder must contain every current occurrence.
- `library_song_view` and `playlist_entry_view` now project canonical metadata,
  ordered artist credit, ownership/offline state, latest download state, and
  availability without rebuilding the full Library in memory. A Room FTS4 table
  indexes title, artist, release, identifiers, linked source titles, and user
  overrides through an explicit per-recording refresh boundary.
- Canonical recording-graph writes and exact-source observation ingestion now
  refresh the affected FTS row inside the same Room transaction. Repeated exact
  sources retain their original source identity while refreshed provider titles
  become searchable without an inconsistent intermediate state.
- Identity decisions and negative matches now have scoped persistence, while a
  bounded redirect resolver records only direct canonical redirects, rejects
  conflicting/non-canonical targets, and reverses each redirect through its exact
  merge audit. Full relationship-moving merge/unmerge remains a later transaction.
- Local play history is keyed independently by listening session and canonical
  recording. Source-neutral playback checkpoints replace their header and ordered
  occurrence rows atomically, preserve duplicate recordings by queue-entry ID,
  and reject incomplete or inconsistent occurrence sets before writing.
- Download writes are scoped by recording/job and publish a verified permanent
  asset with the completed job in one transaction, retaining exact requested
  source identity and verified length. Last.fm uses a listening-session-unique
  FIFO outbox; lyrics use recording/fingerprint/provider identity; saved provider
  entities retain their exact source key.
- Migration audits now have an insert-once start, resumable progress lookup, and
  guarded single completion with target counts and checksum. Bootstrap cutover
  state remains intentionally outside this mutable database, and no importer or
  production switch is claimed yet.
- Library songs, Liked, Local, Downloads, playlist detail, scoped Library and
  playlist FTS, and local play history now expose Room-backed PagingSources with
  a 50-row consumer default rather than full-table hydration. UI/domain repository
  adapters are still intentionally absent.
- Playlist occurrence moves use sparse 64-bit order keys and normally update one
  row. Only an exhausted/overflowed anchor gap triggers a deterministic complete
  rebalance, while duplicate recordings remain distinct by PlaylistEntryId.
- The migration foundation now owns a fixed UUIDv5 namespace and typed,
  domain-separated mappings for legacy recordings, sources, assets, observations,
  playlists, playlist occurrences, and queue occurrences. Retry therefore maps
  the same old identity to the same R16 identity without embedding legacy data in
  the new ID.
- A dedicated v10 reader opens only a read-only SQLite handle, inventories the 13
  expected legacy tables with row counts/missing-table truth, and pages canonical
  tracks by stable key in batches of at most 500. The explicit M0-M14 plan retains
  safe cancellation before cutover.
- M1 canonical-track import now commits one ordered page atomically: deterministic
  Recording/Artist/Release identities, raw legacy observation, field provenance,
  canonical rows, FTS refresh, and the audit checkpoint advance either all commit
  or all roll back. A retry is idempotent, performs no fuzzy cross-track merge, and
  leaves imported identities transient until later relationship/asset phases make
  durability explicit. Backup, later phases, verification, and cutover remain open.

## Current blockers

- The final public application ID string requires owner selection before the
  dependent namespace/package migration is locked.
- A redacted real owner-device v10 database fixture is required before importer
  cutover can be proven.
- Physical-device verification remains unavailable until a device is attached;
  it does not block pure core or deterministic migration work.

## Next exact slice

1. Implement the checksum-verified `ShippyBackupV1` archive contract, then add
   bounded M2 candidate reads/import; production remains on legacy v10.

## Verification level

Baseline preservation is checksum-verified. Changed app Kotlin and Android
resources compile. `R16LayoutRegressionTest` passes (2 tests), and the focused
playback identity/transition suites pass (11 tests). The Last.fm suite passes
(23 tests). Scoped collection composition passes its focused repository and
presentation suites (10 tests). The bounded pager projection suite passes (4
tests). Managed-download reconciliation/filtering passes its focused suite (7
tests). All eight named urgent R15.3 regression repairs are implemented and
focused-unit-tested. Phase 2's `:shippy-core:check` passes independently (22
tests plus its forbidden-import gate). No R16 code has been instrumented,
device-tested, or performance-tested. `:shippy-data:testDebugUnitTest` passes 6
schema/DAO tests, including duplicate playlist occurrences, orphan rejection,
exact-source uniqueness/idempotence, asset/Library scoping, and complete-order
playlist writes, plus canonical Library/playlist read projection and FTS lookup.
The exported schema contains version 1, 30 entities including FTS, 2 views, and
identity hash `a492bff168b76a611f7a9dfb6d8b665b`. The durable identity/history/checkpoint
DAO suite adds 3 passing focused tests (9 total data-module tests).
The download/integration/migration ledger suite adds 3 passing focused tests
(12 total data-module tests).
Paging and sparse-order coverage adds 3 passing focused tests (15 total
data-module tests).
The read-only legacy reader, deterministic mapping, and import-plan suite adds 3
passing focused tests. M1 atomic import/rollback/idempotence adds 2 more (20 total
data-module tests).
