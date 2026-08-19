# Shippy Stabilization and Product-Cohesion Spec

**Status:** implementation-ready  
**Date:** 2026-08-18  
**Code baseline:** `main` at `7fb431c9f`  
**Source inputs:** current repository, canonical product documents, and the user device bug report  
**Priority:** correctness first, then responsiveness, then visual cohesion

This is a focused repair pass, not a rewrite. Existing Shippy domain models,
repositories, ViewModels, XML layouts, RecyclerViews, playback authority, and
bottom-sheet infrastructure remain the foundation.

## Mission

Make the current Shippy build trustworthy and pleasant in daily use by fixing
playback identity, Continue Listening, Last.fm completion, Library and playlist
workflows, and the visibly inconsistent Home, save, lyrics, and action surfaces.

The pass is complete only when a user can shuffle a playlist, move through the
Library, manage a playlist, save a track, and scrobble a listen without the app
showing stale identity, ignoring input, or changing UI language between flows.

## Current Context

The codebase already has most required foundations:

- `PlaybackStateManager` is the single playback and queue authority.
- `ShippyPlaybackController` resolves provider/local queues before handing them
  to the player.
- R15.3 added atomic Media3 queue/index projection and stable queue-item IDs.
- `LibraryRelationshipRepository` owns Liked and playlist membership.
- `ShippyCollectionDetailFragment` already provides one detail surface for
  Liked, Downloads, Local, and user playlists.
- local-only global Search mode and in-playlist filtering already exist.
- Last.fm already has authentication, now-playing, a durable scrobble outbox,
  profile loading, and top-track loading.
- `ViewBindingBottomSheetDialogFragment` is the existing sheet foundation.
- RecyclerView and Coil already provide lazy row binding and artwork loading.

The current implementation does not yet prove the reported device behavior is
fixed. Some reports describe missing behavior; others describe behavior that is
present in source but needs reproduction and correction on the current build.

### Evidence map

| ID | Device report | Current code evidence | Classification |
|---|---|---|---|
| STAB-001 | Playlist Play/Shuffle can show one track while another audio item plays | R15.3 changed Media3 acknowledgement and added pure `RawQueueIdentityTest`, but exact device reproduction is still pending | P0, reopen and verify |
| STAB-002 | Continue Listening play icon opens the player without playing | `homeCurrent` has one click listener that only calls `openPlayback()`; the play glyph has no action | P1, missing behavior |
| STAB-003 | Last.fm shows now playing but does not reliably register the completed listen | `LastFmClient` treats any HTTP success containing `<lfm status="ok">` as delivered and does not parse accepted/ignored scrobbles | P1, concrete correctness gap |
| STAB-004 | No small current-track scrobble result | `LastFmScrobbleTracker` exposes no UI state | P2, missing behavior |
| STAB-005 | Last.fm recommendations are missing from Home | Home loads `user.getInfo` and `user.getTopTracks` only | P2, missing behavior |
| STAB-006 | Last.fm artwork becomes a generic star | parser accepts the last HTTPS image without rejecting Last.fm placeholders or resolving a provider fallback | P2, concrete gap |
| STAB-007 | Home is long and crowded | Recently Played, all library shortcuts, Last.fm top tracks, and Recent Downloads are vertical nested lists | P2, concrete UX gap |
| STAB-008 | Settings icon is nearly invisible in light theme | `ic_settings_24.xml` hard-codes white strokes; `set_lastfm_working` also contains mojibake | P1, concrete theme/text gap |
| STAB-009 | Library entry/tab changes feel slow | five-tab ViewPager is bounded to one warm neighbor, but collection updates still include full adapter invalidation and no device frame baseline exists | P1, profile then repair |
| STAB-010 | Library search opens/behaves like global online Search | current navigation passes `localOnly=true`; provider selector is hidden in that mode | P1, present but must verify |
| STAB-011 | Playlist search/sort/order is unreliable | detail filtering and per-collection sort preferences exist; Room positions append and persist, but no end-to-end order test covers add/remove/re-add/UI projection | P1, verify and close gaps |
| STAB-012 | Long-press playlist management and pin editing are missing | long press is consumed by drag; actions exist only in playlist detail overflow | P2, interaction conflict |
| STAB-013 | Batch removal is missing | collection detail has no selection state or contextual batch action | P2, missing behavior |
| STAB-014 | Save-to-Library flyout feels wrong | unsaved uses plus, saved uses check, and destination editing is a `MaterialAlertDialog` | P2, concrete UX gap |
| STAB-015 | Lyrics preview is visually disconnected and changes size | card is `wrap_content` with only `minHeight`; no artwork-derived tone exists | P2, concrete UX gap |
| STAB-016 | Sort/action/settings surfaces feel unrelated | current flows mix dialogs, menus, and sheets despite an existing shared sheet base | P2, bounded consistency pass |

