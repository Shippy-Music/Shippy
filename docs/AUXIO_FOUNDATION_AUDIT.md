# Auxio Foundation Audit for Shippy

Status: source audit complete; no Gradle task or Android build was run.

## Snapshot

- Upstream: `https://github.com/OxygenCobalt/Auxio.git`
- Checked out branch: `dev` (upstream default and development branch)
- Commit: `b8c3cacd6430fc3ec009979eca2eda6424a4ac15`
- Stable branch at audit time: `origin/master` at `ab8651ca7`
- License: GPL-3.0-or-later (`LICENSE`, `README.md:111-122`)
- Repository history and `origin` are preserved. Recursive submodules are initialized at their pinned commits.

Auxio is a credible Shippy foundation for local-library indexing, playback, queueing, playlists, background audio, MediaSession, settings, and widgets. It is not already a multi-source player: there is no network provider, download state machine, canonical cross-source track, or source-resolution layer. Shippy should retain the proven local/player core, introduce a domain boundary above it, and replace presentation incrementally.

## Build and repository shape

The Gradle project contains:

| Module | Purpose | Evidence |
|---|---|---|
| `:app` | Android application, UI, playback coordination, service/platform integration | `settings.gradle:23-24`, `app/build.gradle:1-117` |
| `:musikr` | Local filesystem abstraction, metadata/tag parsing, indexing pipeline, library graph, playlists, cover/cache storage | `settings.gradle:24`, `musikr/src/main/java/org/oxycblt/musikr/Musikr.kt:47-156`, `musikr/src/main/java/org/oxycblt/musikr/Config.kt:29-60` |
| Vendored `media-*` modules | Patched AndroidX Media3/ExoPlayer and FFmpeg decoder | `settings.gradle:18-20`, `app/build.gradle:107-109`, `.gitmodules:1-3` |
| TagLib native source | Native metadata parser built through CMake/NDK | `.gitmodules:4-8`, `musikr/build.gradle:18-29`, `musikr/build.gradle:76-99` |

Current toolchain constraints are AGP 9.2.1, Kotlin 2.3.10, Java 21, min SDK 24, target/compile SDK 36, and NDK 28.2.13676358 (`build.gradle:3-17`, `app/build.gradle:17-48`). Gradle is deliberately limited to two workers and 2 GiB for the Gradle daemon because of the vendored media tree (`gradle.properties:6-9`, `gradle.properties:26-31`).

The upstream README explicitly says Windows builds are unsupported because the custom Media3/native build invokes Unix shell scripts (`README.md:71-81`). `assembleTaglib` confirms the hard dependency on `sh -c` (`musikr/build.gradle:76-91`). Official CI builds and tests on Ubuntu and checks out submodules (`.github/workflows/android.yml:11-29`, `.github/workflows/lint.yml:11-31`). For this Windows workspace, use WSL2/Linux or CI for builds; do not treat a Windows Gradle failure as an app-code failure.

## Architecture

### UI and navigation

This is a Views application, not Compose:

- `viewBinding` is enabled; Compose is not enabled (`app/build.gradle:62-65`).
- The app has 58 base XML layouts plus size/height variants under `app/src/main/res/layout*`.
- `MainActivity` inflates `ActivityMainBinding` (`app/src/main/java/org/oxycblt/auxio/MainActivity.kt:53-63`).
- Screens use Fragments, ViewModels, RecyclerView, ViewPager2, bottom sheets, and view binding (`app/src/main/java/org/oxycblt/auxio/MainFragment.kt:81`, `app/src/main/java/org/oxycblt/auxio/home/HomeFragment.kt:81`, `app/src/main/java/org/oxycblt/auxio/playback/PlaybackPanelFragment.kt:73`, `app/src/main/java/org/oxycblt/auxio/playback/queue/QueueFragment.kt:45`).
- Navigation is Safe Args with two nested XML graphs. `outer.xml` owns main/settings/about; `inner.xml` owns home/search/details/playlists/dialogs (`app/src/main/res/navigation/outer.xml:1-99`, `app/src/main/res/navigation/inner.xml:1-491`).

The existing shell is mature but tightly coupled to Auxio's local-only `Music` types and view layouts. Preserve navigation behavior and accessibility semantics while replacing the visual shell incrementally. A whole-app Compose conversion is not required for Alpha.

### Local library and indexing

