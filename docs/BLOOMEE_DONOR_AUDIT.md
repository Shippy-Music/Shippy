# Bloomee Donor Audit for Native Kotlin Shippy

Date: 2026-07-25  
Source audited: `X:\piko\bloomee`  
Target assumption: native Android, Kotlin, Auxio's existing Views/Fragments,
Media3.

## Verdict

Bloomee is valuable as a behavioral and protocol donor, not as a code donor.
Its strongest reusable knowledge is:

1. JioSaavn request/response handling and media URL formatting.
2. YouTube Music browse/search/related response traversal.
3. YouTube stream selection and expiring-link cache semantics.
4. Spotify-link-to-playable-track resolution.
5. LRCLIB retrieval, matching, synced-LRC parsing, and offline lyric persistence.
6. Queue operations, related-track autoplay, download-as-library-state, Last.fm scrobbling, and JSON/M3U interchange behavior.

Almost every implementation is Dart and coupled to Flutter packages. Native Shippy should preserve the contracts and user-visible behavior while rebuilding runtime, persistence, playback, background work, intents, and UI in Kotlin.

## Classification

- **Port**: small, deterministic algorithm or schema can be translated nearly line-for-line and covered by fixtures.
- **Adapt**: preserve protocol/behavior, but redesign boundaries, error handling, and types for Kotlin.
- **Reimplement**: platform implementation must be native Android.
- **Do not port**: obsolete, duplicated, unsafe, or demonstrably fragile implementation.

## Port / adapt / reimplement / do-not-port matrix

