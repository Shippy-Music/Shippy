# R16 Migration Ledger

**State:** R15 remains the sole production authority. A persisted marker routes
`MainActivity` to the M0-M13 recovery host before normal legacy work. An isolated
ACTIVE composition now exists (one shared runtime, one player/spine/coordinator,
MediaBrowser adapter/router, R16-only Songs host, system surfaces, listening
delivery, and Last.fm scheduling), but durable `R16_ACTIVE` still maps to
`ACTIVE_UNAVAILABLE`; the internal startup reader/M14 seam remains unreachable.

### Narrow R16 offline foundation checkpoint (2026-08-21)

The current verified slice is the durable offline ledger plus exact-source,
SAF-publication, removal, and scanner-suppression boundaries. It does not claim
a complete transfer pipeline, migration completion, or durable activation.

- Schema v4 adds nullable `requested_media_variant` and `destination_identity`
  columns through additive migration `3→4`; generated Room schema `4.json` is
  present. New requests require exact requested source, variant, and destination;
  legacy/imported rows retain null where the old schema had no evidence. Backup
  preserves these durable fields and excludes pending document locators.
- `R16OfflineRepository` loads a full exact job snapshot, enters `PUBLISHING`
  only from `VERIFYING` while atomically recording the pending URI, and resets
  `FAILED_RETRYABLE` to clean `QUEUED` state only after caller cleanup. Dedicated
  transition gates reject illegal predecessors, identity drift, progress
  regression, or mismatched destination. Legacy `FINALIZING` is interpreted as
  `PUBLISHING` for migration compatibility.
- Publication is exact and verified. Shared assets survive until their last job
  reference is removed; last-reference removal deletes physical bytes first and
  reconciles Room in `NonCancellable`. Missing-asset repair updates every job
  referencing the shared asset. Recording, likes, playlists, and unrelated
  assets remain untouched.
- `R16DownloadSourceResolver` accepts only the persisted source already linked
  to the requested Recording, checks provider health and DOWNLOAD capability,
  and makes one download-constrained resolve call. It does not search, rank,
  retry, fall back, substitute playback sources, or guess the requested variant.
- `SafR16DownloadDestination` uses the persisted SAF tree directly, not global
  settings. It requires retained read/write permission, creates a fresh
  job-marked child without filename lookup, rejects cross-tree/cross-job reuse,
  copies only a verified private stage, verifies length/readability/opening, and
  performs cancellation-shielded partial cleanup. Same-name user files are not
  adopted. `SafR16ManagedDownloadStorage` retains exact removal/probe truth.
- The existing pre-ingestion filesystem boundary suppresses pending locations
  for R16 `PUBLISHING` and legacy `FINALIZING` jobs before the one Musikr scanner
  can create a local candidate. Exact registered assets retain Recording/job
  ownership; unrelated same-name files pass through. Because each scan uses an
  immutable index snapshot, a pending URI created after a concurrent scan starts
  can be missed; the future worker must call `beginPublishing` before SAF copy
  and before any reindex it triggers.
- Focused host evidence is 32/32 with zero failures, errors, or skips: offline
  repository 17, source repository 2, source resolver 5, SAF destination 3,
  managed storage 3, and filtering filesystem 2. Spotless and diff checks are
  clean. App compilation passed under a temporary 4 GiB in-process override;
  repository `gradle.properties` is unchanged and caches were retained.
- No R16 worker/coordinator/WorkManager scheduler, complete transfer, Media3
  verification, download UI, device/runtime/performance acceptance, durable
  `ACTIVE`, or M14/cutover is implemented or verified yet. The next offline slice
  is the inactive worker/coordinator/scheduler, ordered so `beginPublishing`
  precedes SAF copy/reindex.
- UI fidelity remains the original Auxio/Shippy theme, typography, row
  treatment, navigation rhythm, sheets, drawers, and mini/full-player
  choreography. R15.3 remains the production authority; persisted `R16_ACTIVE`
  still fails closed as `ACTIVE_UNAVAILABLE`, and M14 has no reachable caller.

- Source: legacy `ShippyDatabase` v10, read-only during import.
- Target: new normalized `shippy-r16.db` schema v4 (32 entities including FTS,
  2 views, identity hash `380e115fb92568875e5e2794203fff40`), with generated
  Room schema `4.json`.