`MusicRepository` is the application-facing local library authority. It exposes the current `Library`, indexing state, UID lookup, playlist mutations, and reindex requests (`app/src/main/java/org/oxycblt/auxio/music/MusicRepository.kt:63-237`). Its implementation:

1. Builds either a SAF or MediaStore filesystem from `MusicSettings`.
2. Combines cache, covers, stored playlists, and interpretation settings.
3. Runs `Musikr`.
4. Atomically publishes the new mutable library and change notifications.

Evidence: `app/src/main/java/org/oxycblt/auxio/music/MusicRepository.kt:382-488`.

`Musikr` is a coroutine pipeline with explicit explore, extract, and evaluate stages (`musikr/src/main/java/org/oxycblt/musikr/Musikr.kt:47-156`, `musikr/src/main/java/org/oxycblt/musikr/pipeline/ExploreStep.kt:39-68`, `musikr/src/main/java/org/oxycblt/musikr/pipeline/ExtractStep.kt:42-96`, `musikr/src/main/java/org/oxycblt/musikr/pipeline/EvaluateStep.kt:35-76`). The canonical local graph exposes `Song`, `Album`, `Artist`, `Genre`, and `Playlist`, all keyed by `Music.UID` (`musikr/src/main/java/org/oxycblt/musikr/Music.kt:45-403`, `musikr/src/main/java/org/oxycblt/musikr/Library.kt:28-161`).

Metadata caching uses Room (`musikr/src/main/java/org/oxycblt/musikr/cache/db/CacheDatabase.kt:35-128`). `IndexingHolder` can observe filesystem changes and request cached reindexing (`app/src/main/java/org/oxycblt/auxio/music/service/IndexingHolder.kt:133-196`).

For Shippy, keep `Musikr` as the local candidate producer. Do not stretch its `Song` model into the cross-source canonical model; adapt each local `Song` into a Shippy `TrackCandidate`.

### SAF folders and MediaStore

Location selection is unusually complete:

- Users can choose SAF file-picker mode or system MediaStore mode, with include/exclude filters and advanced options (`app/src/main/java/org/oxycblt/auxio/music/locations/LocationsDialog.kt:108-260`, `app/src/main/java/org/oxycblt/auxio/music/locations/LocationsDialog.kt:534-581`).
- Persistable tree permission handling is centralized in `Location` (`musikr/src/main/java/org/oxycblt/musikr/fs/Location.kt:31-67`).
- SAF supports source folders, excluded subtrees, hidden-file policy, and optional multithreaded traversal (`musikr/src/main/java/org/oxycblt/musikr/fs/saf/SAF.kt:51-180`).
- MediaStore has its own query and path interpretation (`musikr/src/main/java/org/oxycblt/musikr/fs/mediastore/MediaStore.kt:49-169`).
- The selected mode and queries are persisted in preferences (`app/src/main/java/org/oxycblt/auxio/music/MusicSettings.kt:42-65`, `app/src/main/java/org/oxycblt/auxio/music/MusicSettings.kt:116-209`).

Keep this subsystem. It already provides the local/offline ingestion path Shippy needs.

### Playlists

Internal playlists are Room-backed in `musikr`, with ordered song cross-references (`musikr/src/main/java/org/oxycblt/musikr/playlist/db/PlaylistDatabase.kt:38-168`, `musikr/src/main/java/org/oxycblt/musikr/playlist/db/RawPlaylist.kt:34-68`). The mutable library supports create, rename, add, reorder/rewrite, and delete (`musikr/src/main/java/org/oxycblt/musikr/Library.kt:107-161`, `app/src/main/java/org/oxycblt/auxio/music/MusicRepository.kt:320-359`).

External M3U import/export exists through `ExternalPlaylistManager`; export uses Android's `CreateDocument` contract (`musikr/src/main/java/org/oxycblt/musikr/playlist/ExternalPlaylistManager.kt:38-133`, `app/src/main/java/org/oxycblt/auxio/music/decision/ExportPlaylistDialog.kt:49-159`).

Keep playlist storage and editing for local songs. Extend playlist entries to reference Shippy canonical track IDs while retaining a resolvable local UID/candidate mapping. Migration must preserve order and missing/unavailable entries rather than dropping them.

### Playback, queue, and persistence

Playback has a useful separation:

