# Shippy R16 Status

**Updated:** 2026-08-19  
**Active phase:** Phase 4 — Source Ingestion and Managed Assets  
**Authority:** `../Shippy_R16_Master_Architecture_and_Implementation_Spec.md`

## Execution mandate

The product owner permanently authorized execution of the complete R16 program.
Do not ask for another general "go" after context compaction. Stop only for an
owner decision that the master specification explicitly marks as blocking.

Build and Gradle caches must be retained between builds. Clear them only after a
specific cache-corruption diagnosis.

All R16 work remains local for owner testing. Local commits may preserve
coherent checkpoints, but no GitHub push, pull request, release, or other
publication is authorized until the owner explicitly requests it.

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
  constraints are exercised in-memory. `:app` now sees the database only through
  the explicitly opened inactive R16 runtime and M12 bridge; production Library,
  playback, and database authority still do not read it.
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
- `ShippyBackupV1` now defines a bounded ZIP/NDJSON archive with an explicit
  schema/version manifest, required allow-listed user-data sections, optional
  history and asset manifests, and per-section byte counts plus SHA-256 checksums.
  Reads reject missing, duplicate, unknown, oversized, or tampered entries. Cache,
  Crew temporary media, raw credentials, headers, resolved URLs, and logs have no
  archive section; sanitized database export/restore orchestration is still open.
- M2 now pages `canonical_track_candidate` by its composite stable key and
  atomically advances the matching audit checkpoint with source/asset counts.
  Provider candidates retain exact provider/item identity but never persist a
  legacy resolved stream URL. Local and download candidates become playable
  assets only through verifier-supplied durable location evidence gathered
  before the Room transaction; stale availability without a verified asset is
  downgraded. Temporary Crew candidates remain raw audit observations only and
  never become permanent sources. Retry is idempotent, and an exact source or
  asset-location conflict is reported rather than reassigned.
- M3 now pages legacy Library relationships by TrackId, preserves Liked as an
  explicit R16 relationship, supplies migration-time provenance for the absent
  first-added timestamp, and promotes every user-owned relationship Recording
  from transient to durable. Legacy downloaded flags remain evidence for M6;
  they do not manufacture an available asset before file verification.
- M4 pages playlists and memberships by stable composite keys. Correlated
  source ordinals normalize duplicate/broken legacy positions into deterministic
  1024-spaced order keys across page boundaries. Playlist identity, name, pin,
  artwork, and Library layout are preserved; entries map to deterministic
  occurrence IDs and make their Recordings durable. Retry is idempotent. The
  v10 `(trackId, playlistId)` key could not represent duplicate occurrences, so
  migration preserves its exact expressible data without inventing duplicates.
- M5 remains an explicit runtime dependency on the later Musikr/local-ingestion
  mapping; no placeholder device-playlist importer pretends those UIDs already
  resolve. The import plan still prevents cutover from skipping this phase.
- M6 now pages legacy download jobs by stable JobId and joins only the requested
  candidate identity. Artifact/SAF verification completes before the Room page
  transaction. Verified jobs publish through the same managed-download
  transaction and reuse an existing exact managed asset location; stale
  `AVAILABLE` rows become retryable, interrupted work restarts safely, and
  identity/location/length conflicts remain warnings rather than reassignment.
  Raw artifact, pending, provider-stream, and failure-message locators are not
  copied into R16 durable state.
- M7 pages lyrics by `(TrackId, fingerprint)`, validates the legacy fingerprint
  against its full normalized metadata tuple, rejects empty/corrupt payloads,
  maps exact Recording/provider identity, and marks imported cache rows stale so
  they remain an offline fallback while normal retrieval may refresh them.
- M8 preserves Last.fm FIFO order by `(queuedAt, outboxId)` with deterministic
  outbox/listening IDs. Exact legacy queue-item links reuse the M1 Recording;
  otherwise the importer creates one isolated deterministic durable Recording
  from the scrobble metadata rather than fuzzy-merging or dropping the pending
  listen. Retry cannot duplicate delivery rows, and unavailable legacy
  `chosenByUser` evidence defaults honestly to false.
- M9 reads the bounded legacy active checkpoint without copying resolved media,
  maps every surviving queue occurrence and Recording deterministically, and
  retains duplicate Recordings through distinct QueueEntry IDs. Valid shuffle
  traversal is compacted by identity after invalid rows are skipped; an invalid
  current row moves to the nearest prior surviving traversal entry with position
  reset. The imported checkpoint is source-neutral, versioned, checksummed, and
  makes its retained Recordings durable. Playing intent and shuffle seed default
  honestly because v10 did not persist them.
- M10 pages saved provider entities by their exact composite source key and
  preserves title, subtitle, pin, and save time in `saved_source_entity` without
  pretending they are canonical Releases, Artists, or user playlists. Only
  public-shaped HTTPS artwork/original links cross the migration boundary;
  private or device locators are discarded with bounded audit warnings. Retry
  is idempotent.
