# Bloomee Foundation Audit (Shippy Milestone 0)

> **Superseded foundation decision.** Shippy now uses Auxio/Kotlin. Current
> evidence lives in `X:\piko\shippy\docs\AUXIO_FOUNDATION_AUDIT.md` and
> `X:\piko\shippy\docs\BLOOMEE_DONOR_AUDIT.md`. This file is preserved only as
> the earlier Bloomee-fork investigation.

**Status:** Historical (static code audit; device build not run in this pass)  
**Date:** 2026-07-16  
**Audited tree:** `X:\Shippy\bloomee`  
**Upstream remote:** `https://github.com/mslaksh/Music-app.git`  
**Branch / HEAD:** `main` @ `25e73191fe2f63db57f23d9abd45fca4d136ebae` (*version bump*)  
**App version in tree:** `2.11.6+0`  
**License:** GPL-2.0  

---

## 1. Decision

### Choose: **Fork Bloomee (Flutter) as Shippy foundation**

| Criterion | Finding |
|---|---|
| Builds / CI | GitHub Actions builds Android + Windows with Flutter **3.24.0**, Java 17; reproducible release path exists |
| Playback stack | `just_audio` + `audio_service` already implements MediaSession, notification, queue, offline-preferring resolution |
| Providers | JioSaavn, YouTube Music, YouTube video, Spotify import, Last.fm, lyrics, charts are integrated |
| UI replaceability | Screens are thin-ish over BLoC; design is centralized in `theme_data/default.dart` + widgets |
| Unrecoverable blockers? | **None found.** Friction is real (globals, source-tabbed search, no MediaStore scan, fragile providers) but not rewrite-forcing |

### Rejected for Alpha: full Kotlin/Compose rewrite

A rewrite would reimplement playback, downloads, YT stream resolution, Saavn formatting, Isar library, MediaSession, and import paths with no product gain for the golden loop. Reconsider only if a later milestone proves Flutter/Android integration unworkable.

### License consequence

Shippy on this path is a **GPL-2.0 derivative**. Notices, source availability, and no silent relicense of Bloomee code.

---

## 2. What Bloomee Is

Cross-platform Flutter music player (Android primary; Windows/Linux/macOS present). Tagline matches Shippyâ€™s â€œad-free multi-source player,â€ but UX is still **source-aware** (user picks engine on Search) rather than â€œone library, source is metadata.â€

| Item | Value |
|---|---|
| Flutter package name | `Bloomee` |
| Android `applicationId` | `ls.bloomee.musicplayer` |
| Dart files under `lib/` | ~179 (~1.6 MB source) |
| Tests | Minimal (`widget_test.dart`, `charts_test.dart`) |
| State management | `flutter_bloc` (Cubits + a few Blocs) + `rxdart` streams on player |
| Routing | `go_router` + `StatefulShellRoute` shell |
| Local DB | Isar **3.1.8** (community host `pub.isar-community.dev`) |
| Audio | `just_audio` + `audio_service`; desktop via `just_audio_media_kit` |
| Downloads | `flutter_downloader` (Android isolate port) |
| YT streams | Forked `youtube_explode_dart` (git: HemantKArya) + isolate-backed `YouTubeAudioSource` |

**Note:** README points at `HemantKArya/BloomeeTunes`; this cloneâ€™s origin is `mslaksh/Music-app`. Treat as the candidate maintained branch we cloned; re-verify upstream lineage before public attribution.

---

## 3. Architecture Map

```text
UI screens (lib/screens)
        |
BLoC / Cubits (lib/blocs)  +  MiniPlayerBloc, settings, search, offline, ...
        |
Global services / repositories
  - BloomeeMusicPlayer (BaseAudioHandler)   lib/services/bloomeePlayer.dart
  - BloomeeDBService (Isar)                 lib/services/db/bloomee_db_service.dart
  - SaavnAPI / YouTubeServices / YTMusic    lib/repository/*
  - BloomeeDownloader / DownloaderCubit
        |
Platform: audio_service AudioService + MediaButtonReceiver
```

### Bootstrap (`lib/main.dart`)

