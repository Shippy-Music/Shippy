# Shippy R16 Status

> **Historical ledger:** This document's authority/cutover snapshot predates
> R16 Beta 1. Read `../CLOUD_HANDOFF.md`, the current README, and
> `KNOWN_ISSUES.md` before continuing; verify startup behavior in the real code.

**Updated:** 2026-08-21
**Active phase:** Isolated ACTIVE composition (durable selector still fail-closed)
**Authority:** `../Shippy_R16_Master_Architecture_and_Implementation_Spec.md`
**Execution model:** `AUTONOMOUS_EXECUTION_OPERATING_MODEL.md`

## Execution mandate

The product owner permanently authorized execution of the complete R16 program.
Do not ask for another general "go" after context compaction. Stop only for an
owner decision that the master specification explicitly marks as blocking.

Build and Gradle caches must be retained between builds. Clear them only after a
specific cache-corruption diagnosis.

All R16 work remains local for owner testing. Do not commit, push, open a pull
request, release, or publish until the owner explicitly requests it.

Treat the master specification as a compass, not a literal transcription task:
its invariants, data-safety rules, authority boundaries, and required outcomes
are binding, while suggested class names, exact commit counts, and implementation
details may adapt to current evidence. Prefer practical coherent slices and
avoid checklist theatre.

The autonomous execution operating model now governs implementation shape:
preserve sound completed work, integrate the central end-to-end Shippy spine
before expanding horizontally, delegate coherent ownership through Sol → Terra
→ Luna, and justify complexity only by a concrete product or safety property.

## Current authority state

- An isolated ACTIVE composition now exists: one process-shared R16 runtime, one
  player/playback spine/coordinator, a canonical MediaBrowser adapter/router, an
  R16-only Songs host, system surfaces, listening-session delivery, and Last.fm
  scheduling. This is composition evidence, not durable activation: persisted
  `R16_ACTIVE` still selects `ACTIVE_UNAVAILABLE`, M14 remains unreachable, and
  R15.3 remains the sole production authority.
- R15.3 remains the sole production authority: its legacy database, playback
  manager, Library, and UI remain active.
- An R15 database alone stays on normal R15 database, playback, Library, and UI
  access. It does not activate or block on incomplete M0-M13 machinery.
- A persisted migration marker opens an isolated recovery host before normal
  legacy work. That host owns M0-M13 only and exposes no R16 playback,
  Library/Search reads, feature flags, or M14.
- The read-only authority selector is production-wired into `MainActivity` and
  `AuxioService`. Only `LEGACY` may construct legacy UI/playback; migration and
  recovery states stay isolated, while `R16_ACTIVE` fails closed to a static
  unavailable screen. `R16M14Cutover` still has no reachable production caller.
- `READY_TO_SWITCH` is a recovery checkpoint only; it is neither a cutover
  action nor an activation trigger. Legacy data and backup remain intact.
- `R16BackupRuntime` exists behind the explicitly opened inactive R16 data
  runtime. It does not change production authority.
- A lazy singleton active-data owner now guarantees that R16 Library and playback
  consumers receive the same `R16DataRuntime` only for explicit isolated
  `R16AuthorityMode.ACTIVE`; the durable `R16_ACTIVE` marker cannot select that
  mode. It opens nothing in legacy, migration, ready, corrupt, or
  `ACTIVE_UNAVAILABLE` modes. The isolated ACTIVE composition binds that owner
  to the R16 Songs host, browser/router, and playback spine.
- That shared runtime now also exposes a bounded MediaBrowser read repository:
  capped Library-song, playlist-summary, playlist-entry, exact-item, and sanitized
  local-FTS pages plus strict R16-only media IDs. Duplicate playlist occurrences
  retain `PlaylistEntryId`; its focused browser/paging gate is 8/8 green. The
  isolated service/browser adapter and search-to-queue router now use that shared
  runtime; durable ACTIVE startup remains fail-closed.
- The isolated Songs host now has contextual search with accessible search, clear,
  empty, and paged-result UI. It reuses the existing Library-scoped canonical FTS
  and exact `RecordingId` playback path; it is not the full R16 Library Search
  surface.
- Library Search V1 is now a dedicated local-only surface, distinct from Global,
  Songs contextual, and playlist contextual search. It Room-pages Songs,
  Playlists, and Artists and exposes query-filtered bounded Liked, Local, and
  Downloads streams. Canonical Recording playback and existing Playlist, Artist,
  and system-collection routes are retained. Releases are visibly disabled and
  deferred because no live Release graph exists.