## Goals

1. Audio, current queue item, title, artwork, lyrics, actions, mini-player, full
   player, notification, and MediaSession always identify the same occurrence.
2. Every visible playback affordance performs the action it depicts.
3. Last.fm reports accepted, queued, retrying, ignored, or authentication-needed
   outcomes truthfully and never labels an ignored scrobble successful.
4. Home becomes compact: continuation, horizontal recents, recommendations, and
   pinned shortcuts only.
5. Library and playlist search, ordering, pinning, and removal work without
   leaving their context.
6. Warm navigation and list interaction feel immediate on a library of roughly
   1,500 songs.
7. Save, sort, playlist actions, and lyrics share Shippy's calm, artwork-led,
   native Android visual language.

## Non-Goals

- No Compose migration or Auxio rewrite.
- No new player, queue, library, or navigation architecture.
- No Paging 3 conversion. RecyclerView already binds visible rows lazily; fix
  measured update work rather than adding a second data model.
- No new recommendation engine or server. Last.fm remains the taste source and
  existing providers remain the playback/artwork source.
- No Crew protocol, Push/Pull, relay, download, or provider redesign in this
  pass unless a playback-identity fix touches a shared invariant.
- No app-wide conversion of every historical dialog. Only touched choice/action
  surfaces are standardized.
- No analytics/telemetry service. Temporary debug logs and local frame metrics
  are sufficient.
- No accessibility release pass; preserve current semantics and touch targets,
  but the dedicated accessibility pass remains deferred.
- No speculative cache, thread, GPU, or memory system without a measured
  bottleneck.

## Users

- A local-library user with hundreds or thousands of tracks.
- A mixed-source user who searches providers, downloads tracks, and saves them
  into Liked or playlists.
- A Last.fm user who expects reliable scrobbling and useful discovery.
- A daily listener who values fast, quiet interactions over dense controls.

## Assumptions

- The first implementation build is produced from this exact or a later known
  commit and installed over the same application identity.
- The reported playback mismatch remains open until reproduced or disproved on
  a physical device; passing JVM tests alone does not close it.
- The test library contains at least 1,000 local tracks, one mixed-source
  playlist, one Liked collection, and a valid Last.fm account.
- Existing system collections remain immutable: Liked, Downloads, and Local can
  be pinned/reordered but not renamed or deleted.
- User playlists remain editable and may contain local, downloaded, and
  provider-only tracks together.

## Requirements

### R1. Canonical playback identity

- `QueueItemId`, not numeric list position or `TrackId`, is the occurrence
  identity for active playback.
- Media3's committed current `mediaId` must equal
  `PlaybackStateManager.currentQueueItem.id.value` after every transition.
- Shuffle toggle must not change the current occurrence. Shuffle Play may choose
  an occurrence, but the chosen audio and every visible surface must match it.
- Next, Previous, GoTo, automatic transition, queue reorder, provider locator
  refresh, and restoring a saved queue must publish one atomic queue/index
  snapshot.
- `PlaybackViewModel.displayItem`, pager selection, lyrics lookup, player action
  state, queue highlight, notification, and MediaSession metadata must derive
  from that snapshot or the matching queue-item ID.
- Async artwork, lyrics, provider, or pager callbacks must be ignored when their
  captured queue-item ID no longer matches the active item.
- The app must not hide a mismatch by delaying text until a later frame. The
  source identity must be correct.

### R2. Continue Listening

- Tapping the card body opens Now Playing without changing play/pause state.
- Tapping the trailing play/pause button toggles playback in place.
- When the current queue is paused, Play resumes it before opening Now Playing
  only if the product chooses to open the player; the required result is audible
  playback and updated button state.
- When no resumable queue exists, the entire Continue Listening section is
  hidden. It must not render a decorative play button.
- Recently Played item taps continue to open a resolved search/result context;
  they are not silently treated as the current queue.

### R3. Last.fm delivery truth