1. `WidgetsFlutterBinding.ensureInitialized`
2. Desktop: `JustAudioMediaKit.ensureInitialized`
3. `initServices()` â†’ paths + `BloomeeDBService` + `YouTubeServices` singletons
4. Android high refresh rate, `MetadataGod.initialize`
5. Global `bloomeePlayerCubit = BloomeePlayerCubit()` (module-level late)
6. `DiscordService.initialize` (desktop presence)
7. `MultiBlocProvider` tree + `MaterialApp.router`

**Shippy concern:** player cubit is a process-wide global; domain boundary will need a controlled composition root instead of free imports of `bloomeePlayerCubit`.

### Navigation shell

| Tab index | Route | Screen | Role |
|---|---|---|---|
| 0 | `/Explore` | `ExploreScreen` | Home: charts, YT Music browse, recents entry |
| 1 | `/Library` | `LibraryScreen` | Local playlists + saved online collections |
| 2 | `/Search` | `SearchScreen` | Per-source search (JIS / YTM / YTV) |
| 3 | `/Offline` | `OfflineScreen` | **Downloaded** tracks only |
| â€” | `/MusicPlayer` | `AudioPlayerView` | Full Now Playing (root overlay) |
| â€” | `/AddToPlaylist` | â€¦ | Modal flow |

Bottom bar: `google_nav_bar` + always-on `MiniPlayerWidget`. Desktop: `NavigationRail` + responsive layout.

**Gap vs Shippy IA:** Shippy wants Home / Search / Library / Now Playing with Downloads as a **filter**, not a fourth product world. Offline tab is the biggest IA mismatch.

---

## 4. Domain Model (as implemented)

There is **no** canonical `Track` / availability enum. Identity and playback ride `audio_service`â€™s `MediaItem`.

### Core types

| Type | File | Role |
|---|---|---|
| `MediaItemModel` | `lib/model/songModel.dart` | Extends `MediaItem`; primary song model |
| `MediaItemDB` | `lib/services/db/GlobalDB.dart` | Isar persistence of a track |
| `MediaPlaylist` / `MediaPlaylistDB` | models + DB | User playlists |
| `DownloadDB` | GlobalDB | `fileName`, `filePath`, `mediaId`, `lastDownloaded` |
| `YtLinkCacheDB` | GlobalDB | Cached YT stream URLs + expiry |
| `SourceEngine` | `lib/model/source_engines.dart` | `eng_JIS`, `eng_YTM`, `eng_YTV` |
| Online models | album/artist/playlist_onl, yt maps | Provider-specific view models |

### Provenance (extras bag)

`MediaItemModel.extras` carries:

- `url` â€” stream URL or Saavn encrypted path
- `source` â€” e.g. `"saavn"`, `"youtube"`
- `perma_url`
- `language`

Mapping helpers: `MediaItem2MediaItemDB` / `MediaItemDB2MediaItem` in `songModel.dart`.

### Identity

- Saavn: platform song id  
- YouTube: often id with a `"youtube"` prefix stripped at play/download time  

**No cross-source canonical identity** â€” same recording from Saavn and YTM is two library items. This is the main domain work for Shippy Milestone 2.

---

## 5. Source Engines And Search

### Engines

```dart
enum SourceEngine { eng_JIS("JISaavn"), eng_YTM("YTMusic"), eng_YTV("YTVideo") }
```

- JioSaavn is country-gated (IN, NP, BT, LK) via settings + `availableSourceEngines()`.
- Engines can be toggled with bool settings keyed by engine value.

### Search path

`FetchSearchResultsCubit` (`lib/blocs/search/fetch_search_results.dart`):

- Separate last-search state per engine (pagination mainly for JIS)
- Result types: songs / playlists / albums / artists
- Dispatches to `SaavnAPI`, `YTMusic`, `YouTubeServices`

UI (`SearchScreen`) requires the user to **pick a source engine** â€” opposite of Shippyâ€™s one-tap unified search.

### Playback resolution (actual policy)

In `BloomeeMusicPlayer.getAudioSource`:

```text
1. If DownloadDB hit and file exists â†’ play local file (isOffline=true)
2. Else if source == youtube â†’ YouTubeAudioSource (youtube_explode, quality setting)
3. Else â†’ Saavn-style URL via getJsQualityURL (96/160/320 kbps rewrite)
```

This is **download-first**, then provider stream â€” good seed for Shippyâ€™s  
`Local â†’ Downloaded â†’ Cached â†’ Preferred provider` policy (local MediaStore still missing).

### Related / autoplay

Near end of track, throttled `check4RelatedSongs` pulls Saavn reco or YTMusic related and appends to queue when autoplay setting is on.

---

## 6. Playback And Android Integration

### Player

- Class: `BloomeeMusicPlayer extends BaseAudioHandler with SeekHandler, QueueHandler`
- File: `lib/services/bloomeePlayer.dart` (~490 lines)
- Init: `PlayerInitializer` â†’ `AudioService.init`  
  Channel: `com.BloomeePlayer.notification.status`  
  `androidStopForegroundOnPause: false`

### Queue model

Custom list on `queue` BehaviorSubject + single-item `ConcatenatingAudioSource` reloaded per track (`playAudioSource` clears/adds one child). Shuffle uses index permutation list, not `just_audio` shuffle.

### Android manifest (relevant)

- Permissions: Internet, wake lock, FGS media playback, storage/media, full-screen intent
- Activity: `com.ryanheise.audioservice.AudioServiceActivity` (not the empty `MainActivity.kt`)
- Service: `AudioService` + `MediaButtonReceiver`
- Share intents: text/plain + `.blm` playlist import
- `usesCleartextTraffic="true"`

### Widget / Auto / Wear

**None.** No home-screen widget code in this tree. Matches Shippy â€œwidget if feasible after player stable.â€

---

## 7. Downloads And Offline

| Piece | Location |
|---|---|
| Enqueue + tag helpers | `lib/utils/downloader.dart` â†’ `BloomeeDownloader` |
| Queue/UI state | `DownloaderCubit` (RepositoryProvider in main) |
| Offline list | `OfflineCubit` â† `BloomeeDBService.getDownloadedSongs()` |
| UI | `OfflineScreen` + download status views |

Flow:

1. Resolve stream URL (YT link cache refresh or Saavn quality URL)
2. `FlutterDownloader.enqueue`
3. On complete â†’ `putDownloadDB`; non-YT may rename `.mp4` â†’ `.m4a`
4. Playback prefers download file over stream

**Critical gap for Shippy Alpha â€œlocal tracksâ€:**  
There is **no MediaStore / device music library scanner**. â€œOfflineâ€ = **Shippy/Bloomee-managed downloads**, not user-owned files already on disk. `file_picker` is used for import/settings paths, not full local library browse.

Downloads live as a separate nav tab and playlist name constant `_DOWNLOADS`, not as badges on unified library rows (partial overlap via DB lookup at play time only).

---

## 8. Persistence (Isar)

`BloomeeDBService` is a large static facade over Isar collections:

| Collection | Purpose |
|---|---|
| `MediaItemDB` | Songs |
| `MediaPlaylistDB` + ranks | Playlists and order |
| `PlaylistsInfoDB` | Playlist metadata |
| `DownloadDB` | Offline files |
| `RecentlyPlayedDB` | History |
| `YtLinkCacheDB` | Stream URL cache |
| `AppSettingsStrDB` / `BoolDB` | Settings |
| `ChartsCacheDB` | Chart cache |
| `SavedCollectionsDB` | Saved online albums/artists/playlists |
| `NotificationDB` | In-app notifications |
| `LyricsDB` | Cached lyrics |

Also: DB backup/restore, search history, API token store, Last.fm-related settings.

**Adapter opportunity:** wrap `BloomeeDBService` behind Shippy repositories rather than rewriting Isar early.

---

## 9. Integrations Inventory