- The isolated ACTIVE host now keeps Global Search separate from Library Search:
  bounded canonical local-FTS results lead, a 200 ms debounce gates generic
  configured-provider search, each provider failure is isolated with partial
  retry, and each section is capped at 20. Selected results persist their exact
  `SourceKey` canonically; JioSaavn retains provider identity, while YouTube and
  YouTube Music share exact video source identity. MediaSession play accepts only
  strict `RecordingId` media IDs, while the same session drives mini-player
  buffering/paused/playing state and its playback controls.
- The same isolated host now provides paged canonical Playlists browse/detail.
  Row playback resolves the exact `PlaylistEntryId`; Play and Shuffle atomically
  submit one `PlayContext` built from the full ordered lightweight playlist seed,
  not a 50-row rich-view page. Each context creates fresh `QueueEntryId`s while
  retaining `PlaylistEntryId`, and cached bounded system projection supports a
  10,000-entry logical queue through the same MediaSession and mini-player.
  Play/Shuffle now wait for the MediaController connection before dispatch.
- Playlist detail now has contextual-search V1 scoped to the current playlist's
  Room FTS. A conservative sanitized query feeds 50-row pages without placeholders
  through `flatMapLatest`; duplicate rows retain exact `PlaylistEntryId` playback.
  Search supports clear, Back, and IME actions. Filter-aware Play/Shuffle use the
  same filtered visible order as the page and the full lightweight playback
  context, preserving exact `PlaylistEntryId` identity for duplicate occurrences.
  Invalid filters resolve to an empty result, malformed playback extras fail
  closed, and playback with no extras uses the persisted playlist sort.
- Persisted playlist sort V1 supports seven typed modes with `ASC`/`DESC`:
  `CUSTOM`, `RECENT`, `OLDEST`, `TITLE`, `ARTIST`, `ALBUM`, and `DURATION`.
  `CUSTOM` is normalized to `ASC` and remains the only reorderable mode; dynamic
  non-CUSTOM temporary sort is scoped to this V1. The original Auxio `DialogSort`
  bottom sheet is reused. The 10,000-entry device benchmark remains pending.
- Tapping that mini-player now opens a full scrollable, artwork-led current-item
  Now Playing surface. Canonical `QueueEntryId`/`RecordingId` stay internal while
  the UI shows title, artist, release, artwork, duration, progress, and playback
  error. A view-bound tick advances progress only while playing, seeks are guarded,
  and previous, play/pause, next, repeat, and shuffle reuse the existing
  MediaController -> MediaSession -> `R16SystemPlaybackCommands` path. The mini is
  hidden while expanded; Back and empty state remain usable.
- Now Playing also supports a one-way Save to Liked action for the exact current
  `RecordingId`. The shared R16 runtime owns the write to `library_recording` as
  `liked=true` and `explicitlySaved=false`; after success the control is filled
  and disabled. The UI resets disabled when `RecordingId` changes and ignores
  stale or mismatched Like emissions, preventing A-state/B-action identity races.
  Unlike and the full Save Destinations sheet are not implemented.
- Now Playing add-to-playlist V1 captures the displayed `RecordingId`,
  `QueueEntryId`, title, and artist before opening a dedicated destination
  picker, so a later playback change cannot retarget the write. Canonical
  playlists are Room-paged at 50; choosing one appends exactly one entry in one
  transaction with a fresh `PlaylistEntryId`, preserving legal duplicates. The
  common append reads only the last entry; sparse-key exhaustion rebalances only
  the affected playlist. Playlist membership enters the derived Library
  membership index without fabricating a `library_recording` row. Insert/update/
  delete triggers, migration/backfill, and rebuild predicates now share that
  rule; existing v3 databases perform the trigger upgrade and targeted backfill
  once when needed. The picker exposes Loading, Adding, retry, and failure truth,
  and retains tokenized completion across STOPPED/configuration changes. This is
  not the full Save Destinations sheet: membership selection/removal,
  create-playlist, and multi-target actions remain deferred. No physical-device,
  durable activation, or M14/cutover claim changes.
- Library now has an Artists in your Library vertical. Its canonical artist list
  and detail recordings are limited to current Library membership, deduplicate
  repeated recording credits with `DISTINCT`, and use deterministic 50-row pages.
  Browser rows use strict composite `ArtistId` + `RecordingId` identity; detail
  Play and row playback create the complete lightweight artist context with
  `ARTIST` origin through the existing MediaSession route. The detail Play control
  remains disabled until a controller is connected and the artist has recordings.
  This is not saved-artist or saved-release functionality.