- M11 now reads only bounded legacy Crew checkpoint metadata plus checksum
  validity. The pre-R16 payload is never copied into the R16 database: its Track
  identity cannot safely become R16 portable Recording/QueueEntry identity, so
  the audit records local expiry/rejoin and required legacy-lease expiry. The
  old database remains untouched for rollback.
- The M13 verifier foundation compares all required user-owned/verified counts,
  checks logical references and SQLite foreign keys, detects redirect cycles,
  duplicate exact source keys and asset locations, and revalidates playback
  order/current/checksum identity. It cannot report ready while any M0-M12 phase
  is missing; therefore M5/M12 still block cutover instead of being papered over.
- Migration authority now has a small checksum-protected `AtomicFile` state
  outside both databases. Revision compare-and-set prevents stale writers;
  completed phases are an irreversible ordered prefix; backup/hash requirements
  gate import, verification, and cutover; corrupt state is reported rather than
  silently reset. The store is not wired into production selection yet.
- `:shippy-sources` now establishes the Phase 4 boundary without changing the
  active app: Android/Musikr-free source observations and local-engine contracts
  depend only on `:shippy-core`, provider observations cannot carry durable
  device locators, and the module has a forbidden-import gate.
- The first managed-asset registry classifies exact URI/document/MediaStore/job
  ownership before verified move-recovery evidence. Document and MediaStore IDs
  are exact only within the same provider/storage root; cross-root collisions are
  review candidates. Unrelated files remain ordinary Local candidates, while
  ambiguous checksum/fingerprint matches are surfaced for review rather than
  silently merged.
- The pure `RecordingIngestor` now executes inside one caller-supplied
  transaction and selects exact source identity first while still reclassifying
  its asset before persistence, followed by exact managed ownership and verified
  identity evidence. Multiple strong candidates and metadata-only similarity
  cannot silently merge recordings; incomplete observations remain explicitly
  unresolved rather than manufacturing canonical metadata.
- The app now contains an inactive `MusikrLocalMediaEngine` adapter over the
  existing process-owned `MusicRepository`. It emits exact Musikr UID/URI/path
  observations, scan state, and duplicate-safe change keys without constructing
  a second scanner. Android delete consent and tag mutation remain explicitly
  unsupported at this R16 boundary until their existing app paths are unified.
- The Room-backed ingestion store now keeps exact source lookup, bounded managed
  asset lookup, conservative identity candidates, new Recording creation, raw
  observation/source/asset writes, provenance, decisions, and FTS refresh inside
  one transaction. Repeated observations are idempotent and a source or asset
  ownership conflict rolls back instead of partially publishing identity.
- Managed download rescans retain `SHIPPY_DOWNLOAD` ownership, job identity,
  checksum/fingerprint, and original source ownership while accepting verified
  moved-location evidence. Probable/ambiguous managed assets are held out of the
  asset table for review instead of becoming duplicate local assets.
- The inactive M12 bridge now feeds the existing Musikr snapshot through the
  production `RecordingIngestor` and Room store in 50-item checkpoint pages.
  Its audit is resumable after interruption and fingerprints source/metadata/
  asset evidence so a changed snapshot restarts idempotently rather than skipping
  a newly inserted key. This seam does not mark bootstrap M12 complete or switch
  any production authority.
- `:shippy-sources` now exposes a small metadata-only `SourceDiscoveryRepository`
  contract with typed provider sections, entities, continuations, failures, and
  discarded-result truth. Provider artwork and original links must be public
  HTTPS, while source observations still cannot carry resolved playback locators.
- One inactive app adapter reuses the existing concurrent
  `UnifiedSearchRepository` rather than starting a second provider pipeline. It
  maps JioSaavn, YouTube, and YouTube Music results to source observations,
  rethrows cancellation, preserves per-provider failures, discards invalid source
  tokens, and never persists a result merely because Search displayed it.
  YouTube and YouTube Music retain distinct `SourceKind` provenance but share the
  exact `youtube`/video source family for the same video ID.
- No second legacy-download ingestion bridge was added: verified M6 publication
  already owns the Recording/source/asset relationship, and the shared managed
  registry plus local scan ingestion reuses that exact asset while retaining
  `SHIPPY_DOWNLOAD` ownership. Re-ingesting a legacy `TrackId` as if it were an
  R16 `RecordingId` would create a competing identity path.
- The scoped persistent `SourceRepository` boundary now composes the existing
  `RecordingIngestor` with Room-backed exact lookup, per-Recording observation,
  and transactional availability updates. Discovery remains a separate read-only
  repository, so showing search results still cannot mutate catalogue identity.
- `source_reference` now persists canonical source kind and failure retryability,
  allowing the full availability contract to round-trip. YouTube Music discovery
  provenance remains in the raw observation while its exact shared video source
  stores canonical `YOUTUBE` kind. Availability failure never deletes or changes
  Recording identity.