- `PlaybackStateManager` is the app-wide command/state authority: current song, queue, index, shuffle, repeat, progression, seek, play-next, add, move, and remove (`app/src/main/java/org/oxycblt/auxio/playback/state/PlaybackStateManager.kt:47-337`).
- `ExoPlaybackStateHolder` adapts that state to a vendored Media3 `ExoPlayer`, FFmpeg renderer, MediaSource factory, ReplayGain processor, and Android audio attributes (`app/src/main/java/org/oxycblt/auxio/playback/service/ExoPlaybackStateHolder.kt:26-111`, `app/src/main/java/org/oxycblt/auxio/playback/service/ExoPlaybackStateHolder.kt:657-700`).
- Queue semantics include a stable heap plus shuffled mapping, avoiding destructive shuffle (`app/src/main/java/org/oxycblt/auxio/playback/state/PlaybackStateHolder.kt:222-274`).
- Playback state and queue are persisted in Room, including position, repeat mode, current UID, heap, and shuffled mapping (`app/src/main/java/org/oxycblt/auxio/playback/persist/PersistenceDatabase.kt:39-153`, `app/src/main/java/org/oxycblt/auxio/playback/persist/PersistenceRepository.kt:52-120`).
- The queue UI supports drag/reorder and removal (`app/src/main/java/org/oxycblt/auxio/playback/queue/QueueFragment.kt:45`, `app/src/main/java/org/oxycblt/auxio/playback/queue/QueueViewModel.kt:41-103`, `app/src/main/java/org/oxycblt/auxio/playback/queue/QueueDragCallback.kt:31-45`).

Keep the command/state pattern and ExoPlayer integration. Extend the queue from `Song` to a Shippy `PlaybackItem`/`QueueItem` that has a resolved playable candidate. Source resolution must occur before player media-source creation and be independently testable.

### Service, MediaSession, notification, and Android Auto

`AuxioService` is a Hilt-injected `MediaBrowserServiceCompat`. It composes music and playback service fragments, owns foreground notification transitions, and serves browser/search children (`app/src/main/java/org/oxycblt/auxio/AuxioService.kt:40-159`).

`PlaybackServiceFragment` attaches the Exo holder, MediaSession holder, widget, and system receiver as one lifecycle unit (`app/src/main/java/org/oxycblt/auxio/playback/service/PlaybackServiceFragment.kt:43-182`). `MediaSessionHolder` mirrors metadata, queue, playback state, repeat/shuffle actions, and notification state into `MediaSessionCompat` (`app/src/main/java/org/oxycblt/auxio/playback/service/MediaSessionHolder.kt:56-361`). `MusicBrowser` maps the library into browsable/searchable MediaSession IDs for Android Auto and other clients (`app/src/main/java/org/oxycblt/auxio/music/service/MusicBrowser.kt:41-191`). Manifest declarations include the foreground media playback service, media-browser action, media-button receiver, Android Auto metadata, and required permissions (`app/src/main/AndroidManifest.xml:5-119`).

Keep this integration for Alpha. The API is legacy compat rather than Media3 `MediaLibraryService`; migration is optional hardening, not a prerequisite. Shippy must ensure canonical IDs and availability changes invalidate browser/session items correctly.

### Widgets

There is one adaptive Now Playing widget provider with multiple size-class `RemoteViews`, current artwork, playback controls, repeat, and shuffle (`app/src/main/java/org/oxycblt/auxio/widgets/WidgetProvider.kt:43-471`). `WidgetComponent` subscribes directly to the single `PlaybackStateManager` and image/UI settings (`app/src/main/java/org/oxycblt/auxio/widgets/WidgetComponent.kt:49-186`). It is attached to the playback-service lifecycle, not a second player authority (`app/src/main/java/org/oxycblt/auxio/playback/service/PlaybackServiceFragment.kt:117-182`).

Keep it. Rebrand and visually simplify it after Shippy's playback item carries canonical metadata.

### Settings

Settings use AndroidX Preference XML backed by default `SharedPreferences` and listener interfaces (`app/src/main/java/org/oxycblt/auxio/settings/Settings.kt:34-100`). Categories cover UI/theme, personalization and queue behavior, music/indexing and covers, and audio/ReplayGain (`app/src/main/res/xml/preferences_root.xml:1-48`, `app/src/main/res/xml/preferences_ui.xml:1-31`, `app/src/main/res/xml/preferences_personalize.xml:1-55`, `app/src/main/res/xml/preferences_music.xml:1-47`, `app/src/main/res/xml/preferences_audio.xml:1-47`).