- Artist detail now also has Shuffle. Play and Shuffle both use the exact Artist
  browser ID through the one MediaSession path and remain gated on a connected
  controller plus nonempty artist results.
- Now Playing presents a friendly failed-state retry for the exact displayed
  `QueueEntryId` + `RecordingId`. `PlaybackCommand.RetryCurrent` revalidates that
  occurrence and its failed phase inside the serialized coordinator, then reuses
  the existing reprepare and bounded source-fallback path. It does not add a
  source picker or change the manual Next/skip behavior.
- Queue V1 now exposes a current-anchored page of at most 50 lightweight entries.
  Prev/Next resolve the exact `QueueEntryId` against revision and cache, and only
  the requested page is presented. The live MediaSession occurrence supplies the
  exact current-row highlight while the bounded MediaSession queue is preserved.
  Reorder, remove, and multi-select are deliberately deferred.
- The isolated host now has a bounded Home surface: Recent is capped at eight,
  History is Room-paged at 50, and Continue Listening reads the one current
  MediaSession occurrence. Its conditional resume rechecks the exact
  `QueueEntryId`/`RecordingId` atomically inside the serialized coordinator, so
  a stale tap cannot resume a different occurrence.
- Home now shows up to eight pinned playlist shortcuts from `library_layout_entry`.
  That table is the sole pin/order authority: only valid `PLAYLIST` rows survive
  the inner join, stale references disappear, and each shortcut opens the existing
  detail by exact `PlaylistId`. Empty shortcuts stay hidden. This adds no system
  pins, pin/reorder mutation UI, saved-artist/release claim, or Album/Release
  vertical.
- Playlist summary pin/order now also reads only `library_layout_entry` through a
  `PLAYLIST`-scoped left join; a missing layout row is unpinned and ordered
  deterministically last. An exact `PlaylistId` pin toggle is one Room transaction
  that preserves an existing layout key or appends one. Per-row in-flight state
  disables the control; successful writes refresh Paging and the same Home Flow.
  Reorder, system pins, and saved-artist/release behavior remain absent.
- Playlist lifecycle V1 now creates a canonical UUID `PlaylistId` with `USER`
  provenance, default `CUSTOM`/`ASC`, and one unpinned layout row atomically.
  Rename/delete target the exact canonical `PlaylistId`, including imported/device
  playlists, without rewriting provenance. Delete removes the layout row and
  playlist (cascading entries) while retaining unrelated Library/download state;
  playlist FTS triggers stay aligned. Create, Rename, and Delete dialogs use
  tokenized completion state retained through STOPPED/configuration changes and
  acknowledge each completion once. Multiselect remains deferred; persisted
  playlist sort and adjacent entry reorder are covered below.
- Playlist-entry removal V1 deletes only the exact paired `PlaylistId` and
  `PlaylistEntryId`, so duplicate Recording occurrences remain distinct. Missing,
  mismatched, or repeated requests return false without mutation; imported/device
  canonical playlists are equally eligible. The detail row popup requires
  confirmation, tracks each entry in flight, retains its operation-token
  completion through STOPPED/configuration changes, and refreshes both Paging and
  the header summary on success. Batch removal remains deferred; persisted
  playlist sort and adjacent reorder are covered below.
- Playlist sort V1 persists the typed mode and direction for each playlist and
  applies one deterministic order to both the paged visible rows and the full
  lightweight Play/Shuffle context. It is filter-aware, uses exact
  `PlaylistEntryId` identity for duplicates, fails closed for malformed extras,
  and treats invalid filters as empty results. `CUSTOM` is normalized to `ASC`
  and is the only reorderable mode; other modes are temporary/non-reorderable in
  this V1. The original Auxio `DialogSort` bottom sheet is reused.
- Playlist-entry adjacent reorder V1 is now integrated for `CUSTOM` playlists
  with a blank filter only. Screen-owned Edit order mode exposes accessible
  Move up/Move down actions for the exact `PlaylistEntryId`; Back/Done exits the
  mode, and ordinary play/remove/search actions are guarded while editing. The
  repository moves exactly one canonical neighbor using the paired
  `PlaylistId`/`PlaylistEntryId`: the common indexed path swaps two unique sparse
  order keys, while tied/shared/corrupt keys materialize and rebalance only the
  affected playlist. Duplicate Recording occurrences remain independent. This
  is not drag or arbitrary cross-page reorder, queue reorder, batch action,
  schema, M14, or device-acceptance evidence.