- IDs: deterministic UUIDv5 mappings for legacy durable identities.
- Entry: a persisted migration marker routes `MainActivity` to the recovery host
  before the normal NavHost, legacy UI, or playback service can start. An R15
  database alone continues through normal R15 access. The internal startup reader
  and `R16M14Cutover` seam exist. The read-only selector is production-wired:
  only `LEGACY` may construct legacy UI/playback, migration states remain isolated,
  and `R16_ACTIVE` still fails closed as `ACTIVE_UNAVAILABLE`. The isolated ACTIVE
  composition uses the shared runtime for its Songs/browser/playback/system path;
  M14 still has no reachable caller.
- Runtime: one application-scoped `R16MigrationEntryRuntime` opens one
  `R16DataRuntime` and runs bounded M0-M13 pages on the IO dispatcher. The
  persistent bootstrap/checkpoint state supports stop, resume, and retry.
- Recovery: cancellation is observed at a bounded page boundary; recoverable
  failures, including low storage, are shown without a fabricated percentage.
  The most recent sanitized failure can be exported through Android's document
  picker and is bounded by the runtime's 16 KiB report limit.
- Safety: create `ShippyBackupV1`, checkpoint each phase, verify counts and
  invariants. The `READY_TO_SWITCH` state remains inside the isolated recovery
  host; it is not a cutover action or an activation trigger.
- Backup: `R16BackupRuntime` exists behind the explicitly opened inactive R16
  data runtime and does not change production authority.
- Active data owner: a lazy singleton returns the same `R16DataRuntime` to future
  Library/playback consumers only for explicit isolated `R16AuthorityMode.ACTIVE`;
  the durable `R16_ACTIVE` marker cannot select that mode. It opens nothing in
  legacy, migration, ready, corrupt, or `ACTIVE_UNAVAILABLE` modes. The isolated
  ACTIVE host binds the same owner to Songs/browser/playback, but durable startup
  remains unavailable.
- The isolated Songs host now has contextual search with accessible search, clear,
  empty, and paged-result UI. It reuses the existing Library-scoped canonical FTS
  and exact `RecordingId` playback path; it is not the full R16 Library Search
  surface.
- Library Search V1 is a dedicated local-only surface, separate from Global,
  Songs contextual, and playlist contextual search. It Room-pages Songs,
  Playlists, and Artists, and query-filters bounded Liked, Local, and Downloads
  streams. Existing canonical Recording, Playlist, Artist, and system-collection
  playback routes are reused. Releases are visibly disabled/deferred because no
  live Release graph is present.
- Authority: legacy storage stays read-only for import. There are no dual
  writes or second player. The isolated ACTIVE composition has one R16 read-model
  and playback authority, while durable `R16_ACTIVE` remains fail-closed and the
  production authority remains R15.
- Isolated ACTIVE surface: Global Search remains separate from Library Search,
  leads with bounded canonical local-FTS results, applies a 200 ms debounce, and
  caps each configured-provider section at 20. Provider failures are isolated
  with partial retry; selected results persist exact `SourceKey` identity for
  JioSaavn and shared YouTube/YouTube Music video identity. MediaSession play
  requires a strict `RecordingId`; its same session drives mini-player
  buffering/paused/playing state and controls.
- The same isolated surface now has paged canonical Playlists browse/detail.
  Exact `PlaylistEntryId` row playback and atomic Play/Shuffle `PlayContext`s use
  full ordered lightweight playlist seeds rather than a 50-row rich-view page.
  Fresh per-context `QueueEntryId`s retain `PlaylistEntryId`; cached bounded
  system projection supports 10,000-entry logical queues through the same
  MediaSession and mini-player. Play/Shuffle now wait for MediaController
  connection before dispatch.
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
- Mini-player tap now opens a full scrollable, artwork-led current-item Now
  Playing surface. It keeps canonical `QueueEntryId`/`RecordingId` internal while
  presenting metadata, duration, progress, and error; uses a view-bound playing
  tick and guarded seek; and routes previous, play/pause, next, repeat, and shuffle
  through the existing MediaController -> MediaSession ->
  `R16SystemPlaybackCommands` path. The mini hides while expanded, Back/empty state
  remains usable.