Keep the preference storage behind interfaces during Alpha. Replace only the screen presentation if the Shippy shell moves to Compose. New providers and downloads should have typed repositories/state, not ad-hoc preference flags.

### Tests

There are no app-module test files. The nine existing unit tests are all in `musikr` and focus on tags, dates, discs, names, separators, release types, and parsing edge cases (`musikr/src/test/java/org/oxycblt/musikr/`). CI runs `musikr:testDebug` and Spotless, while the Android workflow packages a debug APK (`.github/workflows/lint.yml:26-31`, `.github/workflows/android.yml:26-29`).

This is the main engineering risk. Before changing playback semantics, add JVM tests around source resolution, canonical identity, queue persistence/migration, and candidate fallback. Add focused instrumentation tests for service/session behavior later.

## Keep / extend / replace map

| Area | Decision | Shippy action |
|---|---|---|
| Git history, GPL notices, upstream remote | Keep | Fork transparently; preserve GPL-3.0-or-later obligations and attribution. |
| `musikr` local scan, metadata, cache, SAF/MediaStore | Keep | Expose local songs through a `LocalTrackCandidateSource` adapter. |
| ExoPlayer/FFmpeg/ReplayGain integration | Keep | Feed it resolved `PlaybackItem`s; avoid replacing a proven audio core. |
| `PlaybackStateManager` authority and queue operations | Extend | Generalize persisted identity from local `Music.UID` to canonical track/candidate identity. |
| Service, MediaSession, notification, Android Auto | Extend | Retain lifecycle; translate canonical Shippy metadata and unavailable states. |
| Internal playlist DB and M3U import/export | Extend | Store canonical references and preserve unresolved items during migration. |
| Now Playing widget | Extend | Rebrand and bind canonical metadata; retain one player authority. |
| Settings interfaces and persistence | Keep | Add typed provider/download configuration outside UI code. |
| Auxio XML/Fragment visual shell | Replace incrementally | Build the Shippy information architecture and visual system screen-by-screen; do not block Alpha on Compose migration. |
| Direct UI dependence on `musikr.Music` | Replace | UI consumes Shippy domain models and state, never provider/local implementation types. |
| Cross-source identity and availability | Add | Introduce `Track`, `TrackCandidate`, `LibraryEntry`, `Availability`, and deterministic matching. |
| Source resolution | Add | Keep exact Local items separate. Resolve provider items through `Crew cache -> Downloaded -> preferred provider -> fallback provider -> active Crew peer -> unavailable`. |
| Downloads | Add | Separate `DownloadJob` state machine and durable download catalogue; never conflate downloaded with local user-owned files. |
| Online search/providers | Add | Define provider interfaces and merge candidates before presentation. No provider exists in Auxio today. |
| Automated coverage | Add now | Unit-test the new domain boundary before presentation work. |

## Recommended first V1 slice

1. Add a pure Kotlin Shippy domain package with canonical IDs, `Track`, `TrackCandidate`, `Availability`, `PlaybackItem`, `QueueItem`, and source-resolution tests.
2. Adapt `musikr.Song` into a local candidate without changing the indexer.
3. Add a compatibility adapter that resolves a canonical `PlaybackItem` back into the current ExoPlayer media source.
4. Migrate queue persistence to versioned canonical identity with a local-UID fallback and explicit migration test.
5. Replace one vertical UI path only: Library/Search result -> Now Playing -> Queue.

Exit criterion: the current local golden path still works through the new domain boundary, and the tests can represent downloaded, streamable, fallback, and unavailable candidates even before a real provider is connected.

## Risks and decision

- **Windows build blocker:** upstream cannot build natively on Windows. Use WSL2/Linux CI with recursive submodules.
- **Large vendored media fork:** keep it pinned until a specific Shippy requirement justifies rebasing or replacing it.
- **Local-type coupling:** changing `Music.UID` everywhere at once would cause a broad rewrite. Use adapters and versioned persistence.
- **No app tests:** do not redesign playback and queue simultaneously without characterization tests.
- **GPL-3.0:** a distributed Shippy fork must satisfy GPL-3.0-or-later, not the GPL-2.0 assumptions attached to the earlier Bloomee candidate.

**Foundation decision: use Auxio as Shippy's native Android foundation.** Keep its local/indexing/player/platform subsystems, add the canonical multi-source domain as a seam above them, and replace the Auxio presentation incrementally. A clean Kotlin/Compose rewrite would discard the strongest verified parts of this repository without solving Shippy's actual missing layers.