- Keep the existing `LastFmListenPolicy`: tracks at most 30 seconds are not
  eligible; eligible listens scrobble after half the duration or four minutes,
  whichever comes first; seeks do not manufacture listened time.
- Parse the Last.fm scrobble response, including batch `accepted`, `ignored`, and
  each `ignoredMessage` code. HTTP 2xx plus `<lfm status="ok">` is not enough.
- Delete accepted and permanently ignored entries from the outbox. Retain
  transient failures for retry. Mark error 9 as reauthorization required.
- Do not enqueue the same queue occurrence twice.
- A process restart or temporary network failure must preserve queued entries
  and retry them in oldest-first batches of at most 50.
- Delivery state for the active occurrence is one of: `Disabled`, `NowPlaying`,
  `Eligible`, `Queued`, `Scrobbled`, `Retrying`, `Ignored`, or `ReauthRequired`.
  This is an in-memory UI projection, not a new history database.
- A successful UI state means Last.fm accepted that occurrence, not merely that
  Shippy sent a request.

### R4. Scrobble indicator

- Show one small Last.fm status affordance in Now Playing only when Last.fm is
  configured or needs reauthorization.
- Default states use a quiet on-surface-variant tint; accepted uses the accent;
  retry/ignored/reauth use a warning/error tint.
- The icon has a content description and may expose the concise reason on tap.
- Do not add a feed, toast every transition, modal success dialog, or permanent
  badge to song rows.

### R5. Last.fm recommendations and artwork

- Replace the Home top-tracks list with `Recommended for you`.
- Use one deterministic daily seed from the user's recent/top Last.fm tracks and
  call `track.getSimilar` with `autocorrect=1` and a bounded result limit.
- Exclude the seed itself and exact normalized duplicates. Cap the visible list
  at eight items.
- Rotate the seed by local date so recommendations change without request fanout.
- Cache the last successful recommendation payload with the existing overview
  cache behavior and show stale content while offline.
- A recommendation tap opens Shippy Search with `artist + title`; Last.fm does
  not become a playback provider.
- Reject empty and known Last.fm placeholder artwork URLs. For visible items
  without real art, query only the preferred searchable provider, accept an
  exact normalized artist/title match, and cache the resulting artwork URL.
- Limit artwork resolution to visible recommendation items and at most two
  concurrent provider lookups. Fall back to Shippy's neutral track placeholder,
  never the Last.fm star.

### R6. Home hierarchy

Home content order is:

1. Active Crew card, only while starting/active/ending.
2. Continue Listening, only with a resumable current item.
3. Recently Played, horizontal, at most six unique tracks.
4. Recommended for you, horizontal, at most eight tracks, only when available.
5. Pinned, containing only pinned system collections, user playlists, and saved
   provider entities.

Additional rules:

- Remove the `Your music` heading, aggregate song/album/artist count, and Recent
  Downloads section from Home. Downloads remain first-class in Library.
- Do not show empty section headings or error-shaped blank blocks.
- Deduplicate Recently Played by canonical `TrackId`, keep most recent first,
  and retain the existing durable recent-listening source.
- Horizontal rows use stable item IDs, bounded item counts, and shared artwork
  card dimensions.

### R7. Theme and text correctness

- Toolbar/menu icons must use theme attributes or explicit menu tint, never a
  hard-coded white/black stroke.
- Verify Home settings in light, dark, and black themes.
- Replace the corrupted `Workingâ€¦` string with `Working…` and run a targeted
  mojibake scan over English Shippy strings.
- Do not globally recolor Lucide vectors in this pass; fix only theme-incompatible
  assets encountered by these flows.

### R8. Library responsiveness

- Capture a before/after `gfxinfo` trace for 20 warm Home ↔ Library transitions
  and Library tab changes on the same device/build type.
- Preserve the current `offscreenPageLimit = 1`; do not preload all five tabs.
- Set adapters/listeners once per view lifecycle and update through DiffUtil or
  payloads. Avoid `notifyDataSetChanged()` in the unified collection list.
- Do not map provider queues, scan media, decode artwork, or sort the full
  library on the main thread.
- Preserve each Library tab's scroll position and selected tab across warm
  bottom-navigation changes.
- Warm navigation must show existing Library chrome in the first rendered frame,
  show list content within 250 ms on the test device, produce no frozen frame,
  and keep slow frames below 5% across the 20-transition sample.
- If the baseline already meets a requirement, record it and do not add code.

### R9. Local Library search