- Now Playing has a one-way Save to Liked action scoped to the exact current
  `RecordingId`. The shared R16 runtime writes `library_recording` with
  `liked=true` and `explicitlySaved=false`; success leaves a filled disabled
  control. The UI resets disabled when `RecordingId` changes and ignores stale
  or mismatched Like emissions, preventing A-state/B-action identity races.
  Unlike and the full Save Destinations sheet remain absent.
- Now Playing add-to-playlist V1 captures the displayed `RecordingId`,
  `QueueEntryId`, title, and artist before navigation, then Room-pages canonical
  playlist destinations at 50. Selecting one appends exactly one fresh
  `PlaylistEntryId` in one transaction, so duplicate Recording occurrences stay
  legal. The common append reads only the last entry; only sparse-key exhaustion
  rebalances the affected playlist. Playlist membership now contributes to the
  derived Library membership index without creating a `library_recording` row;
  insert/update/delete triggers, migration/backfill, and rebuild predicates are
  aligned, with a one-time trigger upgrade and targeted backfill for existing v3
  databases. Loading, Adding, retry, failure, and retained tokenized completion
  remain truthful across STOPPED/configuration changes. Full Save Destinations
  membership selection/removal, create-playlist, and multi-target behavior remain
  deferred. No physical-device, durable activation, or M14/cutover claim changes.
- Artists in your Library is a canonical current-membership read surface, not
  saved-artist or saved-release functionality. Artist summaries and detail
  recordings use duplicate-safe `DISTINCT` queries with deterministic 50-row
  paging. Browser/playback IDs retain the exact `ArtistId` + `RecordingId` pair;
  Artist Play and row selection build the full ID-only artist context with
  `ARTIST` origin through the existing MediaSession route. Detail Play is gated
  on both a connected controller and nonempty artist recordings.
- Artist detail Shuffle uses that same exact Artist browser ID and one MediaSession
  path. Play and Shuffle share the connected-controller/nonempty-results gate.
- Now Playing has a friendly failed-state Retry for the exact displayed
  `QueueEntryId` + `RecordingId`. It dispatches `PlaybackCommand.RetryCurrent`,
  which revalidates the occurrence and failed phase inside the serialized
  coordinator before using the established reprepare/one-bounded-fallback path.
  There is no source picker; Next remains the explicit skip action.
- Queue V1 is a current-anchored, maximum-50 lightweight presentation. Prev/Next
  page by exact `QueueEntryId`, revision, and cache while preserving the bounded
  MediaSession queue. The live session occurrence supplies the exact highlighted
  row. Reorder, remove, and multi-select are deliberately deferred.
- The isolated Home surface keeps Recent bounded to eight and History Room-paged
  at 50. Continue Listening uses the current MediaSession occurrence; its
  conditional resume is atomically rechecked by the serialized coordinator using
  exact `QueueEntryId` and `RecordingId`, so stale UI cannot resume another item.
- Home pinned-playlist shortcuts V1 reads at most eight valid `PLAYLIST` entries
  from `library_layout_entry`, its sole pin/order authority. An inner join removes
  stale playlist rows; empty state is hidden, and a shortcut opens the existing
  detail with its exact `PlaylistId`. This adds no system pins, pin/reorder
  mutations, saved-artist/release claim, or Album/Release vertical.
- Playlist summary pin/order likewise uses only a `PLAYLIST`-scoped
  `library_layout_entry` left join: missing layout rows are unpinned and
  deterministically last. The exact `PlaylistId` pin toggle is one Room
  transaction, retaining an existing layout key or appending one; per-row
  in-flight state disables its control, and successful writes refresh Paging and
  the shared Home Flow. Reorder, system pins, and saved-artist/release behavior
  remain absent.
- Playlist lifecycle V1 creates an exact canonical UUID `PlaylistId` with `USER`
  provenance, default `CUSTOM`/`ASC`, and one unpinned layout row in one
  transaction. Rename/delete use that exact ID for every canonical playlist,
  including imported/device rows, preserving provenance. Delete removes layout and
  playlist (cascading its entries) without disturbing unrelated Library/download
  state; playlist FTS triggers remain correct. Create/Rename/Delete dialogs use
  retained tokenized completion state that survives STOPPED/configuration changes
  and is acknowledged exactly once. Multiselect remains deferred; persisted
  playlist sort and adjacent entry reorder are covered below.