| Capability | Decision | Exact donor paths | Native Kotlin destination |
|---|---|---|---|
| JioSaavn endpoint catalog and response formats | Adapt | `bloomee/lib/repository/Saavn/saavn_api.dart`, `bloomee/lib/repository/Saavn/format.dart` | Retrofit/Ktor `JioSaavnClient`, typed DTOs, mapper tests |
| JioSaavn encrypted media URL decoding and quality URL rewrite | Port, behind tests | `bloomee/lib/repository/Saavn/format.dart`, `bloomee/lib/model/saavnModel.dart` | Resolver utility with captured fixtures; explicit 96/160/320 variants |
| YouTube Music guest browse/search/next/related behavior | Adapt | `bloomee/lib/repository/Youtube/ytm/yt_service_provider.dart`, `bloomee/lib/repository/Youtube/ytm/mixins/search.dart`, `browsing.dart`, `library.dart`, `utils.dart`, `bloomee/lib/repository/Youtube/ytm/ytmusic.dart` | One `YouTubeMusicClient`; typed navigation helpers; guest-session state |
| YouTube video metadata, playlist import, stream manifest selection | Adapt | `bloomee/lib/repository/Youtube/youtube_api.dart`, `bloomee/lib/utils/ytstream_source.dart` | One resolver abstraction; Kotlin-compatible maintained extractor or isolated service |
| Legacy/duplicate YouTube implementations | Do not port | `bloomee/lib/repository/Youtube/yt_music_api.dart`, `bloomee/lib/repository/Youtube/yt_music_home.dart`, `bloomee/lib/repository/Youtube/ytmusic/*`, overlapping parts of `youtube_api.dart` | Consolidate into the single client/resolver above |
| Hard-coded YouTube client identities and keys | Do not port | `bloomee/lib/repository/Youtube/yt_streams.dart`, `bloomee/lib/repository/Youtube/ytm/yt_service_provider.dart`, `bloomee/lib/repository/Youtube/yt_music_api.dart` | Runtime-configured resolver strategy; no credentials in source |
| Provider-independent source selection | Adapt | `bloomee/lib/model/source_engines.dart`, `bloomee/lib/blocs/search/fetch_search_results.dart` | Provider registry with availability, region, priority, and health |
| Metadata-to-playable-track matching | Port, then harden | `bloomee/lib/repository/MixedAPI/mixed_api.dart` | Deterministic scorer over normalized title, artists, duration, explicit/version markers |
| Spotify metadata import | Adapt | `bloomee/lib/repository/Spotify/spotify_api.dart`, `bloomee/lib/utils/external_list_importer.dart`, `bloomee/lib/utils/url_checker.dart` | Spotify metadata client plus resolver; Spotify is an import source, not an audio source |
| Embedded Spotify client secret | Do not port | `bloomee/lib/repository/Spotify/spotify_api.dart` | Backend token broker or user-authorized flow; never ship reusable secret |
| Track/provider metadata models | Adapt | `bloomee/lib/model/songModel.dart`, `saavnModel.dart`, `yt_music_model.dart`, `youtube_vid_model.dart`, `MediaPlaylistModel.dart`, `album_onl_model.dart`, `artist_onl_model.dart`, `playlist_onl_model.dart` | Typed domain models: stable canonical ID plus provider candidates and availability |
| Isar database schema and generated code | Reimplement | `bloomee/lib/services/db/GlobalDB.dart`, `GlobalDB.g.dart`, `bloomee_db_service.dart` | Room entities/DAOs; migrations; encrypted secret storage |
| Download behavior and offline lookup | Adapt + reimplement | `bloomee/lib/utils/downloader.dart`, `bloomee/lib/blocs/downloader/cubit/downloader_cubit.dart`, `bloomee/lib/blocs/offline/offline_cubit.dart`, download methods in `bloomee_db_service.dart` | Media3 download stack or WorkManager; Room download state; MediaStore/SAF |
| Current Flutter downloader completion/file handling | Do not port | `bloomee/lib/blocs/downloader/cubit/downloader_cubit.dart`, `bloomee/lib/utils/downloader.dart` | Native state machine with verified final URI, checksum/size, retry, cancellation |
| Audio tags and artwork embedding | Adapt | `bloomee/lib/utils/downloader.dart` | Native metadata writer only after successful finalization; failure must not invalidate download |
| Local-device music scanner | Reimplement; no donor exists | No scanner dependency or MediaStore query exists; only app downloads are indexed | MediaStore ingestion and persisted local-source candidates |
| Player and queue behavior | Adapt | `bloomee/lib/services/bloomeePlayer.dart`, `bloomee/lib/services/audio_service_initializer.dart`, `bloomee/lib/blocs/mediaPlayer/*` | Media3 `MediaLibraryService`, `ExoPlayer`, `MediaSession`, single queue authority |
| Flutter audio runtime | Do not port | `bloomee/lib/services/bloomeePlayer.dart`, `bloomee/lib/services/audio_service_initializer.dart`, `pubspec.yaml` (`just_audio`, `audio_service`, `rxdart`) | Native playback stack above |
| Lyrics API, matching, synced-LRC parser | Port/adapt | `bloomee/lib/repository/Lyrics/lrcnet_api.dart`, `lyrics.dart`, `bloomee/lib/model/lyrics_models.dart` | LRCLIB client, parser, matcher, repository |
| Lyrics cache and player-bound loading | Adapt | `bloomee/lib/blocs/lyrics/lyrics_cubit.dart`, lyric methods in `bloomee/lib/services/db/bloomee_db_service.dart`, `LyricsDB` in `GlobalDB.dart` | Room cache keyed by canonical track and provider fingerprint; Flow-based repository |
| Last.fm request signing and batch scrobble contract | Port/adapt | `bloomee/lib/repository/LastFM/lastfmapi.dart` | HTTPS Last.fm client, signing utility, max-50 batching |
| Last.fm playback threshold and retry intent | Adapt | `bloomee/lib/blocs/lastdotfm/lastdotfm_cubit.dart` | Playback-event scrobble coordinator with durable outbox |
| Current Last.fm secret/cache implementation | Do not port | `bloomee/lib/blocs/lastdotfm/lastdotfm_cubit.dart`, `bloomee/lib/repository/LastFM/lastfmapi.dart` | Encrypted credentials; transactional outbox; redacted logs |
| JSON playlist/song interchange | Port schema, reimplement I/O | `bloomee/lib/services/import_export_service.dart`, `bloomee/lib/services/db/GlobalDB.dart` | Versioned DTO schema plus SAF import/export |
| M3U codec | Port with fixtures | `bloomee/lib/services/m3u_processor.dart` | Pure Kotlin parser/writer; tolerate standard M3U and preserve Shippy extensions |
| URL classification and external imports | Port/adapt | `bloomee/lib/utils/url_checker.dart`, `external_list_importer.dart` | Android App Links/share receiver feeding an import coordinator |
| Flutter sharing and receive-intent plumbing | Reimplement | `bloomee/lib/main.dart`, `bloomee/lib/screens/widgets/more_bottom_sheet.dart`, `bloomee/lib/screens/screen/library_views/more_opts_sheet.dart`, `bloomee/android/app/src/main/AndroidManifest.xml` | `ACTION_SEND`, `ACTION_VIEW`, `Intent.EXTRA_TEXT`, `FileProvider`, SAF |
| Equalizer/audio effects | Reimplement; no donor exists | No equalizer/effect implementation or dependency found | Android audio effects bound to the active player session, if in Shippy scope |
| Stream/download quality settings | Adapt | `bloomee/lib/screens/screen/home_views/setting_views/player_setting.dart`, `download_setting.dart`, `bloomee/lib/routes_and_consts/global_str_consts.dart` | DataStore preferences mapped to resolver format constraints |
| Flutter UI, BLoCs, widgets, generated platform glue | Do not port | `bloomee/lib/screens/**`, `bloomee/lib/blocs/**`, `bloomee/android/app/src/main/kotlin/ls/bloomee/musicplayer/MainActivity.kt` | Auxio Views/Fragments, ViewModels, repositories, use cases |