- Playlist title FTS is schema v3: v2-to-v3 migration backfills it, insert/rename/
  delete triggers keep it derived, and `3.json` is generated. Backup restores by
  clearing, rebuilding, and verifying derived `playlist_fts`. Library Search
  retains its query, hides empty section headers, shows an honest all-stream empty
  state, and provides shared-error Retry all plus horizontal 48dp navigation.
- R16 system collections Liked, Local, and Downloads are Room-paged at 50.
  Their display paths do not hydrate the collection: only Play/Shuffle obtains
  the ordered ID-only context needed to construct the complete queue.
- The current R16 schema is v4: 32 entities including FTS, 2 views, and
  identity hash `380e115fb92568875e5e2794203fff40`. The additive v3-to-v4
  migration and generated Room schema `4.json` are present.
- R16 feature flags have not been introduced.
- The master specification supersedes conflicting pre-R16 product,
  architecture, implementation, UX, stabilization, and completion claims.

## Narrow R16 offline foundation checkpoint (2026-08-21)

This is a verified R16 offline persistence, resolution, SAF-publication, and
scanner-suppression foundation. It is not yet a complete transfer pipeline or
an activation claim.

- Schema v4 and generated Room schema `4.json` durably retain the exact requested
  source reference, opaque media variant, and destination identity. New jobs
  require all three; imported v3 jobs preserve honest nulls instead of inventing
  values. Backup/export retains the new durable fields while excluding pending
  document locators.
- The shared `R16OfflineRepository` can load the exact full job snapshot,
  atomically enter `PUBLISHING` from `VERIFYING` while persisting its pending URI,
  and reset a `FAILED_RETRYABLE` job to a clean `QUEUED` state after caller-owned
  cleanup. Dedicated transition gates enforce legal predecessor states, exact
  Recording/source/variant/destination identity, monotonic progress, and
  idempotent replay; legacy `FINALIZING` is normalized as `PUBLISHING`.
- Publication remains verified and fail-closed: only the exact `PUBLISHING` job
  and destination can publish its `SHIPPY_DOWNLOAD` asset. A shared asset is
  retained while another job still references it. Last-reference removal is
  physical-delete-first, then Room reconciliation runs in `NonCancellable`;
  Recording identity, likes, playlists, and unrelated assets remain intact.
  Missing-asset repair updates every job that references that shared asset.
- `R16DownloadSourceResolver` resolves only the persisted, linked provider source
  for the requested Recording. It checks provider health and DOWNLOAD capability,
  calls the provider once with download constraints, preserves the resolved
  stream, and performs no search, ranking, fallback, playback substitution, or
  media-variant guessing.
- `SafR16DownloadDestination` is settings-free. It requires the exact persisted
  SAF tree identity plus retained read/write grant, creates a fresh job-marked
  pending child without filename lookup, rejects cross-tree/cross-job reuse,
  copies only a verified private stage, verifies exact length/readability/opening,
  and shields partial-document cleanup from cancellation. Same-name user files
  are never adopted. `SafR16ManagedDownloadStorage` separately preserves exact
  present/absent/failure truth for published-asset removal.
- Managed-file recognition still uses the existing pre-ingestion filesystem
  boundary and single Musikr scanner. Exact pending URIs for R16 `PUBLISHING`
  jobs (including legacy `FINALIZING`) are suppressed before local ingestion;
  verified managed assets retain canonical Recording/job ownership, while
  unrelated same-name files pass through normally. The index is a snapshot per
  scan: a concurrently started scan can miss a pending URI created afterward,
  so the upcoming worker must call `beginPublishing` before SAF copy and before
  any reindex it triggers.
- Focused evidence is green: 32/32 tests with zero failures, errors, or skips
  (offline repository 17, source repository 2, source resolver 5, SAF destination
  3, managed storage 3, filtering filesystem 2). Spotless and diff checks are
  clean. App compilation is green under a temporary 4 GiB in-process override;
  repository `gradle.properties` was left unchanged and build caches were kept.
- No R16 worker, coordinator, or WorkManager scheduler exists yet. Complete
  transfer execution, Media3 asset-open verification, cache promotion/policy,
  download UI, physical-device/runtime/performance acceptance, durable
  `ACTIVE`, and M14/cutover remain unimplemented or unverified.
- UI direction is unchanged: the original Auxio/Shippy theme, typography, rows,
  navigation rhythm, sheets, drawers, and mini/full-player choreography remain
  the fidelity boundary. Production stays on R15.3; persisted `R16_ACTIVE`
  still fails closed to `ACTIVE_UNAVAILABLE`, and M14 has no reachable caller.