- Library search may reuse `SearchFragment`, but `localOnly=true` is mandatory
  and visible in the UI through a `Search your library` hint.
- No provider selector, provider request, provider loading header, or online
  result is permitted in local-only mode.
- Back returns to the previous Library tab and scroll position.
- An empty query shows local recent searches only if those records came from
  local selection; otherwise show a quiet empty state.
- Typing filters off the main thread and cancels the prior query.

### R10. Playlist detail search, sort, and order

- Playlist search remains inside the open collection and filters title, artist,
  and album without navigating to global Search.
- The toolbar search field uses the normal full-width Shippy search treatment,
  a clear-text action, and no tiny blue label.
- Replace the collection sort alert with a Shippy bottom sheet.
- Sort modes are `Playlist order`, `Title`, `Artist`, and `Duration`, persisted
  independently per collection.
- Sorting changes only the projection. It never rewrites stored playlist order.
- In Playlist order, adding A, B, C displays A, B, C. Removing B and adding it
  again displays A, C, B. Reordering persists exactly after process restart.
- A newly saved track appears immediately when its metadata is available; while
  unresolved, the header accurately reports pending metadata rather than
  silently dropping the membership.
- Drag reordering is enabled only for user playlists with empty search and
  Playlist order selected.

### R11. Playlist management and pinning

- A playlist-row long press opens a shared action sheet.
- System collections expose Pin/Unpin only.
- User playlists expose Pin/Unpin, Rename, Change artwork, and Delete.
- Destructive Delete requires confirmation and deletes the playlist, not its
  underlying local/downloaded media.
- Because long press is now actions, collection reordering uses an explicit
  `Edit order` mode with visible drag handles and Done/Cancel. This avoids one
  gesture doing two incompatible jobs.
- Dragging across the pinned boundary changes the moved collection's pin state,
  matching the current persisted layout rule.
- Cancel restores the pre-edit projection; Done persists once.

### R12. Batch track actions

- Long-pressing a track in a collection enters selection mode. Further taps
  toggle selection; Back exits selection without mutation.
- User playlist action: `Remove from playlist` removes selected memberships only.
- Liked action: `Remove from Liked` clears selected liked relationships only.
- Downloads action: `Remove downloads` removes selected durable artifacts only;
  tracks may remain in other collections.
- Local action: `Delete from device` uses the existing
  `LocalMediaDeletionCoordinator`, shows one count-aware destructive
  confirmation, and respects Android MediaStore/SAF consent.
- Batch mutations are transactional where they affect Room rows. Partial media
  deletion reports the failed count and keeps failed rows visible.
- Do not use the ambiguous label `Delete` without its target.

### R13. Save-to-Library behavior

- Unsaved state uses circle-plus. One tap saves to Liked.
- Saved state uses a filled red heart. It means the track is in Liked or at
  least one playlist.
- Tapping the saved heart opens a Shippy bottom sheet listing Liked and user
  playlists with their current checked state.
- Apply all destination changes through the existing atomic
  `updateSavedDestinations` repository path.
- If the active queue-item ID changes while the sheet is open, dismiss the sheet
  instead of applying selections to the wrong song.
- Local, downloaded, and provider tracks use the same save interaction.

### R14. Lyrics presentation

- On standard portrait (`layout-h520dp`), the preview card has one stable height
  across Loading, Ready, Unavailable, and Error states. Use a fixed 304 dp card
  for this qualifier; keep existing compact/landscape alternatives bounded.
- The preview shows one emphasized current line and up to four subdued following
  lines. Text updates must not remeasure the full player.
- Use one small `ArtworkToneExtractor`, backed by a 32-entry in-memory LRU, to
  derive a muted card color from the already loaded artwork. Blend heavily with
  the theme surface and verify text contrast. Do not build a new theme engine or
  animated gradient system.
- When art is absent or extraction fails, use normal theme surface tokens.
- Full lyrics reuse the extracted tone, preserve synchronized active-line
  emphasis, allow tapping a timed line to seek, and keep controls clear of text.
- Lyrics loading for an older queue-item ID must never replace the active song's
  lyrics or tone.

### R15. Sheet and dialog consistency

- Use `ViewBindingBottomSheetDialogFragment` and existing Shippy sheet behavior
  for saved destinations, collection sorting, playlist actions, and batch action
  choices.
- Sheets use one title row, 48 dp minimum actions, theme surface colors, and a
  short drag-to-dismiss threshold consistent with queue/provider sheets.