| Integration | Path | Alpha relevance |
|---|---|---|
| JioSaavn | `repository/Saavn/*` | Core stream/search (region-limited) |
| YouTube Music | `repository/Youtube/ytm/*` | Core search/browse/related |
| YouTube video | `youtube_api.dart` + explode | Streams / YTV search |
| Spotify | `repository/Spotify/spotify_api.dart` | Playlist/track **import** only (not playback source) |
| Last.fm | `repository/LastFM`, `lastdotfm` cubit | Scrobble / charts; post-Alpha polish |
| Lyrics | `repository/Lyrics/*` | Now Playing feature |
| Charts | Billboard, Melon, Last.fm, Spotify top50 plugins | Explore home |
| Discord RPC | `discord_service.dart` | Desktop only |
| Share / import | `import_export_service`, m3u, external URL intents | Useful |

### Compliance flags (engineering, not legal advice)

1. **Hardcoded Spotify `clientID` / `clientSecret`** in `spotify_api.dart` â€” must not ship in Shippy as-is; rotate/remove/move to user-supplied or env.  
2. Provider scraping (Saavn web API cookies, YT clients) is **brittle and ToS-sensitive** â€” Shippy must treat each source as an adapter with kill-switch, not a guaranteed catalogue.  
3. GPL-2.0 on the whole derivative.  
4. Broad storage permissions + cleartext traffic â€” tighten for a premium Android product.

---

## 10. UI / Theme Surface Area

- Theme: `lib/theme_data/default.dart` â€” dark purple-black (`#0A040C`), pink accent (`#FE385E`), cyan secondary; fonts Fjalla / Gilroy / CodePro / Unageo assets.
- Key widgets: `SongCardWidget`, `MiniPlayerWidget`, `GlobalFooter`, playlist/album/artist cards, up-next panel.
- Now Playing: `player_screen.dart` â€” blur/palette, lyrics tab, queue panel, timer entry.
- Settings: nested under Explore/home views (download path, quality, Last.fm, country, UI).

**Shippy Milestone 1 leverage:** replace theme tokens + shell (footer, mini-player, track row, Now Playing) while keeping `BloomeeMusicPlayer` and repositories intact.

Accessibility: no systematic TalkBack/semantics pass visible; touch targets and labels need work for Shippy Alpha checklist.

---

## 11. Toolchain Snapshot (from repo, not local machine)

| Tool | Evidence |
|---|---|
| Flutter | CI `3.24.0` stable; `.metadata` channel stable |
| Dart SDK | `>=3.0.6 <4.0.0` |
| Android minSdk | **21** |
| applicationId | `ls.bloomee.musicplayer` |
| ABI splits | armeabi-v7a, arm64-v8a, x86, x86_64 + universal |
| Kotlin MainActivity | Stub only; real entry is AudioServiceActivity |
| Isar | Community fork 3.1.8 (generator + flutter_libs) |
| youtube_explode_dart | Git dependency on HemantKArya fork |

**Not verified this pass:** `flutter pub get`, APK install, or physical-device smoke. Next engineering step before UI work: run that smoke and attach results here.

Suggested smoke commands (when ready):

```bash
cd bloomee
flutter --version
flutter pub get
flutter analyze
flutter build apk --debug
# install on device and: search â†’ play â†’ queue â†’ download â†’ airplane mode play
```

---

## 12. Entry Points Cheat Sheet

| Concern | Entry point |
|---|---|
| App start | `lib/main.dart` |
| Router | `lib/routes_and_consts/routes.dart` |
| Player service | `lib/services/bloomeePlayer.dart` |
| AudioService init | `lib/services/audio_service_initializer.dart` |
| Player cubit | `lib/blocs/mediaPlayer/bloomee_player_cubit.dart` |
| Search | `lib/blocs/search/fetch_search_results.dart` |
| Library | `lib/blocs/library/cubit/library_items_cubit.dart` |
| Downloads | `lib/blocs/downloader/cubit/downloader_cubit.dart`, `lib/utils/downloader.dart` |
| Offline list | `lib/blocs/offline/offline_cubit.dart` |
| DB | `lib/services/db/bloomee_db_service.dart`, `GlobalDB.dart` |
| Saavn | `lib/repository/Saavn/saavn_api.dart` |
| YT Music | `lib/repository/Youtube/ytm/ytmusic.dart` |
| YT stream source | `lib/utils/ytstream_source.dart` |
| Theme | `lib/theme_data/default.dart` |
| Android XML | `android/app/src/main/AndroidManifest.xml` |
| Android Gradle | `android/app/build.gradle` |