## Provider and resolver donor map

### JioSaavn

`bloomee/lib/repository/Saavn/saavn_api.dart` contains a broad anonymous web-API client:

- song, album, artist, and playlist search;
- token-based collection fetch;
- album, artist, playlist details;
- related-song recommendations;
- top searches;
- endpoint selection through `__call` parameters.

`bloomee/lib/repository/Saavn/format.dart` converts loose web responses into a common map, expands artwork URLs, decodes `encrypted_media_url` with DES, and exposes a base media URL. `bloomee/lib/model/saavnModel.dart` rewrites that URL for the selected 96/160/320 kbps variant.

Keep the endpoint/format knowledge, but add typed DTOs, request timeouts, cancellation, fixture tests, null-safe parsing, and provider-health reporting. The donor relies on undocumented response shapes, a fixed language cookie, browser impersonation, string replacement for quality, and catch-all empty results. Treat every endpoint and field path as fragile.

### YouTube Music and YouTube

Bloomee has three overlapping families:

1. `bloomee/lib/repository/Youtube/ytm/**` is the clearest reusable guest YouTube Music client. It initializes headers/context, obtains a visitor ID, and implements search, browse, continuations, library-shaped calls, next/radio, related songs, playlist, album, and artist traversal.
2. `bloomee/lib/repository/Youtube/youtube_api.dart` wraps `youtube_explode_dart` for video/playlist metadata and stream manifests.
3. `bloomee/lib/repository/Youtube/yt_music_api.dart` and `bloomee/lib/repository/Youtube/ytmusic/**` overlap the newer client and are mainly useful as fixtures/reference for response navigation.

Playback uses `bloomee/lib/utils/ytstream_source.dart`: it selects low/high audio-only streams, supports byte-range requests, and attempts to cache serialized stream metadata by expiry. Downloading instead calls `YouTubeServices.refreshLink` through `bloomee/lib/utils/downloader.dart`.

Shippy should expose one boundary:

```text
Provider metadata -> StreamResolver.resolve(candidate, constraints)
                  -> ResolvedStream(uri, mime, bitrate, contentLength, expiresAt)
```

Keep stream URLs out of the durable track record. Cache them separately by provider item ID and format constraint, with expiry margin and one forced refresh.

### Spotify and cross-source resolution

`bloomee/lib/repository/Spotify/spotify_api.dart` fetches track, album, and playlist metadata. `bloomee/lib/utils/external_list_importer.dart` then converts each Spotify item into a search query and asks `bloomee/lib/repository/MixedAPI/mixed_api.dart` for a YouTube Music match.