- Playlist-entry removal V1 targets the exact canonical `PlaylistId` plus
  `PlaylistEntryId`, preserving duplicate Recording occurrences. Missing,
  mismatched, and repeated operations return false without mutation; imported and
  device-provenance canonical playlists remain mutable. A detail-row popup and
  confirmation use per-entry in-flight state and retained operation-token
  completion across STOPPED/configuration changes; success refreshes Paging and
  the header summary. Batch removal remains deferred; persisted playlist sort
  and adjacent entry reorder are covered below.
- Playlist sort V1 persists the typed mode and direction for each playlist and
  applies one deterministic order to both the paged visible rows and the full
  lightweight Play/Shuffle context. It is filter-aware, uses exact
  `PlaylistEntryId` identity for duplicates, fails closed for malformed extras,
  and treats invalid filters as empty results. `CUSTOM` is normalized to `ASC`
  and is the only reorderable mode; other modes are temporary/non-reorderable in
  this V1. The original Auxio `DialogSort` bottom sheet is reused.
- Playlist-entry adjacent reorder V1 is integrated for `CUSTOM` playlists with
  a blank filter only. Screen-owned Edit order mode provides accessible Move
  up/Move down actions for the exact `PlaylistEntryId`; Back/Done exits the mode,
  while play/remove/search actions are guarded during editing. The mutation
  repository requires the paired `PlaylistId` and `PlaylistEntryId`, moves one
  canonical neighbor, and returns typed no-ops for missing, boundary, or
  non-CUSTOM requests. The common indexed path swaps two unique sparse order
  keys; tied/shared/corrupt keys fall back to an exact adjacent swap and rebalance
  only the affected playlist. Duplicate Recording occurrences remain
  independent. Drag, arbitrary cross-page reorder, queue reorder, batch actions,
  schema changes, M14, and device acceptance remain out of scope.
- Playlist title FTS advances the schema to v3. The v2-to-v3 migration backfills
  it; insert/rename/delete triggers maintain it; generated schema `3.json` is
  present. Backup restore clears, rebuilds, and verifies the derived
  `playlist_fts`. The UI keeps its query, hides empty headers, shows an honest
  all-stream empty state, offers shared-error Retry all, and has horizontal 48dp
  navigation.
- Rule-driven Liked, Local, and Downloads collection pages use Room Paging at 50.
  Display never materializes the full collection; Play/Shuffle alone reads the
  ordered ID-only context needed to construct a full queue.

### Prior integrated verification and limits (2026-08-20)