## Prior integrated checkpoint evidence (2026-08-20)

- The prior focused cross-slice gate is green: `spotlessCheck` and
  `:app:compileDebugKotlin` completed successfully, with 60/60 focused tests
  passing (54 app, 6 data). It covers Queue/Search, Home/Continue/History,
  system collections, playback/system seams, and the narrowed download path.
- Refreshed cached `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks); this
  is host-build evidence only, not device/runtime acceptance.
- The Songs contextual-search slice is green: spotless and app compilation pass,
  `R16LibrarySongsViewModelTest` is 3/3, and `PagingDaoTest` is 4/4 (zero
  failures). Its transient 4 GiB build settings were restored afterward.
- Playlist contextual-search V1 passed an independent no-P0/P1 review,
  `spotlessCheck`, app compilation, and `PagingDaoTest` 4/4. The transient 4 GiB
  setting was restored exactly; the refreshed 255-task debug assembly remains
  green.
- The one-way Now Playing Save-to-Liked slice is green: spotless and app
  compilation pass, with 2/2 focused data tests. The identity-race correction
  passes `spotlessCheck` and app compilation under a transient 4 GiB in-process
  gate; the exact prior `gradle.properties` was restored afterward.
- The Artists + failed-state Retry checkpoint is green: `spotlessCheck` and
  `:app:compileDebugKotlin` pass, and 41/41 focused tests pass: PlaybackCoordinator
  15, SystemBridge 8, NowPlayingMapper 3, BrowserResolver 6,
  BrowserServiceAdapter 4, and data BrowserRepo 5. Cached
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks). The temporary
  Gradle settings were restored to the tracked `gradle.properties` hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`.
- Home pinned-playlist shortcuts V1 is green: `spotlessCheck` and
  `:app:compileDebugKotlin` pass, and `R16HomeReadRepositoryTest` passes 3/3.
  Normal `gradle.properties` was restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`.
- Artist Shuffle + playlist layout pin authority is green: `spotlessCheck`,
  `:app:compileDebugKotlin`, data `R16MediaBrowserRepositoryTest` 6/6, and
  `R16LibraryMutationRepositoryTest` 3/3 all pass with zero failures. The combined
  127-task gate completed successfully in 7m18s under transient 4 GiB in-process
  settings; normal tracked `gradle.properties` was restored to
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`. Normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks). The debug APK is
  69,076,401 bytes with SHA-256
  `8240213360494362d576afb2928dbf11ee6e292595fdfe48bf3100266eabf82d`.
- Library Search V1 first passed `spotlessCheck`, `:app:compileDebugKotlin`,
  `PagingDaoTest` 5/5, migration 1/1, and BackupRuntime 2/2 (8/8; 127 tasks,
  5m54s). Review corrections then reran spotless, app compilation, `PagingDaoTest`
  5/5, and migration 2/2 (7/7; 127 tasks, 4m15s). Normal Gradle content was
  restored to tracked hash `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`.
  Normal-settings `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks,
  4m53s); debug APK: 69,080,433 bytes, SHA-256
  `cadd88c0fa36081027c4b9f2e51e5198318feb88daebc2094648d90a33175776`.
- Playlist lifecycle V1 passes `spotlessCheck`, `:app:compileDebugKotlin`,
  `R16LibraryMutationRepositoryTest` 8/8, and `R16MediaBrowserRepositoryTest`
  6/6 (14/14). The 127-task gate completed successfully in 4m15s after one narrow
  visibility fix. Normal `gradle.properties` was restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 2m17s). Debug
  APK: 69,085,697 bytes, SHA-256
  `f1ad19c11cedee71a456ce83aa958561fac558194bce11530b27a6a60c51906e`.
- Playlist-entry removal V1 passed independent P0/P1 review after its summary
  refresh correction, `spotlessCheck`, `:app:compileDebugKotlin`, and
  `R16LibraryMutationRepositoryTest` 9/9. The 127-task focused gate completed
  successfully in 3m24s after one nullable compile correction. Normal
  `gradle.properties` was restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 2m36s). Debug
  APK: 69,086,677 bytes, SHA-256
  `4bfb6113178d70e1abce958a61b9fa1624048389ff8fe98db6434adcb925f52e`.
- Now Playing add-to-playlist V1 passed independent P0/P1 review,
  `spotlessApply`, `spotlessCheck`, `:app:compileDebugKotlin`, and 19/19 focused
  data tests with zero failures, errors, or skips: mutation repository 13/13,
  membership index 2/2, migration 2/2, and backup runtime 2/2. The 129-task gate
  completed successfully in 4m53s. Normal `gradle.properties` was restored to
  tracked hash `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`;
  normal-settings cached `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable
  tasks, 3m56s). Debug APK: 69,091,796 bytes, SHA-256
  `3d1db5b48d106515f90eee36c1699f53b7ee503155da3d66367d8debd044a520`.