- Keep destructive confirmation as a Material alert after the action sheet.
- Settings and About retain their existing navigation and information; only fix
  incorrect tint, typography token, spacing, and stale Auxio-facing labels found
  in the touched screens. Do not rebuild them.

## Proposed V1

This stabilization pass ships in four vertical slices. Each slice must be usable
and verifiable before starting the next.

1. **Truth:** reproduce and close playback identity, Continue Listening, and
   Last.fm accepted/ignored delivery.
2. **Library:** fix local search verification, playlist order/search/sort,
   playlist management, batch actions, and measured Library jank.
3. **Home:** compact hierarchy, Last.fm recommendations/artwork, light-theme icon,
   and text cleanup.
4. **Cohesion:** save drawer, stable artwork-toned lyrics, and touched-sheet
   consistency.

No slice introduces a parallel state owner. If implementation requires one, the
design is wrong and must be reduced before coding.

## Architecture and Design

### State ownership

| State | Sole owner | Consumers |
|---|---|---|
| active queue and occurrence | `PlaybackStateManager` | player UI, queue, Last.fm, Crew, MediaSession |
| mapped active display item | `PlaybackViewModel` | mini/full player, Home continuation, lyrics |
| Liked and playlist membership | `LibraryRelationshipRepository` | player actions, Library, collection detail |
| collection layout/pin order | current layout store + repository | Playlist tab and Home pinned projection |
| Last.fm outbox | `LastFmScrobbleDao` | tracker delivery only |
| active scrobble presentation | `LastFmScrobbleTracker` StateFlow | Now Playing only |
| Home recommendation cache | extended Last.fm overview cache | Home ViewModel |

### Data flow constraints

```text
Media3 committed mediaId
  -> RawQueue atomic projection
  -> PlaybackStateManager queue + index
  -> PlaybackViewModel current QueueItemId
  -> title / art / lyrics / actions / MediaSession / Last.fm
```

```text
Last.fm top/recent seed
  -> one track.getSimilar request
  -> dedupe + cap
  -> preferred-provider artwork fallback for visible misses
  -> cached Home recommendations
```

```text
Collection IDs in Room order
  -> metadata join without reordering IDs
  -> search/sort projection
  -> ListAdapter DiffUtil
  -> visible RecyclerView rows
```

### New types allowed

Only these small additions are justified:

- `LastFmScrobbleResult` or equivalent parsed batch result.
- `LastFmScrobbleStatus` UI enum/state keyed by current `QueueItemId`.
- `LastFmRecommendation` as part of the existing overview/home model.
- one shared playlist/action bottom-sheet model.
- `ArtworkToneExtractor` with a bounded in-memory cache.
- selection state inside `ShippyCollectionDetailViewModel`.

Do not add a new repository for each screen, a generic workflow engine, an event
bus, a second database, or a design-system module.

## Task Tree

### 0. Establish reproducible evidence

- [ ] **0.1** Install a build from the recorded commit/application ID and capture
  the exact playlist mismatch sequence: Liked Play, Liked Shuffle Play, shuffle
  toggle, Next, Previous, automatic transition, and queue GoTo.
- [ ] **0.2** In debug builds only, log Media3 `mediaId`, canonical
  `QueueItemId`, `TrackId`, projected index, and shuffle state at transition
  acknowledgement. Remove or gate verbose logs before release.
- [ ] **0.3** Capture baseline `gfxinfo` for 20 warm Home ↔ Library transitions
  and tab switches with the same 1,000+ track library.
- [ ] **0.4** Record which report items already pass on this build. Passing source
  inspection alone is not closure.

### 1. Close playback and Last.fm correctness

- [ ] **1.1** Extend `RawQueueIdentityTest`/playback tests to cover shuffle
  toggle with current occurrence preservation, Next/Previous/GoTo, automatic
  transition, and a queue mapping replacement arriving after an index change.
- [ ] **1.2** Audit every `StateAck.IndexMoved` path in
  `ExoPlaybackStateHolder` and keep acknowledgement only after Media3 commits.
- [ ] **1.3** Add a PlaybackViewModel regression test proving a stale pager or
  lyrics result cannot replace a newer queue-item ID.
- [ ] **1.4** Run the exact device sequence from 0.1 and compare all visible
  identity surfaces with actual audio.
- [ ] **1.5** Split Continue Listening card-body and trailing-control actions;
  bind the icon to `isPlaying`.