- The prior focused cross-slice gate is green: `spotlessCheck` and
  `:app:compileDebugKotlin` completed successfully, with 60/60 focused tests
  passing (54 app, 6 data). This includes Queue/Search, Home/Continue/History,
  system collections, playback/system seams, and narrowed download coverage.
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
- Artists + failed-state Retry are green: `spotlessCheck`,
  `:app:compileDebugKotlin`, and 41/41 focused tests pass (PlaybackCoordinator
  15, SystemBridge 8, NowPlayingMapper 3, BrowserResolver 6,
  BrowserServiceAdapter 4, data BrowserRepo 5). Cached `:app:assembleDebug` is
  BUILD SUCCESSFUL with 255 actionable tasks. The temporary Gradle settings were
  restored to the tracked `gradle.properties` hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`.
- Home pinned-playlist shortcuts V1 passes `spotlessCheck`,
  `:app:compileDebugKotlin`, and `R16HomeReadRepositoryTest` 3/3. Normal
  `gradle.properties` was restored to tracked hash
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`.
- Artist Shuffle + playlist layout pin authority passes `spotlessCheck`,
  `:app:compileDebugKotlin`, `R16MediaBrowserRepositoryTest` 6/6, and
  `R16LibraryMutationRepositoryTest` 3/3 with zero failures. The combined
  127-task gate is BUILD SUCCESSFUL in 7m18s under transient 4 GiB in-process
  settings; normal tracked `gradle.properties` was restored to
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`. Normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks); debug APK:
  69,076,401 bytes, SHA-256
  `8240213360494362d576afb2928dbf11ee6e292595fdfe48bf3100266eabf82d`.
- Library Search V1 first passed `spotlessCheck`, `:app:compileDebugKotlin`,
  `PagingDaoTest` 5/5, migration 1/1, and BackupRuntime 2/2 (8/8; 127 tasks,
  5m54s). Review corrections reran spotless, app compilation, `PagingDaoTest` 5/5,
  and migration 2/2 (7/7; 127 tasks, 4m15s). Normal Gradle content was restored to
  tracked hash `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`. Normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 4m53s); debug
  APK: 69,080,433 bytes, SHA-256
  `cadd88c0fa36081027c4b9f2e51e5198318feb88daebc2094648d90a33175776`.
- Playlist lifecycle V1 passes `spotlessCheck`, `:app:compileDebugKotlin`,
  `R16LibraryMutationRepositoryTest` 8/8, and `R16MediaBrowserRepositoryTest`
  6/6 (14/14). Its 127-task gate is BUILD SUCCESSFUL in 4m15s after one narrow
  visibility fix. Normal tracked `gradle.properties` was restored to
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 2m17s). Debug
  APK: 69,085,697 bytes, SHA-256
  `f1ad19c11cedee71a456ce83aa958561fac558194bce11530b27a6a60c51906e`.
- Playlist-entry removal V1 passed independent P0/P1 review after its summary
  refresh correction, `spotlessCheck`, `:app:compileDebugKotlin`, and
  `R16LibraryMutationRepositoryTest` 9/9. The focused 127-task gate is BUILD
  SUCCESSFUL in 3m24s after one nullable compile correction. Normal tracked
  `gradle.properties` was restored to
  `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 2m36s). Debug
  APK: 69,086,677 bytes, SHA-256
  `4bfb6113178d70e1abce958a61b9fa1624048389ff8fe98db6434adcb925f52e`.
- Now Playing add-to-playlist V1 passed independent P0/P1 review,
  `spotlessApply`, `spotlessCheck`, `:app:compileDebugKotlin`, and 19/19 focused
  data tests with zero failures, errors, or skips: mutation repository 13/13,
  membership index 2/2, migration 2/2, and backup runtime 2/2. The 129-task gate
  is BUILD SUCCESSFUL in 4m53s. Normal `gradle.properties` was restored to tracked
  hash `53e3cb5bf6f2344048eaf16f0f61997b4c826b7f`; normal-settings cached
  `:app:assembleDebug` is BUILD SUCCESSFUL (255 actionable tasks, 3m56s). Debug
  APK: 69,091,796 bytes, SHA-256
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
- The prior Kotlin compiler OOM in `ShippyDownloadWorker`/transfer-request code
  was mitigated by narrow download source splits. Compilation succeeded with
  transient 4 GiB in-process Gradle settings; settings were restored afterward
  and the local disk/build cache remains retained.
- The new isolated Search/mini-player slice has `:app:compileDebugKotlin` green,
  `GlobalSearchCoordinatorTest` 3/3 green, and `MiniPlayerUiStateMapperTest` 2/2
  green. Integration review found no P0 issue after the provider-retry fix.
- The playlist vertical has `:app:compileDebugKotlin` and data compilation green;
  four focused suites pass 16/16 tests.
- The Now Playing slice has `spotlessApply` and `:app:compileDebugKotlin` green;
  focused Now Playing, mini-player, and system-bridge coverage passes 10/10 tests.
- The synthetic owner-shaped v10 fixture now resumes from a bounded M1
  checkpoint, reaches verified `READY_TO_SWITCH`, publishes the M13 recovery
  archive, and re-enters idempotently; its focused suite passes 14/14 tests.
  M7/M11/M13 preserve the shared expected-count audit envelope throughout.
- An earlier app-compile stop during shared Gradle/JVM contention is historical,
  not a current gate result.
- No real redacted owner-device v10 database fixture exists yet. No physical
  device or runtime performance measurements have been performed. Lifecycle
  attach/release ordering, attach-failure cleanup, and listening-delivery drain
  are host-tested; physical playback, system surfaces, process death, and
  provider-network retry, authenticated Last.fm delivery, and mini-player behavior
  remain unverified.
- Failure-report export is available for an in-memory recoverable failure. A
  process-restart export from only persisted failure state is not yet provided.