This is an important product behavior: imported identity and playback provenance are different. Preserve the Spotify URL/ID and original metadata, but store the chosen playable provider as a replaceable candidate. Do not silently turn the imported track into a YouTube-only identity.

The current matcher considers only the first one or two results and uses fuzzy title/artist ratios with an 80 threshold. Port it only as a baseline. Add duration tolerance, normalized featured artists, punctuation/version handling, explicit/live/remix penalties, confidence, and user-visible mismatch recovery.

## Downloads and offline behavior

The behavioral flow spans:

- `bloomee/lib/blocs/downloader/cubit/downloader_cubit.dart`: permission, destination, task registration, completion callback;
- `bloomee/lib/utils/downloader.dart`: provider URL resolution, enqueue, duplicate check, filename generation, optional tagging;
- `DownloadDB` and download methods in `bloomee/lib/services/db/GlobalDB.dart` and `bloomee_db_service.dart`;
- `bloomee/lib/blocs/offline/offline_cubit.dart`: downloaded items exposed as an offline list;
- `bloomee/lib/services/bloomeePlayer.dart`: prefer a verified downloaded file over a network stream.

Useful semantics:

- a download is a state attached to a track;
- playback first checks for an existing offline artifact;
- stale DB rows are removed if the file is missing;
- download state also feeds a reserved downloads playlist;
- stream resolution happens immediately before enqueue;
- user-selectable destination and separate source-specific quality settings exist.

Native design:

```text
DownloadJob -> resolve -> enqueue -> transfer -> verify -> finalize -> index -> available
                                      \-> failed/retryable
TrackCandidate + DownloadArtifact(uri, state, format, bytes, verifiedAt)
```

Use a content URI or app-owned file reference, not a concatenated path. Persist task state before enqueue and reconcile active platform tasks after process death. A general local-library scanner is absent from Bloomee and must be built through MediaStore.

Current code that must not be copied:

- On Android 13+, `storagePermission()` requests photos and videos rather than audio.
- `FlutterDownloader.initialize` sets `ignoreSsl: true`.
- completion treats `_task.filePath` (the destination directory) as the downloaded file when attempting rename;
- non-YouTube files may be renamed from `.mp4` to `.m4a`, but the DB can retain the old filename;
- YouTube download refresh expects `vidMap["url"]`, while `YouTubeServices.refreshLink` returns `qurls`;
- active tasks live only in a static in-memory list;
- tagging is commented out;
- filenames sanitize only `?` and `/`;
- no checksum/content-length verification or robust cancellation/recovery exists.

## Player and queue behavior

`bloomee/lib/services/bloomeePlayer.dart` is the behavioral reference:

- one current queue and current index;
- load playlist at an index;
- add, batch add, play next, remove, reorder;
- next/previous and seek;
- shuffle and off/one/all loop intent;
- related-song autoplay when fewer than two items remain;
- provider-specific related lookup for JioSaavn and YouTube Music;
- offline-first source selection;
- notification/media controls through `audio_service`;
- recently played persistence;
- play/pause/position/buffer/speed exposure.

Preserve those behaviors behind one native queue authority. Media3 should own
the timeline and session. Views/Fragments observe ViewModel state; UI code must
not mutate queue lists directly.

Fragile behavior to redesign:

- `playMediaItem(..., doPlay:)` ignores `doPlay`; `playAudioSource` always starts playback.
- end detection polls position near duration and manually calls next instead of trusting player transitions.
- queue mutation often reuses and mutates the same list held by a `BehaviorSubject`.
- duplicate track IDs are globally suppressed, so a legitimate repeated queue item cannot exist.
- shuffle stores random indices that can become stale after queue edits.
- loop-all is partly manual while loop-one is delegated to the player.
- `currentPlayingIdx` and the actual player timeline can diverge.
- related-song fetching is throttled but has no cancellation or request identity.

## Lyrics

The lyrics stack is compact and worth carrying forward:

- `bloomee/lib/repository/Lyrics/lrcnet_api.dart`: LRCLIB exact lookup, ID lookup, search, fuzzy best-match, synced-result preference;
- `bloomee/lib/repository/Lyrics/lyrics.dart`: repository fallback policy;
- `bloomee/lib/model/lyrics_models.dart`: plain/synced representation and timestamp parsing;
- `bloomee/lib/blocs/lyrics/lyrics_cubit.dart`: current-track loading and optional auto-save;
- `LyricsDB` plus lyric methods in `bloomee/lib/services/db/GlobalDB.dart` and `bloomee_db_service.dart`: offline cache.

Port the parser and matching fixtures. Adapt networking and persistence. Harden URL query encoding, empty/short lyric handling, instrumental results, multiple LRC timestamp formats, fractional precision, offset handling, and stale-match replacement. `Lyrics.toString()` assumes at least 15 characters, and the parser accepts only one exact `[mm:ss.xx] text` shape.

## Last.fm

`bloomee/lib/repository/LastFM/lastfmapi.dart` contains the useful protocol pieces:

- request/session authentication;
- sorted-parameter MD5 API signatures;
- scrobble batches capped at 50;
- track fields including album, duration, timestamp, and chosen-by-user;
- recommended/mix/library station retrieval.

`bloomee/lib/blocs/lastdotfm/lastdotfm_cubit.dart` expresses intended behavior:

- track active listening time;
- scrobble after 30 seconds or 50 percent;
- exclude tracks longer than 15 minutes or shorter than 30 seconds;
- persist failed scrobbles for startup retry;
- resolve recommendation metadata to playable YouTube Music candidates.

Rebuild the coordinator as a durable outbox driven by native playback events. Use HTTPS for all Last.fm calls and auth URLs. Do not carry over plaintext credential storage, logs that print API keys/secrets/session keys, the busy-wait for initialization, or the string/List casts in retry-cache handling. The `initialize` method also shadows its static fields, so it does not assign the supplied arguments.

## Audio options and equalizer

Bloomee exposes:

- JioSaavn stream and download quality: 96/160/320 kbps;
- YouTube stream and download quality: high/low;
- autoplay;
- shuffle;
- loop off/one/all;
- volume and seek controls.

Sources: `bloomee/lib/screens/screen/home_views/setting_views/player_setting.dart`, `download_setting.dart`, `bloomee/lib/services/bloomeePlayer.dart`, and `bloomee/lib/routes_and_consts/global_str_consts.dart`.

No equalizer, bass boost, loudness normalization, crossfade, playback-speed UI, skip-silence control, or audio-effect engine exists. `loudnessDb` is parsed in `bloomee/lib/repository/Youtube/yt_streams.dart` but never applied. Equalizer/audio effects are therefore a clean native implementation, not a Bloomee port.

## Sharing, links, and imports

Reusable behavior:

- URL classification for Spotify track/album/playlist and YouTube video/playlist in `bloomee/lib/utils/url_checker.dart`;
- YouTube, YouTube Music, Spotify track/album/playlist import flows and progress state in `bloomee/lib/utils/external_list_importer.dart`;
- JSON song/playlist export, validation, collision renaming, and import in `bloomee/lib/services/import_export_service.dart`;
- M3U write/parse in `bloomee/lib/services/m3u_processor.dart`;
- outgoing file sharing in `bloomee/lib/screens/widgets/more_bottom_sheet.dart` and `bloomee/lib/screens/screen/library_views/more_opts_sheet.dart`;
- incoming Android filters in `bloomee/android/app/src/main/AndroidManifest.xml`.

Reimplement the Android edge. `bloomee/lib/main.dart` handles shared Spotify tracks and YouTube videos, but only tells users to use the library for shared playlists/albums. Its file-import path is nested under URL handling and can be unreachable for ordinary local files. It also indexes the first shared item without an empty-list guard. Native Shippy should normalize every incoming intent into `ImportRequest.TextUrl` or `ImportRequest.Document`, then pass it to one coordinator.

Version the interchange format. The donor exports transient streaming URLs; Shippy should prioritize canonical/source IDs and permanent URLs, then re-resolve playback candidates on import.