- Playlist-entry adjacent reorder V1 passed independent P0/P1 review with no
  release-blocking finding. The focused gate passed `spotlessCheck`,
  `:app:compileDebugKotlin`, CoreDao 7/7, MutationRepo 15/15, and MembershipIndex
  2/2 (24/24 total); the 129-task build was BUILD SUCCESSFUL in 9m47s. Normal
  `gradle.properties` was restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; cached normal-settings
  `:app:assembleDebug` was BUILD SUCCESSFUL in 5m02s with 255 actionable tasks.
  Debug APK: 69,097,364 bytes, SHA-256
  `aea64522bcaf845cb199dc1468b9bd9c573434e9964a330e2d40070084f47168`.
  This is host evidence only; it adds no physical-device, runtime-performance,
  durable-activation, M14, or cutover claim.
- Persisted playlist sort and filter-aware playback passed independent final
  review CLEAN. The focused gate passed `spotlessCheck` and
  `:app:compileDebugKotlin`, with core 2/2, data 33/33, and app 17/17 tests
  (52/52 total; zero failure, error, or skip). Normal `gradle.properties` was
  restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings
  `:app:assembleDebug` completed successfully in 3m03s with 255 actionable
  tasks. Debug APK: 69,103,120 bytes, SHA-256
  `9566c272e55072161c4d78160f325c73c2eb7e8e0c92aea75521f0cb2eb10f3b`.
  This is host evidence only; physical-device behavior, runtime performance,
  durable activation, M14, and cutover remain unverified. The 10,000-entry
  device benchmark is still pending.
- The prior Kotlin compiler OOM in the download worker/transfer request path was
  mitigated by narrow source splits. Compilation now succeeds using transient
  4 GiB in-process Gradle settings; those settings were restored afterward and
  the local disk/build cache was retained.
- The new Search/mini-player slice compiles with `:app:compileDebugKotlin` green;
  `GlobalSearchCoordinatorTest` passes 3/3 and `MiniPlayerUiStateMapperTest`
  passes 2/2. Integration review found no P0 issue after the provider-retry fix.
- The playlist vertical has `:app:compileDebugKotlin` and data compilation green;
  its four focused suites pass 16/16 tests.
- The Now Playing slice has `spotlessApply` and `:app:compileDebugKotlin` green;
  focused Now Playing, mini-player, and system-bridge coverage passes 10/10 tests.
- The focused owner-shaped migration suite is green: 14/14 tests, including
  bounded restart/resume through verified M13 `READY_TO_SWITCH` and idempotent
  re-entry.
- The app R16 namespace gate is green: 78/78 tests passed across 21 suites.
- `:app:assembleBenchmark` is green.
- `:macrobenchmark:assembleBenchmark` and
  `:baselineprofile:assembleBenchmarkBenchmark` are green.
- The deterministic schema-v2 50,000-recording fixture was built and is
  cached for benchmark-variant use; this is fixture/build evidence, not a
  runtime timing result.
- No physical-device or runtime performance measurements exist yet.
- No real redacted owner-device v10 database fixture exists yet.
- The new lifecycle path is host-tested only: attach orders the one spine before
  system surfaces, attach failure releases both, release orders surfaces before
  the spine, and listening delivery drains before teardown. Physical playback,
  MediaSession, notification/widget/Quick Settings behavior, process death, and
  provider-network retry, authenticated Last.fm delivery, and mini-player behavior
  remain unverified.

## Historical implementation record

The detailed bullets below are retained for traceability from earlier
checkpoints. They are not the current gate. Earlier cumulative totals (including
schema v1/30 entities and the 545-app/42-data test totals) are historical and
must not be read as current evidence.

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
- Phase 5 now has an inactive serialized `PlaybackCoordinator` implementing the
  ordinary `PlaybackCommandRouter` path over the existing pure queue/playback
  reducers. Commands address QueueEntryId occurrences, queue replacement bumps
  generation, navigation bumps revision, and cancelled or late preparation and
  engine callbacks cannot commit over newer intent.