- [ ] **1.6** Parse Last.fm accepted/ignored batch results and update outbox
  deletion/retry behavior per R3.
- [ ] **1.7** Add fixture tests for accepted, ignored, mixed batch, malformed,
  retry, and reauth responses plus tracker tests for one occurrence/one enqueue.
- [ ] **1.8** Expose active scrobble status and bind the small Now Playing
  indicator.

### 2. Repair Library and playlist workflows

- [ ] **2.1** Re-run Library local-only search and playlist-detail search on the
  current build. Fix only failing routing/query/UI state.
- [ ] **2.2** Replace collection sort alert with the shared bottom sheet while
  preserving per-collection preferences.
- [ ] **2.3** Add pure order tests for append, remove/re-add, drag reorder,
  unresolved metadata, sorting without storage mutation, and process reload.
- [ ] **2.4** Add the Library collection action sheet and explicit Edit order
  mode; wire system/user action visibility exactly per R11.
- [ ] **2.5** Add collection-detail selection state and context-specific batch
  mutations per R12.
- [ ] **2.6** Replace full collection-list invalidation with DiffUtil/payload
  updates and keep drag/edit projections stable.
- [ ] **2.7** Profile the Library trace. Move only observed main-thread mapping,
  sorting, or artwork work off-main; do not increase ViewPager preload.
- [ ] **2.8** Repeat the same `gfxinfo` sample and record the before/after result.

### 3. Compact and enrich Home

- [ ] **3.1** Restructure `fragment_shippy_home.xml` and adapters to the R6 order;
  remove Your music summary and Recent Downloads from Home only.
- [ ] **3.2** Convert Recently Played and recommendations to bounded horizontal
  lists with stable IDs and dedupe recents by `TrackId`.
- [ ] **3.3** Extend the Last.fm client/home model with one daily
  `track.getSimilar` seed request, dedupe/cap logic, and cached stale fallback.
- [ ] **3.4** Reject Last.fm placeholder images and add bounded preferred-provider
  artwork fallback for visible misses.
- [ ] **3.5** Filter Home library shortcuts to pinned items only.
- [ ] **3.6** Make the settings icon theme-tinted and fix English Shippy
  mojibake found by the targeted scan.

### 4. Unify save, lyrics, and sheets

- [ ] **4.1** Replace saved-destination alert with a bottom sheet and implement
  circle-plus → Liked → filled-heart state.
- [ ] **4.2** Guard the sheet by active `QueueItemId` and retain atomic repository
  updates.
- [ ] **4.3** Fix the portrait lyrics preview height and state layout; ensure all
  state transitions preserve the same player geometry.
- [ ] **4.4** Add the bounded artwork tone extractor and apply it to preview/full
  lyrics with theme fallback and contrast checks.
- [ ] **4.5** Verify timed-line seek, active-line emphasis, automatic scroll, and
  stale lyric cancellation.
- [ ] **4.6** Apply shared sheet tokens/behavior to the four touched action/choice
  flows and perform a light/dark/black visual pass.

### 5. Release gate

- [ ] **5.1** Run formatting and focused tests after each slice.
- [ ] **5.2** Run the full app JVM suite and lint once after integration.
- [ ] **5.3** Build one installable debug/profileable artifact only after the
  non-device gate passes.
- [ ] **5.4** Execute the device acceptance matrix below without clearing app data.
- [ ] **5.5** Update `docs/STATUS.md` with implemented, test-passed, device-passed,
  and still-open items separately.

## Acceptance Criteria

### Playback

- [ ] 50 consecutive transitions across playlist Play, Shuffle Play, shuffle
  toggle, Next, Previous, GoTo, and automatic advance produce zero audio/UI
  identity mismatches.
- [ ] During each transition, full player, mini-player, queue highlight, lyrics,
  notification, and MediaSession show the same canonical track.
- [ ] Continue Listening body opens; its control plays/pauses immediately.

### Last.fm

- [ ] An eligible real listen appears in the account's recent scrobbles.
- [ ] Accepted, ignored, retry, and reauth fixtures drive distinct outbox and UI
  results.
- [ ] Offline eligible listens survive process restart and deliver after network
  return.
- [ ] Home displays up to eight cached recommendations when configured and no
  Last.fm section when unconfigured.
- [ ] No Last.fm generic-star image is displayed.

### Library and playlists

- [ ] Warm Home ↔ Library and adjacent Library tab changes meet R8 frame targets
  with 1,000+ tracks.