## Metadata model lessons

Bloomee’s common item is `MediaItemModel`, a subclass of Flutter `audio_service`'s `MediaItem`, with provider data in an untyped `extras` map. Persistence mirrors it in `MediaItemDB`. Online album, artist, and playlist models keep source IDs and source URLs.

Keep these concepts, not these classes:

```text
Track
  canonicalId
  title, artists, album, duration, artwork
  candidates: List<TrackCandidate>

TrackCandidate
  provider
  providerItemId
  permanentUrl
  providerMetadata
  availability

ResolvedStream
  candidateId
  uri, mimeType, bitrate, contentLength, expiresAt

DownloadArtifact
  trackId, candidateId, contentUri, state, format, verifiedAt
```

Do not use source-prefixed or sometimes-prefixed IDs as canonical identity. Bloomee inconsistently uses raw YouTube IDs and `youtube...` IDs across formatters. Do not store expiring stream URLs in the main track entity. Do not use `Map<String, dynamic>` as the provider boundary.

## Secrets and fragile code inventory

Do not copy secret values from these files:

- Spotify client ID and client secret are embedded in `bloomee/lib/repository/Spotify/spotify_api.dart`.
- YouTube client keys/identities are embedded in `bloomee/lib/repository/Youtube/yt_streams.dart`, `yt_music_api.dart`, and `ytm/yt_service_provider.dart`.
- Last.fm API key, secret, session, and username are stored through generic Isar settings in `bloomee/lib/blocs/lastdotfm/lastdotfm_cubit.dart` and `bloomee/lib/services/db/bloomee_db_service.dart`.
- Last.fm credentials/session are written to logs in `bloomee/lib/blocs/lastdotfm/lastdotfm_cubit.dart` and the Last.fm settings screen.
- Spotify access tokens are logged in `bloomee/lib/repository/Spotify/spotify_api.dart`.

Other high-risk donor assumptions:

- undocumented provider endpoints and response trees;
- fixed user agents, cookies, client versions, and signature timestamps;
- duplicate YouTube stacks with different ID and response conventions;
- broad exception swallowing into empty collections;
- HTTP Last.fm endpoints and globally enabled cleartext traffic;
- app database methods performing synchronous Isar work inside async functions;
- generated Isar code and Flutter plugin contracts that have no Kotlin value.

## Recommended extraction order

1. Define Kotlin domain contracts (`Track`, `TrackCandidate`, `ResolvedStream`, `DownloadArtifact`) and provider interfaces.
2. Port pure fixtures first: URL classifier, JioSaavn decode/quality rewrite, LRC parser, Last.fm signature, M3U codec, metadata matcher.
3. Adapt one provider end-to-end. JioSaavn is the smaller path; YouTube Music has broader coverage but more moving parts.
4. Build Media3 playback and queue independently of provider code.
5. Add durable download state and offline-first playback.
6. Add lyrics cache.
7. Add external import/share handling.
8. Add Last.fm through an encrypted credential store and durable scrobble outbox.
9. Add MediaStore local scanning; Bloomee provides no donor implementation.
10. Add equalizer/audio effects only after playback-session behavior is stable.

## Acceptance fixtures to extract before implementation

- JioSaavn song/search/album/artist/playlist/related responses and encrypted URL examples.
- YouTube Music search/browse/next/continuation responses for song, video, album, artist, and playlist.
- YouTube stream manifests with low/high, M4A/Opus, expiry, range, unavailable, and age-restricted cases.
- Spotify imports with remaster/live/explicit/featured-artist ambiguity.
- LRCLIB exact, fuzzy, synced, plain-only, instrumental, empty, and malformed LRC cases.
- Last.fm signature vectors, 1/50/51 item batches, offline retry, and duplicate-delivery cases.
- JSON and M3U round trips with missing optional fields, duplicate names, and stale provider URLs.
- queue edits while shuffled, repeated tracks, remove-current, loop transitions, related-fetch race, and process restore.

This audit is source-level only. It intentionally did not run Flutter, Gradle, Android, or device builds.