- The new narrow `PlayerEngine` and `PlaybackSourcePreparer` contracts contain no
  Media3 types. A `PlayerTransaction` with expected QueueEntryId and generation
  exists before engine mutation, closing the R15 early-transition race. The
  engine projection now uses the selected occurrence plus a bounded deterministic
  neighbor window; QueueEntryId is the Media3 identity and Media3 shuffle remains
  disabled.
- `PositionAnchor` now carries positive playback speed and advancing state while
  retaining a monotonic timestamp. Engine failures are accepted only for the
  pending/committed generation and occurrence; source/engine exceptions become
  typed retryable playback failures without changing Recording identity.
- Source-neutral checkpoint/restore, occurrence-scoped listening sessions,
  bounded trace retention, long-queue proof, and deterministic randomized
  coordinator runs are implemented behind the inactive R16 boundary.
- The shared R16 data runtime now exposes a transactional listening-session
  repository. It idempotently records canonical play history and conditionally
  queues one deterministic Last.fm outbox entry only when canonical artist/title
  metadata is usable and not filename-derived. Its focused 3-test data gate is
  green. The playback coordinator now emits typed finalized sessions with exact
  occurrence/source identity, reason, audible time, and position; the spine drains
  persistence after checkpoint and player release. The isolated ACTIVE service
  schedules the durable Last.fm outbox through WorkManager; device/network/auth
  delivery remains unverified.
- Playback materialization now resolves verified managed assets and provider
  sources through the pure ranking policy, stores expiring locators only in a
  bounded process-local registry, projects cache/header keys without persisting
  private URLs, and permits one generation-safe retry/fallback per occurrence.
  Media3 observations are delivered through a non-lossy callback channel.
- The Room-backed R16 checkpoint repository now round-trips source-neutral queue
  occurrence intent through the existing playback tables. Exact checksums, a
  10,000-entry bound, and corruption refusal protect restore; transient provider
  locators and headers never enter the checkpoint. A corrupt checkpoint is not
  silently cleared merely because the service attaches to an empty queue.
- The inactive process-scoped `R16PlaybackServiceRuntime` exposes one canonical
  snapshot/command endpoint, restores paused by default, debounces durable writes,
  owns orderly authority release, applies the mature foreground-retention policy,
  and exports bounded locator-free traces. It accepts one already-composed R16
  authority and has no production caller, so it neither creates a second player
  nor changes R15.3 authority.
- The existing canonical Library view now feeds a source-neutral playback
  presentation repository. Large unique-recording sets are observed in bounded
  SQLite query batches, so a 10,000-entry queue cannot overflow one `IN` binding.
- The legacy audio-only player construction is now shared through one injectable
  factory without changing its active/standby behavior. Its inert R16 entry point
  creates exactly one focus-owning Media3 player; no production R16 caller exists
  yet, so the authority selector still cannot activate playback.
- An inactive shared system bridge derives traversal, selected/committed occurrence,
  metadata, queue, playback state, notification/widget content, and Quick Settings
  state from one `PlaybackSnapshot`. MediaSession callbacks and system actions submit
  only `PlaybackCommand`; these adapters import neither `Song` nor the legacy manager.
  The surface runtime exposes its already-created session token without reattachment,
  and the action mask no longer advertises play-from-ID/search before canonical routing
  exists.
  The retained R15.3 Android surfaces remain active in production; the isolated
  R16 service owner composes equivalent lifecycle wrappers without changing that
  authority.
- Inactive lifecycle wrappers now apply that projection through the retained
  MediaSession, playback notification, widget layouts/artwork transformations,
  Android broadcast actions, foreground callback, and Quick Settings rendering.
  Queue and metadata publication reject stale asynchronous work; widget artwork is
  reused across unrelated snapshots and recreated after image/shape setting changes.
  Attach/release is centralized, commands still enter one R16 router, and no wrapper
  owns a player, queue, or legacy playback state. R15.3 remains the sole active
  playback path.

## Current blockers

- The final public application ID string requires owner selection before the
  dependent namespace/package migration is locked.
- A redacted real owner-device v10 database fixture is required before importer
  cutover can be proven.
- Durable ACTIVE activation remains fail-closed: persisted `R16_ACTIVE` maps to
  `ACTIVE_UNAVAILABLE`, and M14 has no reachable caller. The isolated composition
  shares one runtime/player/authority but still lacks device and cutover proof.
- Physical-device verification remains unavailable until a device is attached;
  it does not block pure core or deterministic migration work.