- Transient catalogue retention now has one 60-day default shared by fresh
  ingestion and legacy import. The Room maintenance owner scans at most 800
  eligible rows and deletes at most 200 per invocation, rechecking every row in
  its deletion transaction and reporting scanned/protected/raced/deleted counts.
- GC refuses any Recording with a Library/playlist relationship, any asset,
  user metadata or manual identity decision, checkpoint, retained history,
  download, Last.fm outbox row, or merge/redirect audit. Runtime queue, retained
  cache, and pending-work snapshots are explicit protections. It removes only
  database catalogue rows and derived observations; it has no filesystem API and
  therefore cannot delete user files.
- `:shippy-sources` now owns the inactive event-driven enrichment boundary.
  Playback may schedule only after commit, durable intent upgrades the same
  `enrich-recording:<RecordingId>` identity, and idle maintenance is deterministic,
  capped at 50, and unmetered. The scheduler contract is cancellable and
  observable. Search rendering and interactive manual Identify are deliberately
  absent from this background path; an Android WorkManager executor remains a
  later activation step rather than a no-op worker.

## Current blockers

- The final public application ID string requires owner selection before the
  dependent namespace/package migration is locked.
- A redacted real owner-device v10 database fixture is required before importer
  cutover can be proven.
- Physical-device verification remains unavailable until a device is attached;
  it does not block pure core or deterministic migration work.

## Next exact slice

1. Start Phase 5 behind the inactive R16 boundary: implement the process-scoped
   playback coordinator and fake engine around immutable snapshots, QueueEntryId
   occurrence identity, deterministic shuffle, generation-safe preparation, and
   restore semantics. Do not project to Media3 or switch legacy playback authority
   until the pure state machine is proven.

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
identity hash `44e9cd72ef59268c22b636e7b74d7fdc`. The durable identity/history/checkpoint
DAO suite adds 3 passing focused tests (9 total data-module tests).
The download/integration/migration ledger suite adds 3 passing focused tests
(12 total data-module tests).
Paging and sparse-order coverage adds 3 passing focused tests (15 total
data-module tests).
The read-only legacy reader, deterministic mapping, and import-plan suite adds 3
passing focused tests. M1 atomic import/rollback/idempotence adds 2 more (20 total
data-module tests).
The backup round-trip and tamper rejection add 2 passing tests (22 total
data-module tests).
M2 composite paging, exact source import, verified asset publication, URL
discard, Crew-temporary exclusion, and retry behavior add 1 passing focused test
(23 total data-module tests).
M3/M4 Library ownership, playlist/layout preservation, deterministic sparse
ordering, stable paging, and retry behavior add 1 passing focused test (24 total
data-module tests).
M6 download paging, source mapping, exact artifact verification, managed-asset
reuse, stale-availability repair, locator discard, and retry behavior add 1
passing focused test (25 total data-module tests).
M7/M8 lyrics fingerprint validation, stale-cache preservation, exact queue-item
outbox linking, isolated fallback identity, FIFO order, and retry behavior add 1
passing focused test (26 total data-module tests).
M9/M10 source-neutral checkpoint conversion, invalid-entry compaction, duplicate
Recording occurrences, current/traversal preservation, checksum generation,
exact saved-source paging, private-locator discard, and retry behavior add 1
passing focused test (27 total data-module tests).
M11 bounded Crew-checkpoint inspection/expiry and M13 phase/count/reference/
redirect/uniqueness/checkpoint verification add 2 passing focused tests (29
total data-module tests).
Checksum-protected atomic bootstrap state, legal transition checks, stale-writer
rejection, corruption refusal, and premature-verification refusal add 2 passing
focused tests (31 total data-module tests).
Room-backed ingestion, managed-download preservation, conflict rollback, and the
M12 audit checkpoint add 3 passing tests (34 total data-module tests).
Room-backed source observation/exact lookup plus retryable availability updates
that preserve Recording identity add 1 passing test (35 total data-module tests).
Bounded transient collection, complete durable-reference protection, derived-row
cleanup, runtime protection, and the shared 60-day default add 2 passing tests
(37 total data-module tests).
`:shippy-sources:check` passes its forbidden-import gate and 10 focused
source, managed-asset, ingestion, and enrichment tests. The app compiles with the inactive Musikr/data
bridge; its exact observation-mapping test and interrupted/resumed/changed-snapshot
M12 test pass. The provider observation adapter adds 3 passing tests for stable
repeated source keys, shared YouTube/YouTube Music video identity, locator/artwork
sanitization, scoped partial failure, and cancellation propagation. Root
`spotlessCheck` also passes. M12 and provider discovery have explicit callable
seams but are not invoked by production or recorded complete in bootstrap state;
none of this work is instrumented, physical-device tested, or
performance-profiled.