---

## 13. Strengths for Shippy

1. End-to-end golden loop already exists: search â†’ play â†’ queue â†’ download â†’ offline play.  
2. MediaSession / notification / FGS media playback already wired.  
3. Download-preferring `getAudioSource` is the right shape for offline-first.  
4. Rich library/playlist DB with watchers.  
5. Multi-provider adapters are isolated under `repository/` â€” good place for Shippy ports.  
6. UI is Flutter-only; shell redesign does not require native rewrite.  
7. CI proves a known Flutter version for builds.

---

## 14. Weaknesses / Shippy Friction

| Issue | Impact |
|---|---|
| No device local library scan | Blocks true â€œLocal â†’ Downloaded â†’ Streamâ€ until built |
| Search is source-tabbed | Contradicts unified library thesis |
| No canonical Track / availability model | Milestone 2 adapter work |
| Global player cubit | Harder testing and boundary enforcement |
| Single-source queue audio reload | Fine for V1; not gapless multi-source pipeline |
| Fragile YT/Saavn scraping | Expect breakage; need adapter health checks |
| Hardcoded Spotify secrets | Security debt |
| Offline as separate tab | IA rework for Downloads-as-filter |
| Almost no automated tests | Regressions likely during redesign |
| A11y not first-class | Milestone 3 cost |
| Isar community fork | Supply-chain/maintenance risk |
| GPL-2.0 | Distribution model fixed for derivative code |

---

## 15. Recommended Shippy Layering On This Fork

Do **not** rewrite repositories first. Insert a thin domain boundary:

```text
Shippy UI (new shell / tokens)
    â†’ presentation cubits (adapt existing where possible)
    â†’ Shippy domain (Track, availability, resolveSource, DownloadJob)
    â†’ adapters wrapping BloomeeDBService, BloomeeMusicPlayer, provider APIs
    â†’ existing Flutter plugins / Android
```

### Suggested package / branding strategy

- Keep working tree under `bloomee/` until first shell PR; or rename package gradually.  
- Temporary app id e.g. `â€¦Shippy.debug` for side-by-side install with stock Bloomee.  
- Rebrand strings/icons after shell works.

### Milestone order (unchanged, now evidence-backed)

0. ~~Audit~~ (this doc) + **device smoke** (still open)  
1. Design system + shell + mini-player + Now Playing on live player  
2. Canonical Track + unified search/library badges + Downloads filter  
3. MediaSession polish, widget, a11y, performance  
4. Sync prototype later  

---

## 16. Verification Status

| Claim | Status |
|---|---|
| Tree cloned at documented SHA | **Implemented** / verified |
| Architecture, modules, entry points mapped | **Implemented** (static audit) |
| Foundation decision (fork) written | **Implemented** |
| `flutter pub get` / analyze / APK | **Not verified** this pass |
| Device golden loop | **Not verified** this pass |
| Provider live health (Saavn/YT) | **Not verified** this pass |

---

## 17. Next Concrete Actions

1. On a dev machine with Flutter 3.24.x: `pub get`, `analyze`, debug APK, install.  
2. Manually exercise: Explore â†’ Search (each engine) â†’ play â†’ queue â†’ download â†’ airplane mode.  
3. Record failures in this file under a â€œRuntime notesâ€ section.  
4. Start Milestone 1: Shippy tokens + `GlobalFooter` / mini-player / Now Playing reskin without touching providers.  
5. File `FUTURE.md` for local MediaStore scan, unified search, Sync, Last.fm depth â€” without blocking Alpha shell.

---

## 18. Decision Sign-off

| Field | Value |
|---|---|
| Decision | **Fork Bloomee Flutter codebase for Shippy** |
| Alternative rejected | Greenfield Kotlin/Compose rewrite for Alpha |
| Cost accepted | GPL-2.0; Flutter constraints; provider fragility; later local-scan work |
| Exit criterion (Milestone 0) | Audit written; baseline SHA recorded; remaining gap = device smoke only |

When device smoke completes successfully, Milestone 0 is fully closed and Milestone 1 may start.