## Next exact slice

1. Compose an inactive R16 download worker/coordinator/scheduler over the verified
   job, exact-source, transfer-stage, SAF-destination, publication, and scanner
   boundaries. Keep durable activation fail-closed and order `beginPublishing`
   before SAF copy/reindex.

## Verification level

Current host evidence adds the focused 32/32 offline gate, schema v4 migration,
and app compilation described above. Earlier evidence covers the migration entry
host, selector, inactive playback/library seams, benchmark builds, the synthetic
owner-shaped M0-M13 flow, and the prior 60/60 Queue/Search/Home/system-collections
integration gate. A redacted owner-device fixture, connected-device behavior,
instrumented coverage, and runtime performance measurements remain unverified.

Baseline preservation is checksum-verified. Changed app Kotlin and Android
resources compile. `R16LayoutRegressionTest` passes (2 tests), and the focused
playback identity/transition suites pass (11 tests). The Last.fm suite passes
(23 tests). Scoped collection composition passes its focused repository and
presentation suites (10 tests). The bounded pager projection suite passes (4
tests). Managed-download reconciliation/filtering passes its focused suite (7
tests). All eight named urgent R15.3 regression repairs are implemented and
focused-unit-tested. Phase 2's `:shippy-core:check` passes independently (26
tests plus its forbidden-import gate). No R16 code has been instrumented,
device-tested, or performance-tested. `:shippy-data:testDebugUnitTest` passes 6
schema/DAO tests, including duplicate playlist occurrences, orphan rejection,
exact-source uniqueness/idempotence, asset/Library scoping, and complete-order
playlist writes, plus canonical Library/playlist read projection and FTS lookup.
Historical report: the exported schema contained version 1, 30 entities including FTS, 2 views, and
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
Transactional playback source options add one passing test covering verified
assets, linked providers, and rejection of unavailable or unverified assets (38
total data-module tests).
Exact R16 checkpoint round-trip and checksum-tamper refusal add 2 passing tests
(40 total data-module tests). The inactive service runtime adds 2 app tests for
safe paused restore, pre-attach rejection, central command routing, foreground
policy, locator-free trace export, debounced persistence, corrupt-checkpoint
preservation, and exactly-once release.
Canonical playback presentation adds 2 focused data tests for identity projection,
missing rows, empty requests, and bounded 1,001-ID lookup. Shared system projection
adds 2 focused app tests for duplicate Recording occurrences, shuffled traversal,
exact QueueEntryId presentation, Quick Settings agreement, and central command
routing. The inactive retained MediaSession, notification, widget, receiver,
foreground, artwork, and Quick Settings lifecycle wrappers compile and pass app lint;
they are not production-composed, instrumented, or device-tested.
`:shippy-sources:check` passes its forbidden-import gate and 10 focused
source, managed-asset, ingestion, and enrichment tests. The app compiles with the
inactive Musikr/data bridge; its exact observation-mapping test and
interrupted/resumed/changed-snapshot
M12 test pass. The provider observation adapter adds 3 passing tests for stable
repeated source keys, shared YouTube/YouTube Music video identity, locator/artwork
sanitization, scoped partial failure, and cancellation propagation.
The inactive Phase 5 path now covers the coordinator, bounded engine window,
checkpoint/restore, listening, trace, QueueEntryId Media3 projection, expiring
locator materialization, request headers/cache keys, and one bounded source
fallback. Historical cached-gate report: the earlier full cached gate passed
`spotlessCheck`, 26 core tests, 10 source tests, 42 data tests, 545 app tests (1 skipped), `:app:lintDebug`, and
`:app:assembleDebug`. The R16 runtime still has no production authority; M12 and
provider discovery have explicit callable seams but are not invoked by production
or recorded complete in bootstrap state. None of this work is instrumented,
physical-device tested, or performance-profiled.

## 2026-08-26 final local integration checkpoint

The R16 implementation is now production-activatable through the guarded
READY_TO_SWITCH to M14 cutover and ACTIVE authority path. The final local cached
gate passed `:shippy-core:test`, `:shippy-data:testDebugUnitTest`, the complete
`:app:testDebugUnitTest` suite (737 tests, 1 skipped), `:app:lintDebug`, and
`:app:assembleDebug`. The resulting local debug APK is at
`app/build/outputs/apk/debug/app-debug.apk`.

No commit or publish was performed. No Android device was attached at this
checkpoint, so migration, playback, storage, provider, system-surface, and
performance acceptance on physical hardware remain owner-device checks before
publication.