- [ ] Library search performs zero provider requests and Back restores context.
- [ ] Playlist search remains in the playlist and clear-text restores all rows.
- [ ] Playlist order passes A/B/C, remove/re-add, drag, restart, and unresolved
  metadata cases.
- [ ] System collection actions never show Rename/Delete/Change artwork.
- [ ] User playlist pin, rename, artwork, delete, and order changes persist.
- [ ] Batch actions mutate only their named target and report partial local-file
  failures honestly.

### UI cohesion

- [ ] Home has no Your music summary or Recent Downloads section and uses the R6
  order without empty headings.
- [ ] Settings icon is visible in light, dark, and black themes.
- [ ] Save state is circle-plus when unsaved and filled red heart when saved; the
  destination editor is a sheet and cannot update a newly playing song.
- [ ] Lyrics preview geometry does not move across state/line changes; artwork
  tone remains subtle and readable.
- [ ] Save, sort, playlist action, and batch choice sheets share spacing, shape,
  color, and drag behavior.

## Verification Plan

### Automated

Run focused tests while implementing, then the existing full gate:

```powershell
rtk .\gradlew.bat :app:testDebugUnitTest --tests "*RawQueueIdentityTest" --tests "*PlaybackPagerStateTest" --tests "*PlaybackTransitionTest"
rtk .\gradlew.bat :app:testDebugUnitTest --tests "*LastFm*"
rtk .\gradlew.bat :app:testDebugUnitTest --tests "*Playlist*" --tests "*Collection*" --tests "*Library*"
rtk .\gradlew.bat spotlessCheck :app:testDebugUnitTest :app:lintDebug
```

Do not repeatedly run full Android builds during implementation. Build once at
the release gate.

### Performance

Use one device and build type before and after:

```powershell
adb shell dumpsys gfxinfo org.oxycblt.auxio.debug reset
# Perform 20 Home <-> Library transitions and Library tab changes.
adb shell dumpsys gfxinfo org.oxycblt.auxio.debug > shippy-library-gfxinfo.txt
```

Record total frames, slow frames, frozen frames, and visible-content latency.
Keep the evidence in the implementation report, not as permanent telemetry.

### Physical device matrix

1. Dark and light themes.
2. Current Android 15/16 device plus one Android 7-12 device if available.
3. 1,000+ local tracks and a mixed local/provider/download playlist.
4. App data retained across update.
5. Online Last.fm delivery, offline enqueue, process restart, network return.
6. System and user collection action visibility.
7. Rotation or compact-height layout for player/lyrics smoke coverage.

## Risks and Decisions

| Risk | Decision |
|---|---|
| R15.3 fix may already solve the reported identity bug | Treat it as open until the exact physical sequence passes; do not rewrite playback pre-emptively |
| Last.fm may accept the envelope but ignore individual entries | Parse per-entry results and expose ignored state |
| Last.fm artwork can be missing or generic | Reject placeholder, then bounded preferred-provider fallback |
| Recommendation fanout can slow Home or trigger rate limits | One daily rotating seed, one similar-track request, cached result |
| Library jank may tempt full caching/preloading | Measure first; retain one-neighbor ViewPager and optimize only observed work |
| Paging could duplicate the current in-memory music model | Explicitly excluded from V1 |
| Long press cannot safely mean both menu and drag | Long press opens actions; explicit Edit order mode owns drag |
| Fixed lyrics geometry can conflict with compact screens | Fix only standard portrait qualifier and keep compact/landscape bounded variants |
| Artwork theming can become visually noisy | One muted blended color, no animated gradient or global theme engine |
| Batch deletion can destroy the wrong data | Context-specific labels, one confirmation, existing Android consent coordinator |

## `$ship` Handoff

Implement in task-tree order. Before each slice, read this file plus
`PRODUCT_SPEC.md`, `UX.md`, `ARCHITECTURE.md`, and `STATUS.md`.

For every completed leaf:

1. edit the named existing surface rather than introducing a parallel system;
2. add the smallest regression test that proves the behavior;
3. record whether it was implemented, syntax-checked, JVM-tested, linted,
   built, or device-verified;
4. stop and reduce scope if the proposed fix requires a new architecture not
   allowed above.

The implementation is not complete when the UI merely looks correct. It is
complete when playback identity, Last.fm acceptance, Library workflows,
performance evidence, and the device acceptance criteria all pass.
