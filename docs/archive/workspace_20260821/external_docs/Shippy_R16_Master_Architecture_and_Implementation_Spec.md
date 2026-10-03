
# Shippy R16 Master Architecture and Implementation Specification

**Status:** Canonical R16 refactor specification  
**Release target:** R16 architecture reset, hardened beta candidate  
**Primary implementation input:** `Shippy-Source-R15.3-Stabilized-20260819.zip`  
**Control baseline:** GitHub commit `7fb431c9f3e5bad6805f7ff7b90127c67fd90b58` (`fix: keep shuffled playback identity synchronized`)  
**Original defect input:** `Shippy_Bug_UX_Feature_Report.md`, issues `SHIP-001` through `SHIP-028`  
**Product owner:** Rudra  
**Intended consumer:** Coding agent(s), reviewer(s), and the product owner  
**Authority rule:** When this document conflicts with pre-R16 Shippy architecture, stabilization, implementation, or UX documents, this document wins for R16 unless the product owner explicitly records a later decision.

> **R16 is not a conservative cleanup.** It is the release in which Shippy acquires one coherent music model, one dependable playback reality, one scalable library architecture, and one consistent product language. Existing code survives because it is good and fits the target, not because replacing it would be inconvenient.

---

## 0. How to Use This Document

This is both a **target architecture** and an **execution contract**. It is deliberately more specific than a normal product requirements document.

An implementation agent must:

1. Read Sections 1 through 12 before changing architecture.
2. Work through the migration phases in order.
3. Treat all “must,” “must not,” “non-negotiable,” and invariant statements as release requirements.
4. Preserve user data before deleting a legacy path.
5. Add the required tests before calling a replacement complete.
6. Record deviations as Architecture Decision Records rather than quietly improvising.
7. Keep the app runnable at each phase boundary.
8. Never claim device verification when only source inspection or JVM tests were performed.
9. Never trade identity correctness for a visually convincing demo.
10. Never perform a mechanical whole-repository rename before package identity, database migration, content-provider authorities, deep links, backups, and signing are deliberately handled.

This document is large because the system is large. It should still be executed in controlled, independently verifiable slices. “Big refactor” is permission to make necessary changes, not permission to create one unreviewable mega-commit.

---


## 0.1 High-Level Document Map

### Product and architecture

- **Part I:** release contract, evidence, current architecture, and confirmed failures
- **Part II:** canonical Recording/source/asset/metadata model
- **Part III:** identification, matching, deduplication, editing, and enrichment
- **Part IV:** providers, local files, cache, downloads, and source availability
- **Part V:** Library, playlists, search, persistence semantics, and read models
- **Part VI:** target Gradle/package architecture
- **Part VII:** R16 Room schema, queries, repositories, FTS, backup, and migration mechanics
- **Part VIII:** playback coordinator, queue, shuffle, Media3 engine, checkpoint, and system surfaces
- **Part IX:** Last.fm, history, recommendations, and lyrics
- **Part X:** Crew integration with the R16 identity/playback core
- **Parts XI–XIV:** UI state, UX, M3 Expressive polish, accessibility, and adaptive layouts
- **Parts XV–XVIII:** performance, reliability/security, migration, naming, and legal attribution

### Execution

- **Part XIX:** disposition of current files/functions
- **Part XX:** required ADRs
- **Part XXI:** phased R16 implementation program
- **Part XXII:** feature flags and rollback
- **Part XXIII:** agent execution contract
- **Parts XXIV–XXVII:** tests, physical-device matrix, beta gate, and risks
- **Parts XXVIII–XXIX:** original bug and Aug-19 regression traceability

### Implementation appendices

- **Appendices A–D:** matching, metadata, source selection, and playback state machines
- **Appendices E–G:** screen contracts, critical SQL, and background work
- **Appendices H–J:** current audit, dependency rules, and CI/release
- **Appendices K–L:** acceptance journeys and remaining owner decisions
- **Appendices M–P:** target source tree, file transformation, commit sequence, and hotspots
- **Appendices Q–R:** manual QA and official references
- **Appendices S–V:** glossary, standalone agent prompt, governance, and final release contract

Use editor heading navigation or search by issue/section ID. The document intentionally repeats critical invariants at execution boundaries so an implementation agent cannot miss them after reading only one phase.

---

# Part I — Release Contract

## 1. R16 Mission

R16 transforms the current R15.3 codebase into a dependable Android-native music application whose complexity is hidden behind a simple user experience:

- A song is a song, regardless of whether the playable bytes come from a local file, a Shippy download, a streaming provider, a verified cache entry, or an active Crew peer.
- The same recording does not become several library entries merely because several providers expose it.
- A Shippy-managed download never reappears as a mangled, metadata-poor local duplicate.
- Audio, title, artwork, lyrics, queue, notification, widget, Android Auto, Last.fm, and Crew always agree about the exact queue occurrence that is active.
- Playing and navigating feel immediate because expensive work is scoped, paged, cached, cancellable, and performed only when the user’s intent justifies it.
- The UI remains recognizably Shippy and Material-based, with selective Material 3 Expressive polish rather than an unrelated redesign.
- The core is stable enough that later UI and feature iteration can depend on it without repeatedly destabilizing playback or library state.

The release is successful when future development changes become ordinary product work instead of archaeology across multiple incompatible meanings of “song.”

---

## 2. R16 Quality Bar

R16 is governed by the following hierarchy:

1. **Correctness**
2. **Data safety**
3. **Identity consistency**
4. **Responsiveness and resource efficiency**
5. **Recoverability**
6. **Usability**
7. **Visual polish**
8. **Implementation convenience**

This hierarchy does not excuse ugly or slow software. It prevents visual polish from being used to conceal structural defects.

### 2.1 “Apple-like” in this document means

- The common path is obvious.
- The user is not exposed to provider and storage machinery unless they ask.
- Transitions are stable and predictable.
- Empty, loading, error, offline, and recovery states are designed rather than accidental.
- Actions acknowledge immediately.
- A failed background integration does not poison playback.
- The app remembers sensible choices.
- A single operation does not produce several contradictory representations.
- Detail is consistent across the product: sheets, back behavior, touch targets, state icons, typography, spacing, and motion.
- Complex subsystems are engineered underneath, then rendered as a calm interface.

It does **not** mean imitating Apple visuals, adding expensive blur everywhere, or choosing animation over responsiveness.

---

## 3. Non-Negotiable R16 Decisions

1. All first-party product architecture is Shippy architecture.
2. Legacy Auxio-derived infrastructure may remain only behind Shippy-owned interfaces.
3. Legal copyright, GPL notices, source attribution, and third-party names must remain where required.
4. The core domain does not expose `musikr.Song`, `MusicParent`, provider DTOs, Media3 `MediaItem`, Room entities, Android `Uri`, or UI models.
5. One canonical entity represents one specific audible recording.
6. A provider listing, local file, download, cache object, and Crew object are **sources or assets**, not recordings.
7. Provider IDs and URLs never define the canonical recording ID.
8. Queue occurrences have identities separate from recording identities.
9. Playlist occurrences have identities separate from recording identities.
10. Duplicate playlist entries are legal unless the product owner later explicitly forbids them.
11. Downloads never create new recordings.
12. Managed download files are recognized before the local scanner may create a local recording.
13. Linking and merging are reversible.
14. Automatic matching is conservative; ambiguity remains visible rather than silently corrupting identity.
15. User metadata overrides always win until the user removes them.
16. Raw source metadata is retained with provenance; canonical presentation is selected from it.
17. Library membership, catalogue knowledge, cache possession, and playable availability are separate concepts.
18. Search results do not automatically enter the Library.
19. A one-time stream may remain transient and be garbage-collected later.
20. Saving, liking, adding to a playlist, identifying, editing, or downloading makes the relevant identity durable.
21. One process-scoped playback coordinator owns local playback and queue state.
22. Media3 is the audio engine and event source, not a second product-state authority.
23. Shippy manages shuffle order by queue-entry identity; it does not depend on a competing hidden Media3 shuffle projection.
24. Every playback-facing surface consumes the same immutable playback snapshot.
25. Last.fm is optional discovery/history/scrobbling integration, not an audio provider.
26. Last.fm is invisible outside Settings when disconnected.
27. Connected Last.fm can scrobble local, downloaded, cached, provider, and Crew playback when the canonical recording has valid metadata.
28. Global Search, Library Search, and Playlist Search are separate scopes sharing infrastructure.
29. Large lists are queried and paged; they are not hydrated in full merely because Kotlin can allocate them.
30. Only the current playback item and a bounded nearby window receive expensive source preparation.
31. Network and database work never blocks the main thread.
32. A background thread is not permission to perform unbounded or unnecessary work.
33. R16 remains a Views/Fragments application. A Compose rewrite is out of scope.
34. R16 should move to the stable Material Components `1.14.0` release and selectively use Material 3 Expressive Views components that genuinely improve the product.
35. UI-specific controls belong to their screen, not a shared generic layout that leaks them into unrelated tabs.
36. Accessibility is part of the beta gate, not an indefinitely deferred afterthought.
37. No legacy path is deleted until its replacement passes migration, correctness, and regression tests.
38. No feature is called beta-ready solely because a class exists or a test suite is green.
39. Physical device verification is required for playback, storage, providers, themes, performance, process death, and system integrations.
40. The product owner may make visual refinements after R16, but the structural contracts in this document must remain stable.

---

## 4. Source Snapshots and Evidence

### 4.1 Current implementation snapshot

- File: `Shippy-Source-R15.3-Stabilized-20260819.zip`
- Size: 6,615,963 bytes
- SHA-256: `f097448c4566b983f6ec7d391f81271abcacd293cadc80c42669d95fe7fe5765`
- Created: 2026-08-18 20:40 UTC
- Local audit path: `/mnt/data/shippy_audit/current`

### 4.2 Control snapshot

- Repository: `Shippy-Music/Shippy`
- Commit: `7fb431c9f3e5bad6805f7ff7b90127c67fd90b58`
- Commit date: 2026-08-02
- Purpose: distinguish sound baseline decisions from regressions and feature work introduced by the later stabilization pass.

### 4.3 Original user-reported defect set

- File: `Shippy_Bug_UX_Feature_Report.md`
- SHA-256: `5be69e7339f9f0526dade383405de540d39d52cbbc003b70d32c8198982772fd`
- Coverage: playback identity, Continue Listening, Last.fm, Library/search/playlist operations, Home density, lyrics, drawers, themes, and performance.

### 4.4 Current repository scale

The current snapshot contains approximately:

- 414 production Kotlin files under `app/src/main/java`
- 109 JVM/instrumentation Kotlin tests
- 317 Android resource XML files
- 70 Crew production Kotlin files
- 1,200+ total source/repository files
- Major files exceeding 700–1,600 lines, including:
  - `CrewSessionEngine.kt`
  - `ExoPlaybackStateHolder.kt`
  - `CrewControlProtocol.kt`
  - `PlaybackStateManager.kt`
  - `MainFragment.kt`
  - `PlaybackViewModel.kt`
  - `CrewPlaybackBridge.kt`
  - `PlaybackPanelFragment.kt`

The repository is not too large to fix. It is too coupled to keep extending without establishing enforceable boundaries.

---

## 5. Current Architecture in Plain English

The current app has three overlapping worlds:

1. **Auxio/Musikr world**
   - scans local files;
   - owns local `Song`, album, artist, genre, and playlist structures;
   - supplies much of the original Views/Fragments UI;
   - contains the inherited Media3 player/service/system integration.

2. **Shippy world**
   - adds `Track`, `TrackCandidate`, `QueueItem`, provider playback, downloads, Shippy playlists, Last.fm, lyrics, history, and Crew;
   - has its own Room database and canonical-looking types;
   - attempts to adapt local songs into Shippy tracks.

3. **Provider world**
   - JioSaavn, YouTube, and YouTube Music independently return provider-scoped tracks and playable streams.

The intended flow is sound:

```text
local scanner / providers / downloads / Crew
                    ↓
             Shippy track model
                    ↓
              Shippy queue item
                    ↓
            playback source resolution
                    ↓
       inherited Media3 playback machinery
                    ↓
       UI / notification / widget / Last.fm
```

The actual implementation is transitional:

```text
legacy Song state ───────────────┐
legacy playlist/parent state ────┤
canonical QueueItem state ───────┤
resolved provider state ─────────┤──> several listeners and UI models
Media3 current-item state ───────┤
Crew canonical state ────────────┘
```

Some screens still consume the legacy model, some consume the newer model, and some consume both. This makes apparently simple operations cross several identity systems.

---

## 6. What the Existing Code Gets Right

R16 must retain these ideas, even if their implementations move:

### 6.1 Keep the proven Android audio foundation

The inherited Media3/ExoPlayer path, audio focus, service lifecycle, MediaSession, notification, widgets, Android Auto foundations, equalizer integration, local decoding, ReplayGain, crossfade groundwork, and format support are expensive capabilities. They should be wrapped and hardened, not casually re-created.

### 6.2 Keep exact local-file identity

`LocalTrackCandidateMapper` derives an exact local identity from the Musikr UID and does not silently merge a local file with a provider result. This protects the user from incorrect metadata-only substitution.

R16 changes the output model, not the principle:

```text
exact local asset
    + optional verified recording link
```

### 6.3 Keep queue-occurrence identity

`QueueItemId` correctly recognizes that the same recording can occur twice in a queue. R16 generalizes this into `QueueEntryId` and makes every playback surface key by it.

### 6.4 Keep source resolution late

Expiring provider URLs are playback details, not durable recording identities. R16 preserves this and moves it behind a stronger source-preparation boundary.

### 6.5 Keep bounded provider lookahead

`ProviderPlaybackLifecycle` prepares the current item plus a small forward window. The exact class changes, but the bounded-work principle remains.

### 6.6 Keep typed provider failures and capabilities

The existing `MusicProvider` contract distinguishes provider capabilities, health, search/browse/resolve operations, and typed failures. R16 changes provider output from “canonical Track” to “source observation,” because providers are not entitled to mint canonical Shippy identities.

### 6.7 Keep durable outbox behavior

The Last.fm outbox and download job persistence reflect the correct instinct: important side effects must survive network loss and process death.

### 6.8 Keep Crew’s deterministic core where tests prove it

The pure Crew reducer/protocol/session concepts are valuable. R16 replaces the bridge into local playback and the identity payload, not the entire collaboration model.

---

## 7. Why the Current Architecture Fails Under Growth

### 7.1 Provider normalization stops too early

The domain allows a `Track` to have several candidates, but providers create provider-scoped track identities:

```text
jiosaavn:<id>
youtube:<videoId>
youtube_music:<videoId>
```

Therefore the same recording often enters Shippy as several logical tracks. The system standardized provider output without actually reconciling identity.

### 7.2 Legacy and canonical playback models coexist

`PlaybackStateManager` exposes both:

- `currentSong`, `List<Song>`, `MusicParent`
- `currentQueueItem`, `List<QueueItem>`, `List<ResolvedQueueItem>`

It also emits both legacy and canonical callbacks. This is a compatibility bridge, not a stable final architecture.

### 7.3 Presentation code performs database reconciliation

Collection detail combines:

- selected collection IDs;
- all canonical track metadata;
- all downloads;

then reconstructs rows in memory. This turns a 50-item playlist into work proportional to the whole catalogue.

### 7.4 The player engine and product queue both project order

The current system must reconcile Shippy queue order, raw heap order, Media3 indices, and shuffle mapping. Numeric indices become stale between asynchronous operations.

### 7.5 Managed downloads are exposed to local indexing

When a Shippy-created download is later scanned as a generic local file, its filename or incomplete embedded tags can produce a second ugly song entry. The system knows the download in one database but fails to recognize its bytes at the local-ingestion boundary.

### 7.6 UI reuse leaks screen-specific behavior

The shared `fragment_home_list.xml` received an `Edit order` control intended for playlist/collection management. Because the same layout is used by Songs, Albums, Artists, and Genres, the control appears where it has no meaning.

### 7.7 Background correctness is tied to incidental callbacks

Last.fm listening time is updated on playback-state callbacks rather than an explicit audible-listening clock. Continuous playback may produce no callback that advances the policy.

### 7.8 Large queues are represented too richly

The current player UI maps entire resolved queues into display objects for the artwork pager. A queue operation can therefore trigger work for thousands of entries when only the current and adjacent entries are visible.

### 7.9 Documentation overstates implementation truth

The current `STATUS.md` says the stabilization implementation completed and automated gates passed, but the device screenshots expose layout regressions and the code still contains a scrobble-clock defect and a new-playback race candidate. Automated evidence remains valuable; it is not equivalent to product verification.

---

## 8. Confirmed Current Regressions to Fix Before Structural Migration

These fixes are Phase 1 because they make the current branch usable enough to serve as an implementation platform.

### 8.1 R16-REG-001 — 304 dp seek bar

**Path:** `app/src/main/res/layout-h520dp/fragment_playback_panel.xml`  
**Current line:** approximately 127–136  
**Defect:** `playback_seek_bar` has `android:layout_height="304dp"`.  
**Effect:** a giant blank Now Playing region.  
**Corrective action:** restore `wrap_content`; keep a bounded lyrics viewport on the lyrics container, not the seek bar.  
**Regression test:** inflate all playback-panel resource variants and assert the seek view is not fixed to the lyrics height.

### 8.2 R16-REG-002 — Edit-order control leaks to every Library tab

**Path:** `app/src/main/res/layout/fragment_home_list.xml`  
**Defect:** generic list layout contains `home_collection_edit_start`.  
**Effect:** Songs and other tabs show a playlist-specific action.  
**Corrective action:** move the control into a playlist/collection-specific toolbar or insert it only through an explicit screen-owned component.  
**Architectural rule:** a shared layout may expose neutral slots; it must not contain controls whose semantics exist on only one consuming screen.

### 8.3 R16-REG-003 — New-playback acknowledgement race

**Path:** `ExoPlaybackStateHolder.kt`  
**Current sequence:**

1. assign pending `NewPlayback` acknowledgement;
2. call `player.setMediaItems(...)`;
3. only afterward assign the expected media ID;
4. `onMediaItemTransition()` checks the expected ID.

Media3 may publish the transition caused by `setMediaItems` before the expected ID is assigned. The callback can be discarded and no later transition is guaranteed.

**Corrective action:** replace the pending fields with a predeclared playback transaction whose expected queue-entry ID and generation exist before any player mutation. The long-term R16 coordinator design in Part VI eliminates this race class entirely.

### 8.4 R16-REG-004 — Last.fm listening time has no continuous clock

**Paths:**
- `LastFmScrobbleTracker.kt`
- `ExoPlaybackStateHolder.kt`

The tracker advances `LastFmListenPolicy` only from `onProgressionChanged`. The player publishes progression changes for play/pause, position discontinuity, and similar state events, not every period of uninterrupted audible playback.

**Effect:** Now Playing can appear on Last.fm while the listen never reaches scrobble eligibility.

**Corrective action:** introduce the R16 listening-session actor described in Section 48. Do not add a crude UI timer to the existing tracker.

### 8.5 R16-REG-005 — Last.fm queue snapshot can become stale after reorder

The tracker updates its cached queue on canonical new/change events but does not maintain it on canonical reorder. Later numeric indices can be interpreted against an older order.

**Corrective action:** key Last.fm sessions by `QueueEntryId`, never by an index into a cached list.

### 8.6 R16-REG-006 — Fixed layout variants encode unrelated responsibilities

The tall playback layout duplicates substantial structure and makes it easy for a lyrics change to mutate the seek bar. R16 should extract reusable player sections and use constraint/dimension resources rather than copy-pasted whole layouts where possible.

---

# Part II — Canonical Music Model

## 9. Core Vocabulary

Use these terms consistently in code and documentation.

| Term | Meaning |
|---|---|
| **Recording** | One specific audible performance/mix/version that the listener understands as a song. |
| **Release** | An album, single, EP, compilation, soundtrack, or other published collection containing a recording occurrence. |
| **Artist** | A canonical person/group credit used in recording and release metadata. |
| **Source reference** | A provider/local/import identity that claims to represent a recording, such as a JioSaavn song ID or YouTube video ID. |
| **Media asset** | Actual playable bytes or a stable handle to them: local file, permanent download, complete cache object, Crew temporary object. |
| **Search hit** | A transient result from a source; not automatically a durable recording or Library item. |
| **Library relationship** | User ownership/organization state such as liked, explicitly saved, locally owned, downloaded, or user-edited. |
| **Playlist** | A named ordered user collection. |
| **Playlist entry** | One occurrence of a recording in a playlist. |
| **Queue entry** | One occurrence of a recording in the active playback queue. |
| **Playback resolution** | A short-lived decision selecting one source/asset and producing Media3-ready data. |
| **Cache entry** | Evictable playback bytes. Not ownership and not a Library item. |
| **Listening session** | One continuous listening attempt for one queue entry, used for history and scrobbling. |
| **Identity decision** | A reversible decision linking a source or asset to a canonical recording. |
| **Canonical metadata** | Shippy’s current presentation values after provenance and user overrides are applied. |
| **Raw metadata** | What an individual source/file actually reported. |

### 9.1 User-facing language

The UI may use “song” or “track.” Internal architecture uses `Recording` where ambiguity matters.

Do not expose “candidate,” “realm,” “locator,” or “canonicalization” in primary user flows.

---

## 10. Recording Identity Boundary

A recording is not:

- a URL;
- a provider ID;
- a file path;
- a cache key;
- a playlist row;
- an album track number;
- a Last.fm result;
- a queue index.

A recording is the intended audible identity.

These are separate recordings unless explicitly proven otherwise:

- studio recording;
- live performance;
- remix;
- acoustic version;
- instrumental;
- clean edit;
- explicit edit;
- radio edit;
- materially different remaster/edit;
- karaoke/cover version;
- sped-up/slowed/reverb variant.

The same recording appearing:

- on an album and a compilation;
- on YouTube Music and JioSaavn;
- as a local file and a Shippy download;
- in two playlists;
- twice in one queue;

remains one recording with several references, assets, or occurrences.

MusicBrainz uses a similar distinction: a Recording represents distinct audio and can appear as tracks on several releases. ISRC identifies sound recordings rather than musical works, with distinct remixes or versions receiving distinct codes. R16 adopts that useful boundary while retaining conservative local rules.

---

## 11. Canonical IDs

### 11.1 ID types

Create strong inline types:

```kotlin
@JvmInline value class RecordingId(val value: String)
@JvmInline value class ArtistId(val value: String)
@JvmInline value class ReleaseId(val value: String)
@JvmInline value class SourceReferenceId(val value: String)
@JvmInline value class MediaAssetId(val value: String)
@JvmInline value class PlaylistId(val value: String)
@JvmInline value class PlaylistEntryId(val value: String)
@JvmInline value class QueueEntryId(val value: String)
@JvmInline value class ListeningSessionId(val value: String)
@JvmInline value class IdentityDecisionId(val value: String)
```

### 11.2 Generation rules

- New durable Shippy entities receive application-generated UUIDs.
- IDs do not embed provider, title, artist, path, or URL.
- Migration may use deterministic UUIDv5 values derived from old IDs so retries are idempotent.
- Provider/local exact identities live in source/asset unique keys.
- Queue and playlist occurrence IDs are independently generated.
- IDs are immutable after creation.
- A merge creates a redirect; it does not rewrite history into an untraceable new identity.

### 11.3 Media3 identity

`MediaItem.mediaId` must equal `QueueEntryId`, not `RecordingId` and not provider ID.

This single decision guarantees that duplicate queue occurrences remain distinguishable and that all Media3 callbacks can be related to the exact product occurrence.

---

## 12. Target Entity Graph

```text
Artist ───────┐
              ├── Recording ──< SourceReference
Release ─< ReleaseTrack ┘         │
                                  ├── provider listing
                                  ├── Last.fm identity hint
                                  └── imported link

Recording ──< MediaAsset
              ├── local file
              ├── permanent Shippy download
              ├── complete verified cache
              └── Crew temporary media

Recording ──1 LibraryRelationship
Recording ──< PlaylistEntry >── Playlist
Recording ──< QueueEntry
QueueEntry ──< ListeningSession
```

### 12.1 Recording

```kotlin
data class Recording(
    val id: RecordingId,
    val title: String,
    val artistCredit: ArtistCredit,
    val durationMs: Long?,
    val version: RecordingVersion,
    val explicitness: Explicitness,
    val preferredReleaseId: ReleaseId?,
    val preferredArtwork: ArtworkReference?,
    val retention: RecordingRetention,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

This is the fast canonical projection. Raw observations and provenance remain elsewhere.

### 12.2 RecordingVersion

```kotlin
data class RecordingVersion(
    val kind: VersionKind,
    val label: String?,
    val traits: Set<VersionTrait>,
)

enum class VersionKind {
    ORIGINAL,
    LIVE,
    REMIX,
    ACOUSTIC,
    INSTRUMENTAL,
    RADIO_EDIT,
    REMASTER,
    COVER,
    KARAOKE,
    SPED_UP,
    SLOWED,
    OTHER,
    UNKNOWN,
}
```

Version mismatch is a veto in automatic matching unless a trusted external identifier proves equivalence.

### 12.3 SourceReference

```kotlin
data class SourceReference(
    val id: SourceReferenceId,
    val recordingId: RecordingId?,
    val source: SourceKey,
    val kind: SourceKind,
    val originalUrl: String?,
    val availability: AvailabilitySnapshot,
    val rawMetadataId: String,
    val identityStatus: IdentityStatus,
)
```

`recordingId` may be temporarily unknown during search/identification.

`SourceKey` is exact and unique:

```kotlin
data class SourceKey(
    val providerId: ProviderId,
    val itemType: SourceItemType,
    val sourceItemId: String,
)
```

### 12.4 MediaAsset

```kotlin
data class MediaAsset(
    val id: MediaAssetId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val kind: MediaAssetKind,
    val location: AssetLocation,
    val state: AssetState,
    val technical: AudioTechnicalMetadata,
    val checksum: ContentChecksum?,
    val fingerprint: FingerprintReference?,
    val lastVerifiedAt: Instant?,
)
```

### 12.5 LibraryRelationship

```kotlin
data class LibraryRelationship(
    val recordingId: RecordingId,
    val liked: Boolean,
    val explicitlySaved: Boolean,
    val firstAddedAt: Instant?,
    val userEdited: Boolean,
    val manuallyIdentified: Boolean,
)
```

Local/download/system collections derive from assets; they are not booleans duplicated here.

### 12.6 PlaylistEntry

```kotlin
data class PlaylistEntry(
    val id: PlaylistEntryId,
    val playlistId: PlaylistId,
    val recordingId: RecordingId,
    val orderKey: Long,
    val addedAt: Instant,
)
```

The entry identity allows duplicates and stable drag operations.

### 12.7 QueueEntry

```kotlin
data class QueueEntry(
    val id: QueueEntryId,
    val recordingId: RecordingId,
    val origin: PlaybackOrigin?,
    val playlistEntryId: PlaylistEntryId?,
    val contributor: ContributorId?,
    val addedAt: Instant,
)
```

The queue stores recording intent. A separate resolution map stores ephemeral playable sources.

---

## 13. Recording Retention

Catalogue knowledge and Library ownership are different.

```kotlin
sealed interface RecordingRetention {
    data class Transient(val expiresAt: Instant) : RecordingRetention
    data object RecentHistory : RecordingRetention
    data object DurableReference : RecordingRetention
}
```

### 13.1 Transient

A provider result that was shown but never played or saved need not be persisted.

A provider result that was played may be persisted temporarily to support:

- current queue;
- recent listening;
- cache association;
- Last.fm;
- process restoration.

It does not appear in the Library unless a Library relationship or owned asset exists.

### 13.2 Durable triggers

A recording becomes durable when any of these is true:

- liked;
- explicitly saved;
- belongs to a user playlist;
- has a permanent download;
- has a local user file linked to it;
- has a user metadata override;
- has a manual identity decision;
- is referenced by a retained queue/checkpoint;
- is retained by a history policy.

### 13.3 Garbage collection

A periodic bounded catalogue garbage collector may delete a transient recording only when:

- no Library relationship exists;
- no playlist entry exists;
- no permanent/local asset exists;
- no user override or manual identity decision exists;
- no active/recent checkpoint references it;
- its history retention has expired;
- no cache retention policy requires it.

Deletion cascades transient source observations but never deletes user files.

---

## 14. Raw Metadata, Canonical Metadata, and User Overrides

R16 must preserve three layers.

### 14.1 Raw observations

Each provider/file/metadata service contributes a snapshot:

```kotlin
data class MetadataObservation(
    val id: MetadataObservationId,
    val subject: MetadataSubject,
    val sourceType: MetadataSourceType,
    val sourceReferenceId: SourceReferenceId?,
    val title: String?,
    val artistCredit: ArtistCreditObservation?,
    val releaseTitle: String?,
    val releaseArtist: String?,
    val durationMs: Long?,
    val artwork: List<ArtworkObservation>,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genres: Set<String>,
    val externalIdentifiers: Set<ExternalIdentifier>,
    val versionHints: Set<String>,
    val capturedAt: Instant,
)
```

The snapshot is not automatically the canonical display.

### 14.2 Canonical projection

A deterministic metadata resolver selects each field independently.

Default precedence:

1. explicit user override;
2. user-confirmed identity source;
3. trusted strong identifier metadata;
4. high-quality music provider metadata;
5. embedded file tags;
6. generic video/provider metadata;
7. filename-derived fallback.

Field precedence may differ. A local embedded duration may be more trustworthy than a provider duration; verified provider artwork may be better than a low-resolution file thumbnail.

### 14.3 User overrides

User edits are stored separately and survive:

- provider refresh;
- file rescan;
- source failure;
- cross-provider enrichment;
- merge.

The UI must provide:

- Edit Shippy metadata
- Remove individual override / restore automatic value
- Identify track
- Unlink identification
- Optional “write selected metadata to file” for writable local assets

Do not silently write provider metadata into arbitrary local files.

### 14.4 Provenance

For each canonical field, persist enough provenance to explain:

- selected value;
- source of value;
- confidence;
- selection time;
- whether it is a user override.

This supports debugging and prevents mysterious metadata oscillation.

---

## 15. External Identifiers

Support:

- ISRC
- MusicBrainz Recording ID
- MusicBrainz Release ID
- MusicBrainz Artist ID
- AcoustID
- provider source keys
- Last.fm URL/name identity hints
- local fingerprint IDs

Rules:

- Provider IDs belong to source references.
- ISRC and MusicBrainz Recording IDs can belong to recordings.
- An identifier conflict creates an identity review condition; it must not be quietly overwritten.
- An ISRC is strong but not infallible. Providers sometimes supply bad metadata.
- MusicBrainz API access must respect its one-request-per-second application rate limit and meaningful User-Agent requirement.
- AcoustID’s public service has usage/rate/license constraints; the implementation must remain replaceable and configurable.


# Part III — Identification, Matching, and Deduplication

## 16. Identity Pipeline

R16 does not use one magical “dedupe” function. It uses a staged identity pipeline with explicit evidence.

```text
incoming provider result / local file / download
                         ↓
                  exact-key lookup
                         ↓
              strong-identifier lookup
                         ↓
                fingerprint lookup
                         ↓
           conservative metadata scoring
                         ↓
       link / ask user / keep separate / reject
```

Every stage returns an evidence record, not merely `true` or `false`.

### 16.1 Identity outcomes

```kotlin
sealed interface IdentityOutcome {
    data class Existing(val recordingId: RecordingId, val evidence: MatchEvidence) :
        IdentityOutcome
    data class New(val draft: RecordingDraft) : IdentityOutcome
    data class Ambiguous(val candidates: List<MatchCandidate>) : IdentityOutcome
    data class Rejected(val reason: IdentityRejection) : IdentityOutcome
}
```

### 16.2 Evidence classes

| Evidence | Strength | Default action |
|---|---:|---|
| Existing exact `SourceKey` | Exact | Reuse recording |
| Existing managed asset ID / exact URI | Exact | Reuse recording |
| Same verified checksum | Exact bytes | Reuse asset/recording |
| Same high-confidence audio fingerprint | Very strong | Auto-link unless version conflict |
| Same trusted MusicBrainz Recording ID | Very strong | Auto-link unless contradictory evidence |
| Same valid ISRC | Strong | Auto-link with sanity checks |
| Same YouTube video ID across YouTube/YT Music aliases | Strong exact source | Link same source family |
| Exact normalized artist/title/duration/release/version | Medium | Auto-link only under strict policy |
| Similar metadata | Weak/medium | Ask user |
| Filename similarity only | Weak | Never auto-link |
| Version contradiction | Negative veto | Keep separate |
| Large duration mismatch | Negative veto | Keep separate |
| Explicit user “different recording” decision | Permanent negative | Do not re-suggest unless reset |

### 16.3 Identity is idempotent

Running the same ingestion or enrichment twice must not:

- create another recording;
- create another source reference;
- duplicate a media asset;
- duplicate playlist entries;
- erase a user decision.

Unique database keys and deterministic migration IDs enforce this.

---

## 17. Metadata Normalization

Normalize text for comparison without destroying presentation:

- Unicode normalization;
- whitespace collapse;
- punctuation normalization;
- case-folded comparison keys;
- common featured-artist syntax extraction;
- bracketed version label extraction;
- canonical “and”/ampersand handling for comparison only;
- provider-specific suffix removal only when proven boilerplate;
- duration tolerance based on recording length;
- transliteration only as an additional search key, never as canonical replacement.

Store both:

```text
display value: "See You Again (feat. Charlie Puth)"
comparison key: "see you again"
version traits: []
artist credit: [Wiz Khalifa, Charlie Puth]
```

Do not strip meaningful qualifiers such as:

- Live
- Remix
- Acoustic
- Instrumental
- Remastered
- Radio Edit
- Clean
- Sped Up
- Slowed + Reverb

### 17.1 Duration scoring

Default guidance:

- exact external identifier: duration is a sanity check;
- fingerprint match: duration may vary by leading/trailing silence;
- metadata-only match:
  - under 5 minutes: prefer difference ≤ 3 seconds;
  - 5–15 minutes: prefer difference ≤ 5 seconds;
  - longer content: use a proportional tolerance;
- difference > 8% is normally a veto;
- missing duration reduces confidence but does not reject.

These are configurable policy values with tests, not scattered constants.

### 17.2 Artist credit

Do not flatten all artist text into one uncontrolled string. Preserve:

- ordered artists;
- display names;
- join phrases;
- featured credits;
- aliases;
- source-specific raw credit.

Matching can compare normalized sets and primary artist while presentation preserves the credited form.

---

## 18. Manual “Identify Track” Feature

This is a first-class R16 capability.

### 18.1 Entry points

Expose **Identify track** from:

- local track action sheet;
- unknown/low-confidence recording details;
- metadata editor;
- duplicate cleanup workflow;
- failed Last.fm eligibility explanation when metadata is unusable.

### 18.2 Search evidence

An identification request may use:

- current tags;
- filename tokens;
- duration;
- file technical metadata;
- audio fingerprint;
- existing provider source references;
- current artwork;
- MusicBrainz;
- AcoustID;
- Last.fm track search/info;
- YouTube Music;
- JioSaavn;
- YouTube;
- existing Shippy catalogue.

### 18.3 UX flow

```text
User opens Identify track
        ↓
Shippy displays current raw information
        ↓
Immediate local catalogue matches
        ↓
Progressively arriving online candidates
        ↓
Candidate cards show:
title, artists, release, duration, artwork, version, evidence
        ↓
User selects correct recording
        ↓
Preview of metadata changes and source links
        ↓
Confirm
        ↓
Atomic identity decision
        ↓
Every screen updates from the same RecordingId
```

The user must be able to choose:

- This is the same recording
- This is a different version
- Create a new recording
- Never suggest this match again

### 18.4 What confirmation changes

A successful manual identification transaction:

1. links the local/media asset to the target `RecordingId`;
2. preserves the file’s raw metadata observation;
3. records a user-confirmed identity decision;
4. applies canonical metadata unless overridden;
5. moves Library relationships and playlist references through a redirect if necessary;
6. attaches known provider sources;
7. invalidates affected read models/search indexes;
8. updates Now Playing by recording ID without replacing the queue occurrence;
9. makes future Last.fm sessions use the corrected canonical metadata;
10. preserves an undo record.

It does **not**:

- replace or delete the file;
- rewrite tags without explicit permission;
- remove alternate provider sources;
- retroactively rewrite Last.fm history outside Shippy.

### 18.5 Undo

Undo must restore:

- previous recording link;
- previous canonical projection;
- previous Library visibility;
- previous source grouping.

If a merge moved references, use `entity_redirect` and `merge_audit` data rather than reconstructing from memory.

---

## 19. Bulk Library Cleanup

Bulk cleanup comes after manual identification is reliable.

### 19.1 Classification

Each unresolved/low-quality item is classified as:

- **Certain**: exact/strong match, safe for automatic linking;
- **Probable**: user review required;
- **Unresolved**: insufficient evidence;
- **Conflict**: contradictory strong identifiers or version evidence;
- **Duplicate asset**: same bytes already attached;
- **Possible alternate encode**: fingerprint match but technical differences;
- **Managed download orphan**: Shippy asset record/file relationship needs repair.

### 19.2 Execution rules

- Compute fingerprints only when the device is idle/charging or the user explicitly starts cleanup.
- Process bounded batches.
- Persist progress.
- Cancel cleanly.
- Do not hold a full library in memory.
- Show estimated work without promising exact time.
- Never auto-delete user files.
- Provide a review queue for probable/conflict items.
- Record negative decisions to avoid repeated nagging.

---

## 20. Merge, Link, and Redirect Semantics

### 20.1 Link

A source or asset is attached to an existing recording. No recording disappears.

### 20.2 Merge

Two canonical recording rows are concluded to represent the same recording.

Merge transaction:

1. choose the survivor deterministically;
2. move source references;
3. move assets;
4. combine external identifiers;
5. combine Library state without losing positive state;
6. move playlist entries without collapsing occurrence IDs;
7. move history;
8. reconcile user overrides with explicit conflict handling;
9. create `entity_redirect(oldId → survivorId)`;
10. record complete audit data.

### 20.3 Unmerge

Unmerge is supported when audit data is complete. It recreates the separated recording and restores moved relationships where possible.

### 20.4 Redirect resolution

Every repository entry point that accepts `RecordingId` resolves redirects. Redirect chains are compacted transactionally and must not cycle.

---

## 21. Event-Driven Enrichment

Cross-provider matching is deliberately not continuous.

### 21.1 Search result shown

Work performed:

- parse/normalize the source result;
- check exact `SourceKey`;
- check already-known strong IDs;
- optionally group already-known identical sources;
- render immediately.

Do **not** search every other provider.

### 21.2 Play requested

Work performed:

- ensure an ephemeral/durable recording exists;
- resolve the selected known source;
- start playback;
- optionally schedule low-priority metadata enrichment only after the player has committed.

Do **not** block playback on cross-provider discovery.

### 21.3 Like, save, add to playlist, or download

Schedule unique low-priority enrichment for that `RecordingId` because the user has expressed durable intent.

### 21.4 Manual Identify

Run interactive, high-priority, multi-source search with progressive results.

### 21.5 Known source fails

1. use an already-known valid source/asset;
2. refresh the same provider source if appropriate;
3. run a bounded alternate-source search;
4. report unavailable honestly if no confident source exists.

### 21.6 Idle maintenance

Process only durable unresolved recordings:

- small batches;
- unmetered network by default;
- charging/idle for fingerprints;
- per-provider rate limits;
- exponential backoff;
- TTL-controlled rechecks;
- no whole-catalogue scan on app launch.

### 21.7 Work identity

Use unique WorkManager names:

```text
enrich-recording:<RecordingId>
fingerprint-asset:<MediaAssetId>
verify-asset:<MediaAssetId>
repair-managed-downloads
catalogue-gc
lastfm-outbox-flush
```

A newer explicit request may upgrade or replace lower-priority work.

---

# Part IV — Sources, Assets, Cache, and Downloads

## 22. Source Types

```kotlin
enum class SourceKind {
    LOCAL_FILE,
    SHIPPY_DOWNLOAD,
    JIOSAAVN,
    YOUTUBE_MUSIC,
    YOUTUBE,
    LASTFM_HINT,
    MUSICBRAINZ,
    IMPORTED_LINK,
    CREW_PEER,
}
```

`LASTFM_HINT` and `MUSICBRAINZ` are metadata/identity sources, not playable sources.

### 22.1 Provider contract

Providers return source observations:

```kotlin
interface MusicSourceProvider {
    val descriptor: ProviderDescriptor

    suspend fun search(
        query: SearchQuery,
        page: PageToken?,
    ): ProviderResult<SourceSearchPage>

    suspend fun browse(
        key: SourceEntityKey,
        page: PageToken?,
    ): ProviderResult<SourceBrowsePage>

    suspend fun resolve(
        source: SourceReference,
        request: PlaybackSourceRequest,
    ): ProviderResult<ResolvedMediaSource>

    suspend fun probeHealth(): ProviderHealth
}
```

Important difference from R15.3:

```text
Provider returns SourceTrackObservation
Normalizer decides Recording identity
```

A provider does not return a canonical `Recording`.

### 22.2 Provider DTO boundary

- Raw JSON/HTML/extractor DTOs stay inside provider packages.
- Provider mapping validates lengths, URLs, IDs, and required fields.
- Network exceptions become typed failures.
- Cancellation is always rethrown.
- Provider-specific artwork, URLs, client data, and continuation tokens do not leak into the core.
- No reusable secret is embedded in the APK.
- Fragile parsing has recorded non-secret fixtures.
- Provider health has a TTL and circuit-breaker state.
- One failing provider does not erase results from another provider or local search.

---

## 23. Source Availability

```kotlin
data class AvailabilitySnapshot(
    val state: AvailabilityState,
    val checkedAt: Instant?,
    val expiresAt: Instant?,
    val failure: AvailabilityFailure?,
)

enum class AvailabilityState {
    AVAILABLE,
    RESOLVABLE,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}
```

Availability is not identity. A source becoming unavailable does not delete the recording.

### 23.1 Revalidation

- Permanent/local asset: verify on access and bounded maintenance.
- Provider metadata source: refresh according to provider TTL.
- Resolved URL: refresh before expiry or on playback failure.
- Crew temporary source: valid only for the active session/lease.
- Cache: verify spans/checksum before claiming complete.

---

## 24. Default Source Selection Policy

The listener normally sees only the recording. The resolver uses internal policy.

Default order for a verified recording:

1. active Crew temporary asset required for current Crew context;
2. verified permanent Shippy download;
3. verified linked local asset;
4. complete valid streaming cache for a known source;
5. preferred enabled provider source;
6. alternate enabled provider source;
7. active Crew peer source;
8. unavailable.

Within permanent/local assets, prefer:

- user-selected source override;
- playable/verified state;
- non-lossy or higher quality where device and settings support it;
- lower startup cost when quality is equivalent;
- deterministic choice.

### 24.1 Important safety rule

A local file participates in this fallback order only after it has been explicitly or confidently linked to the recording. Metadata resemblance alone cannot cause Shippy to play an unrelated local file.

### 24.2 User control

Song Information may expose:

- current source;
- available sources;
- quality/codec;
- original provider link;
- “prefer this source”;
- refresh source;
- identify/unlink;
- technical diagnostics.

Provider details do not clutter primary playback controls.

---

## 25. Streaming Cache

Use a dedicated singleton Media3 `SimpleCache` with a bounded evictor.

### 25.1 Requirements

- Stable cache key derived from source identity + media variant, never expiring URL.
- Configurable maximum size.
- LRU eviction.
- Optional age-based maintenance.
- Atomic metadata updates.
- Separate directory/database from permanent downloads.
- Cache never appears as a local file or Library item.
- Partial entries are valid for playback resumption but not claimed as complete.
- Cache clear never removes permanent downloads or local files.
- Cache failures fall back to network without corrupting playback state.

### 25.2 Suggested settings

- Cache size: 256 MB, 512 MB, 1 GB, 2 GB, 5 GB, custom.
- Remove unused cached music after: 7, 30, 90 days, or only by size.
- Stream/cache on metered network toggle.
- Clear cache now.
- Show current cache size.

Do not promise exact age eviction from Media3’s stock LRU evictor. If age policy is implemented, maintain access metadata and run bounded cleanup work.

### 25.3 Complete-cache promotion

When the user chooses Download and the selected source has a complete verified cache object:

1. acquire a read lease;
2. verify content completeness/length;
3. atomically copy or publish into the permanent destination;
4. verify the resulting asset;
5. create/update the permanent `MediaAsset`;
6. only then report Downloaded;
7. leave or evict the cache according to policy.

Never mark a partial cache as a download.

---

## 26. Permanent Downloads

Permanent downloads are owned media assets attached to recordings.

### 26.1 State machine

```text
REQUESTED
  → RESOLVING
  → QUEUED
  → TRANSFERRING
  → VERIFYING
  → PUBLISHING
  → AVAILABLE

active state
  → PAUSED
  → CANCELLED
  → FAILED_RETRYABLE
  → FAILED_FINAL
  → REMOVED
```

### 26.2 Required identifiers

A download job records:

- job ID;
- recording ID;
- requested source reference ID;
- requested media variant;
- destination identity;
- progress;
- expected/actual length;
- checksum if available;
- final asset ID;
- created/updated timestamps;
- failure code/message.

### 26.3 Publication

The download becomes `AVAILABLE` only after:

- bytes are complete;
- final URI exists;
- app retains required access;
- length/format sanity checks pass;
- Media3 can open the asset;
- managed asset registry is committed;
- local scanner suppression/recognition is ready.

### 26.4 Filename

Use a readable sanitized filename, for example:

```text
Wiz Khalifa feat Charlie Puth - See You Again.m4a
```

Filename is presentation and interoperability only. Identity remains database-backed.

### 26.5 Removing a download

Removing a Shippy download:

- removes that permanent asset;
- does not unlike the recording;
- does not remove playlist entries;
- does not delete unrelated local files;
- does not delete the recording if durable references remain;
- may leave cache bytes subject to cache policy.

---

## 27. Managed Asset Registry

This subsystem prevents Shippy from rediscovering its own files as strangers.

### 27.1 Exact recognition keys

Store as many as available:

- SAF document URI;
- document ID;
- MediaStore ID;
- normalized path token where stable;
- file length;
- last modified time;
- checksum;
- audio fingerprint;
- download job ID embedded in `DownloadRequest.data` or sidecar metadata.

### 27.2 Scanner contract

Before local ingestion creates a new local source:

```kotlin
when (managedAssetRegistry.classify(scannedFile)) {
    is ManagedAssetMatch.Exact -> attach/refresh existing asset
    is ManagedAssetMatch.Probable -> queue repair review
    ManagedAssetMatch.None -> ingest as user local asset
}
```

### 27.3 Download folder behavior

The selected download folder may contain both:

- Shippy-managed downloads;
- unrelated user files.

The scanner suppresses only exact/verified managed assets. User files still appear normally.

### 27.4 Moved files

If a managed file is moved:

- exact URI may fail;
- size/checksum/fingerprint can recover identity;
- the registry updates the asset location;
- no duplicate recording is created;
- user is informed if permission is required.

---

## 28. Local Files

### 28.1 Local engine boundary

Musikr may remain the low-level scanner/indexer, but R16 exposes it through:

```kotlin
interface LocalMediaEngine {
    val scanState: StateFlow<LocalScanState>
    fun observeChanges(): Flow<LocalMediaChange>
    suspend fun scan(request: LocalScanRequest)
    suspend fun open(asset: LocalAssetKey): LocalAssetHandle
    suspend fun delete(asset: LocalAssetKey): LocalDeleteResult
    suspend fun writeTags(asset: LocalAssetKey, patch: TagPatch): TagWriteResult
}
```

No UI, playback core, Last.fm, provider, or library use case imports `musikr.Song`.

### 28.2 Ingestion

```text
Musikr/local scan result
        ↓
exact managed-asset check
        ↓
raw embedded metadata observation
        ↓
exact local source + media asset
        ↓
existing identity lookup/fingerprint
        ↓
existing Recording or new local Recording
        ↓
search index/read models update
```

### 28.3 Deletion

Deleting a local file is distinct from:

- removing it from a playlist;
- unlinking identity;
- removing a Shippy download;
- clearing cache;
- hiding a recording.

Android/MediaStore consent is handled explicitly. Batch deletion performs one bounded rescan after the batch, not one full rescan per item.

### 28.4 Metadata editing

Two operations are separate:

1. **Edit Shippy metadata**: changes canonical user overrides.
2. **Write tags to file**: attempts to mutate embedded tags for writable formats/assets.

The user may perform either or both.

---

## 29. Provider Equivalence Discovery

A YouTube Music recording does not need a JioSaavn source before playback.

When enrichment is justified:

1. build a provider search query from canonical metadata;
2. search enabled alternate providers with bounded concurrency;
3. score candidates;
4. reject version/duration conflicts;
5. auto-link only above strict thresholds;
6. store ambiguous candidates for user review;
7. persist linked `SourceReference`s;
8. do not alter Library identity or queue occurrence.

Example:

```text
Recording R
  title: See You Again
  artists: Wiz Khalifa, Charlie Puth
  source: YouTube Music video X
        ↓ enrichment
  candidate: JioSaavn song J
        ↓ strong ISRC/duration/version match
Recording R
  sources:
    - YouTube Music X
    - JioSaavn J
```

If later downloaded from either provider, the asset attaches to `Recording R`.

---

# Part V — Library and Persistence Semantics

## 30. Library Is a User Relationship, Not a Catalogue Dump

A recording may be:

- known transiently;
- recently played;
- cached;
- available online;
- scrobbled;

without being a Library item.

A recording participates in the Library when at least one durable relationship applies:

- liked;
- explicitly saved;
- locally owned asset;
- permanent download;
- user playlist entry;
- saved album/artist relationship if implemented;
- user metadata/identity edit.

### 30.1 System collections

System collections are derived queries:

- **Liked**
- **Downloads**
- **Local**

They are not mutable playlist rows.

They:

- cannot be deleted or renamed;
- can be pinned/unpinned and ordered in Library presentation;
- show canonical recordings;
- update automatically as relationships/assets change.

### 30.2 Songs tab

Recommended semantics:

Show recordings that are at least one of:

- liked;
- explicitly saved;
- locally owned;
- permanently downloaded.

A provider recording existing only inside a playlist remains accessible through that playlist but does not automatically clutter Songs unless product owner chooses otherwise.

### 30.3 Albums and artists

R16 introduces normalized Release and Artist read models. Album/artist saving is separate from liking every contained song.

### 30.4 Genres

Genres/tags are metadata facets, not identity. Local tags and trusted metadata can contribute. Genre normalization remains conservative.

---

## 31. Playlist Model

### 31.1 Required operations

- create;
- rename;
- delete;
- pin/unpin;
- reorder playlists;
- set artwork;
- add/remove entries;
- allow duplicate recording occurrences;
- reorder entries;
- batch actions;
- search;
- sort;
- download missing eligible entries;
- import/export.

### 31.2 Canonical order

Each playlist has one canonical custom order represented by stable `orderKey`s.

Temporary sort modes never overwrite canonical order.

### 31.3 Sparse order keys

Use gapped `Long` order keys, for example increments of 1024, to avoid rewriting every row on ordinary moves.

If no gap remains, rebalance the affected playlist transactionally.

### 31.4 Sort modes

Persist per playlist:

- Custom / playlist order
- Recently added
- Oldest added
- Title
- Artist
- Album
- Duration

Search and sort are independent:

```text
base playlist query
    + current search filter
    + selected display sort
```

Drag reorder is enabled only in Custom order.

### 31.5 Playlist playback

The queue is created from the exact visible order after filtering/sorting rules explicitly chosen by product design.

Default:

- Play uses current visible order.
- Shuffle creates a Shippy traversal order from all eligible playlist entries.
- Selecting a row starts that row’s exact `PlaylistEntryId` origin.
- The queue uses new `QueueEntryId`s while retaining origin references.

---

## 32. Library Layout

Library presentation layout is separate from playlist persistence.

```kotlin
data class LibraryLayoutEntry(
    val target: LibraryTarget,
    val pinned: Boolean,
    val orderKey: Long,
)
```

Targets may include:

- system collection;
- user playlist;
- saved release;
- saved artist.

Do not duplicate `pinned` state in both preferences and database. Choose one authority. R16 recommendation: persist Library layout in the R16 database so ordering and pinning are transactional and backupable.

---

## 33. Search Scopes

### 33.1 Global Search

Searches:

- local/canonical catalogue;
- Library;
- enabled providers;
- source entities such as albums/artists/playlists.

Behavior:

- local results appear first and quickly;
- provider sections arrive progressively;
- one provider failure does not erase other results;
- known exact duplicate sources are grouped under one recording;
- unconfirmed fuzzy duplicates remain separate;
- selecting a provider result can play immediately;
- recent searches are bounded and clearable.

### 33.2 Library Search

Searches only durable Library targets:

- songs;
- playlists;
- releases;
- artists;
- system collections.

It remains within Library and never contacts providers.

### 33.3 Playlist Search

Searches only entries in the current playlist.

It remains in the current collection and retains sort mode.

### 33.4 Search implementation

Use Room FTS for canonical local/library data and provider APIs for remote data.

The UI does not repeatedly scan every in-memory object on each keystroke.

### 33.5 Search cancellation

Every query has a generation ID. Results from an older query are discarded even if a slow provider returns later.

### 33.6 Debounce

- Local FTS: short debounce or immediate after minimum input.
- Remote providers: 150–300 ms debounce, configurable after measurement.
- Explicit submit triggers immediately.
- Never delay showing local results while waiting for remote debounce.

---

## 34. Home and Listening History Data

### 34.1 Play history

Persist listening sessions separately from Last.fm:

```kotlin
data class PlayHistoryEntry(
    val sessionId: ListeningSessionId,
    val recordingId: RecordingId,
    val queueEntryId: QueueEntryId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val activeListenedMs: Long,
    val lastPositionMs: Long,
    val completion: ListeningCompletion,
)
```

### 34.2 Home sections

Recommended R16 Home hierarchy:

1. current/continue queue context;
2. compact horizontal Recently Played;
3. pinned shortcuts;
4. Last.fm discovery when connected;
5. active/recent Crew context when relevant.

Remove permanent duplicate sections such as `Your Music` and static Recent Downloads.

### 34.3 Continue Listening

The action must:

- refer to a concrete queue/checkpoint;
- set loading state immediately;
- restore the correct queue entry by identity;
- start playback if Play was pressed;
- open Now Playing only after/while the playback request is acknowledged;
- prevent duplicate concurrent requests.

### 34.4 History dedupe

Recent presentation may collapse repeated sessions of the same recording, but raw history remains session-based.

---

# Part VI — Proposed R16 Gradle and Package Architecture

## 35. Module Boundaries

R16 should enforce a small set of durable boundaries.

```text
:app
  UI, navigation, Android entry points, resources, system wiring
        ↓
:shippy-core
  pure domain, identity, queue, playback reducer, use-case contracts
        ↓
:shippy-data
  Room, repositories, migrations, read models, WorkManager ledgers
        ↓
:shippy-sources
  local engine adapter, providers, source resolution, cache/download integration
        ↓
:shippy-integrations
  Last.fm, lyrics, MusicBrainz, AcoustID, imports/sharing
        ↓
:shippy-crew
  Crew protocol/runtime/media adapters using canonical IDs

Existing low-level dependencies:
:musikr
:media-lib-exoplayer
:media-lib-decoder-ffmpeg
```

The arrows above are conceptual. Actual dependency direction must avoid cycles:

```text
app → core, data APIs, sources APIs, integrations APIs, crew APIs
data → core
sources → core, data APIs, musikr, media libraries
integrations → core, data APIs
crew → core, data APIs, sources APIs
```

Concrete repository implementations can be bound in `app` through Hilt.

### 35.1 Why these modules

- `core` must be host-JVM testable without Android.
- `data` isolates schema/migration/read-model work.
- `sources` isolates unstable provider/local/media machinery.
- `integrations` prevents Last.fm/lyrics metadata services from becoming playback dependencies.
- `crew` already has sufficient size and deterministic logic to justify isolation.
- `app` remains the Views product shell.

Do not create one Gradle module per feature or per screen.

### 35.2 Transitional package-first option

If module extraction blocks early recovery, first create the same boundaries as packages with forbidden-dependency tests, then extract modules before the old model is deleted. The final R16 branch must have enforceable boundaries.

---

## 36. Namespace

Use `app.shippy.*` in this document as the illustrative internal root. The final root must follow the application ID decision in Section 82.

Suggested packages:

```text
app.shippy.core.identity
app.shippy.core.model
app.shippy.core.library
app.shippy.core.playback
app.shippy.core.matching
app.shippy.data.db
app.shippy.data.library
app.shippy.data.history
app.shippy.data.migration
app.shippy.sources.local
app.shippy.sources.provider
app.shippy.sources.playback
app.shippy.sources.cache
app.shippy.sources.download
app.shippy.integrations.lastfm
app.shippy.integrations.lyrics
app.shippy.integrations.musicbrainz
app.shippy.integrations.acoustid
app.shippy.crew
app.shippy.ui
```

No first-party public API uses `org.oxycblt.auxio.*`.

Third-party or derived code may retain original legal headers. Naming cleanup must not falsify provenance.


# Part VII — R16 Persistence Model

## 37. Database Strategy

The current database is version 10 and contains 13 entities covering library relationships, user playlists, playlist membership, download jobs/candidates, lyrics, Crew checkpoint, canonical tracks/candidates, Last.fm outbox, playback checkpoint, and saved provider entities.

The R16 schema changes identity semantics too deeply for a casual in-place table shuffle.

### 37.1 Recommended strategy: new R16 database plus verified importer

Create:

```text
legacy database: existing ShippyDatabase v10, read-only during import
new database: shippy-r16.db, schema v1
```

Advantages:

- import is repeatable and testable;
- legacy data remains available for rollback/recovery;
- new schema is not constrained by old primary keys;
- migration can run in bounded transactions;
- audit counts can be compared before cutover;
- a failed import does not leave a half-mutated database;
- the app can produce a human-readable migration report.

After one stable release and explicit backup confirmation, old database removal may be considered.

### 37.2 Migration state

```kotlin
enum class R16MigrationStatus {
    NOT_STARTED,
    PREPARING,
    IMPORTING,
    VERIFYING,
    READY_TO_SWITCH,
    ACTIVE,
    FAILED_RECOVERABLE,
}
```

Persist migration status outside either mutable schema in a small atomic state file or dedicated bootstrap preferences.

### 37.3 Cutover rule

The app uses the new database only after:

- all import phases completed;
- row-count and referential checks pass;
- playlists and Library relationships reconcile;
- available downloads are reverified;
- a sample/full playback checkpoint can resolve;
- a migration audit row is committed;
- the user-data backup/export step is satisfied.

---

## 38. Proposed Tables

The exact Room class names may vary. The semantics may not.

### 38.1 `recording`

```sql
CREATE TABLE recording (
    recording_id TEXT NOT NULL PRIMARY KEY,
    canonical_title TEXT NOT NULL,
    duration_ms INTEGER,
    version_kind TEXT NOT NULL,
    version_label TEXT,
    explicitness TEXT NOT NULL,
    preferred_release_id TEXT,
    preferred_artwork_id TEXT,
    retention_kind TEXT NOT NULL,
    retained_until_epoch_ms INTEGER,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    FOREIGN KEY(preferred_release_id) REFERENCES release(release_id)
);
CREATE INDEX index_recording_title ON recording(canonical_title);
CREATE INDEX index_recording_retention ON recording(retention_kind, retained_until_epoch_ms);
```

### 38.2 `artist`

```sql
CREATE TABLE artist (
    artist_id TEXT NOT NULL PRIMARY KEY,
    canonical_name TEXT NOT NULL,
    sort_name TEXT,
    disambiguation TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_artist_name ON artist(canonical_name);
```

### 38.3 `recording_artist_credit`

```sql
CREATE TABLE recording_artist_credit (
    recording_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    artist_id TEXT NOT NULL,
    credited_name TEXT NOT NULL,
    join_phrase TEXT NOT NULL,
    PRIMARY KEY(recording_id, position),
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id) ON DELETE CASCADE,
    FOREIGN KEY(artist_id) REFERENCES artist(artist_id)
);
CREATE INDEX index_recording_artist_credit_artist ON recording_artist_credit(artist_id);
```

### 38.4 `release`

```sql
CREATE TABLE release (
    release_id TEXT NOT NULL PRIMARY KEY,
    canonical_title TEXT NOT NULL,
    release_type TEXT NOT NULL,
    release_year INTEGER,
    artwork_id TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_release_title ON release(canonical_title);
```

### 38.5 `release_track`

```sql
CREATE TABLE release_track (
    release_track_id TEXT NOT NULL PRIMARY KEY,
    release_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    disc_number INTEGER,
    track_number INTEGER,
    display_title TEXT,
    order_key INTEGER NOT NULL,
    FOREIGN KEY(release_id) REFERENCES release(release_id) ON DELETE CASCADE,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id)
);
CREATE INDEX index_release_track_release_order
    ON release_track(release_id, order_key);
CREATE INDEX index_release_track_recording
    ON release_track(recording_id);
```

### 38.6 `source_reference`

```sql
CREATE TABLE source_reference (
    source_reference_id TEXT NOT NULL PRIMARY KEY,
    recording_id TEXT,
    provider_id TEXT NOT NULL,
    item_type TEXT NOT NULL,
    source_item_id TEXT NOT NULL,
    original_url TEXT,
    availability_state TEXT NOT NULL,
    availability_checked_at_epoch_ms INTEGER,
    availability_expires_at_epoch_ms INTEGER,
    failure_kind TEXT,
    identity_status TEXT NOT NULL,
    raw_metadata_observation_id TEXT NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id)
);
CREATE UNIQUE INDEX index_source_reference_exact_key
    ON source_reference(provider_id, item_type, source_item_id);
CREATE INDEX index_source_reference_recording
    ON source_reference(recording_id);
CREATE INDEX index_source_reference_availability
    ON source_reference(provider_id, availability_state);
```

Local source references may use provider ID `local` plus an exact engine key. The playable bytes still live in `media_asset`.

### 38.7 `metadata_observation`

Use normalized columns for common fields and bounded JSON for provider extras.

```sql
CREATE TABLE metadata_observation (
    observation_id TEXT NOT NULL PRIMARY KEY,
    source_type TEXT NOT NULL,
    source_reference_id TEXT,
    asset_id TEXT,
    title TEXT,
    artist_credit_json TEXT,
    release_title TEXT,
    release_artist TEXT,
    duration_ms INTEGER,
    artwork_json TEXT,
    release_year INTEGER,
    track_number INTEGER,
    disc_number INTEGER,
    genres_json TEXT,
    version_hints_json TEXT,
    external_ids_json TEXT,
    extras_json TEXT,
    captured_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_metadata_observation_source
    ON metadata_observation(source_reference_id, captured_at_epoch_ms);
CREATE INDEX index_metadata_observation_asset
    ON metadata_observation(asset_id, captured_at_epoch_ms);
```

Do not use unbounded arbitrary JSON as the only schema. Common queryable fields stay typed.

### 38.8 `external_identifier`

```sql
CREATE TABLE external_identifier (
    external_identifier_id TEXT NOT NULL PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    scheme TEXT NOT NULL,
    value TEXT NOT NULL,
    verified INTEGER NOT NULL,
    source_observation_id TEXT,
    created_at_epoch_ms INTEGER NOT NULL
);
CREATE UNIQUE INDEX index_external_identifier_owner_scheme_value
    ON external_identifier(owner_type, owner_id, scheme, value);
CREATE INDEX index_external_identifier_lookup
    ON external_identifier(scheme, value);
```

### 38.9 `artwork_reference`

```sql
CREATE TABLE artwork_reference (
    artwork_id TEXT NOT NULL PRIMARY KEY,
    owner_type TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    location_type TEXT NOT NULL,
    location TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    source_observation_id TEXT,
    preferred INTEGER NOT NULL,
    content_hash TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    last_verified_at_epoch_ms INTEGER
);
CREATE INDEX index_artwork_owner ON artwork_reference(owner_type, owner_id, preferred);
```

### 38.10 `media_asset`

```sql
CREATE TABLE media_asset (
    asset_id TEXT NOT NULL PRIMARY KEY,
    recording_id TEXT NOT NULL,
    source_reference_id TEXT,
    asset_kind TEXT NOT NULL,
    asset_state TEXT NOT NULL,
    location_type TEXT NOT NULL,
    location TEXT NOT NULL,
    document_id TEXT,
    media_store_id INTEGER,
    display_name TEXT,
    mime_type TEXT,
    container TEXT,
    codec TEXT,
    bitrate_bps INTEGER,
    sample_rate_hz INTEGER,
    channel_count INTEGER,
    content_length INTEGER,
    content_checksum TEXT,
    fingerprint_id TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    last_verified_at_epoch_ms INTEGER,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id),
    FOREIGN KEY(source_reference_id) REFERENCES source_reference(source_reference_id)
);
CREATE UNIQUE INDEX index_media_asset_location
    ON media_asset(location_type, location);
CREATE INDEX index_media_asset_recording_kind_state
    ON media_asset(recording_id, asset_kind, asset_state);
CREATE INDEX index_media_asset_checksum
    ON media_asset(content_checksum);
```

Cache spans may remain in Media3’s cache database. Only complete/promotable cache metadata needs a Shippy row.

### 38.11 `audio_fingerprint`

```sql
CREATE TABLE audio_fingerprint (
    fingerprint_id TEXT NOT NULL PRIMARY KEY,
    asset_id TEXT NOT NULL,
    algorithm TEXT NOT NULL,
    algorithm_version TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
    compressed_fingerprint BLOB NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL,
    FOREIGN KEY(asset_id) REFERENCES media_asset(asset_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX index_audio_fingerprint_asset_algorithm
    ON audio_fingerprint(asset_id, algorithm, algorithm_version);
```

### 38.12 `library_recording`

```sql
CREATE TABLE library_recording (
    recording_id TEXT NOT NULL PRIMARY KEY,
    liked INTEGER NOT NULL,
    explicitly_saved INTEGER NOT NULL,
    user_edited INTEGER NOT NULL,
    manually_identified INTEGER NOT NULL,
    first_added_at_epoch_ms INTEGER,
    updated_at_epoch_ms INTEGER NOT NULL,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id) ON DELETE CASCADE
);
CREATE INDEX index_library_recording_liked ON library_recording(liked);
CREATE INDEX index_library_recording_saved ON library_recording(explicitly_saved);
```

Do not store downloaded/local booleans; derive from assets.

### 38.13 `playlist`

```sql
CREATE TABLE playlist (
    playlist_id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    pinned INTEGER NOT NULL,
    library_order_key INTEGER NOT NULL,
    artwork_override TEXT,
    display_sort_mode TEXT NOT NULL,
    display_sort_direction TEXT NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_playlist_library_order
    ON playlist(pinned DESC, library_order_key);
```

### 38.14 `playlist_entry`

```sql
CREATE TABLE playlist_entry (
    playlist_entry_id TEXT NOT NULL PRIMARY KEY,
    playlist_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    order_key INTEGER NOT NULL,
    added_at_epoch_ms INTEGER NOT NULL,
    FOREIGN KEY(playlist_id) REFERENCES playlist(playlist_id) ON DELETE CASCADE,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id)
);
CREATE INDEX index_playlist_entry_order
    ON playlist_entry(playlist_id, order_key);
CREATE INDEX index_playlist_entry_recording
    ON playlist_entry(recording_id);
```

No unique constraint on `(playlist_id, recording_id)`.

### 38.15 `library_layout_entry`

```sql
CREATE TABLE library_layout_entry (
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    pinned INTEGER NOT NULL,
    order_key INTEGER NOT NULL,
    PRIMARY KEY(target_type, target_id)
);
CREATE INDEX index_library_layout_order
    ON library_layout_entry(pinned DESC, order_key);
```

### 38.16 `user_metadata_override`

```sql
CREATE TABLE user_metadata_override (
    recording_id TEXT NOT NULL,
    field_name TEXT NOT NULL,
    value_json TEXT NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY(recording_id, field_name),
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id) ON DELETE CASCADE
);
```

### 38.17 `canonical_field_provenance`

```sql
CREATE TABLE canonical_field_provenance (
    recording_id TEXT NOT NULL,
    field_name TEXT NOT NULL,
    selected_source_type TEXT NOT NULL,
    selected_source_id TEXT,
    confidence REAL NOT NULL,
    selected_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY(recording_id, field_name),
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id) ON DELETE CASCADE
);
```

### 38.18 `identity_decision`

```sql
CREATE TABLE identity_decision (
    decision_id TEXT NOT NULL PRIMARY KEY,
    subject_type TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    target_recording_id TEXT,
    decision_kind TEXT NOT NULL,
    confidence REAL,
    evidence_json TEXT NOT NULL,
    user_confirmed INTEGER NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_identity_decision_subject
    ON identity_decision(subject_type, subject_id, created_at_epoch_ms);
```

### 38.19 `identity_rejection`

```sql
CREATE TABLE identity_rejection (
    subject_type TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    rejected_recording_id TEXT NOT NULL,
    reason TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY(subject_type, subject_id, rejected_recording_id)
);
```

### 38.20 `entity_redirect`

```sql
CREATE TABLE entity_redirect (
    old_recording_id TEXT NOT NULL PRIMARY KEY,
    canonical_recording_id TEXT NOT NULL,
    merge_audit_id TEXT NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL
);
CREATE INDEX index_entity_redirect_target
    ON entity_redirect(canonical_recording_id);
```

### 38.21 `merge_audit`

```sql
CREATE TABLE merge_audit (
    merge_audit_id TEXT NOT NULL PRIMARY KEY,
    survivor_recording_id TEXT NOT NULL,
    merged_recording_id TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    user_confirmed INTEGER NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL,
    reversed_at_epoch_ms INTEGER
);
```

### 38.22 `play_history`

```sql
CREATE TABLE play_history (
    listening_session_id TEXT NOT NULL PRIMARY KEY,
    recording_id TEXT NOT NULL,
    queue_entry_id TEXT NOT NULL,
    source_reference_id TEXT,
    started_at_epoch_ms INTEGER NOT NULL,
    ended_at_epoch_ms INTEGER,
    active_listened_ms INTEGER NOT NULL,
    last_position_ms INTEGER NOT NULL,
    completion_kind TEXT NOT NULL,
    chosen_by_user INTEGER NOT NULL,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id)
);
CREATE INDEX index_play_history_recent
    ON play_history(started_at_epoch_ms DESC);
CREATE INDEX index_play_history_recording
    ON play_history(recording_id, started_at_epoch_ms DESC);
```

### 38.23 `playback_checkpoint`

```sql
CREATE TABLE playback_checkpoint (
    slot TEXT NOT NULL PRIMARY KEY,
    checkpoint_version INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    current_queue_entry_id TEXT,
    position_ms INTEGER NOT NULL,
    playing_intent INTEGER NOT NULL,
    repeat_mode TEXT NOT NULL,
    shuffle_enabled INTEGER NOT NULL,
    shuffle_seed INTEGER,
    base_order_json TEXT NOT NULL,
    traversal_order_json TEXT NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    checksum TEXT NOT NULL
);
```

### 38.24 `playback_checkpoint_entry`

```sql
CREATE TABLE playback_checkpoint_entry (
    slot TEXT NOT NULL,
    queue_entry_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    recording_id TEXT NOT NULL,
    origin_json TEXT,
    contributor_id TEXT,
    presentation_fallback_json TEXT NOT NULL,
    PRIMARY KEY(slot, queue_entry_id),
    FOREIGN KEY(slot) REFERENCES playback_checkpoint(slot) ON DELETE CASCADE
);
CREATE INDEX index_playback_checkpoint_entry_position
    ON playback_checkpoint_entry(slot, position);
```

### 38.25 `download_job`

Retain a normalized version of current job semantics keyed by recording/source/asset IDs. Do not duplicate canonical metadata into every job except a bounded display fallback needed for recovery.

### 38.26 `lastfm_scrobble_outbox`

```sql
CREATE TABLE lastfm_scrobble_outbox (
    outbox_id TEXT NOT NULL PRIMARY KEY,
    listening_session_id TEXT NOT NULL,
    recording_id TEXT NOT NULL,
    artist TEXT NOT NULL,
    track TEXT NOT NULL,
    album TEXT,
    duration_seconds INTEGER,
    started_at_epoch_seconds INTEGER NOT NULL,
    chosen_by_user INTEGER NOT NULL,
    queued_at_epoch_ms INTEGER NOT NULL,
    attempt_count INTEGER NOT NULL,
    last_attempt_at_epoch_ms INTEGER,
    FOREIGN KEY(recording_id) REFERENCES recording(recording_id)
);
CREATE INDEX index_lastfm_outbox_fifo
    ON lastfm_scrobble_outbox(queued_at_epoch_ms);
```

### 38.27 `lyrics_cache`

Key by recording identity plus metadata fingerprint/provider source, not transient queue index.

### 38.28 `saved_source_entity`

Provider albums/artists/playlists that have not yet been promoted into canonical Release/Artist/Playlist entities may be persisted as source entities. They are not mixed with user playlists.

### 38.29 `migration_audit`

```sql
CREATE TABLE migration_audit (
    migration_id TEXT NOT NULL PRIMARY KEY,
    source_version INTEGER NOT NULL,
    target_version INTEGER NOT NULL,
    started_at_epoch_ms INTEGER NOT NULL,
    completed_at_epoch_ms INTEGER,
    source_counts_json TEXT NOT NULL,
    target_counts_json TEXT,
    warnings_json TEXT,
    checksum TEXT,
    status TEXT NOT NULL
);
```

---

## 39. Database Views and Read Models

Room database views encapsulate screen-shaped queries.

### 39.1 `library_song_view`

Fields:

- RecordingId
- title
- artist display
- release title
- artwork
- duration
- liked
- local asset exists
- download asset exists
- last played
- date added
- sort keys

### 39.2 `playlist_entry_view`

Fields:

- PlaylistEntryId
- PlaylistId
- orderKey
- RecordingId
- canonical presentation
- liked/offline state
- download state
- availability summary

### 39.3 `album_view`

Aggregates release presentation and library relationship without loading every track.

### 39.4 `artist_view`

Aggregates canonical artist presentation and counts.

### 39.5 `library_target_view`

Unifies system collections and user/saved targets for Library presentation only.

### 39.6 Why views

- keep SQL joins near the data;
- produce exactly what screens need;
- prevent `observeAll()` + in-memory reconstruction;
- make PagingSource queries straightforward;
- centralize sort/filter semantics;
- make migration and performance tests explicit.

---

## 40. Full-Text Search

Use Room-supported FTS4.

Suggested content:

```text
recording_id (unindexed payload)
title
artist_names
release_title
aliases
source_titles
user_override_text
```

### 40.1 Search update

Update FTS transactionally when:

- canonical metadata changes;
- a source observation is linked;
- a user override changes;
- a merge/unmerge occurs;
- a local file is identified.

### 40.2 Token rules

- normalized secondary columns for case/diacritic-insensitive matching;
- prefix search after sensible minimum characters;
- exact phrase boosts;
- title > artist > release > aliases;
- source filename tokens have lowest rank.

### 40.3 Playlist search

Query `playlist_entry_view` joined with the FTS table and return a `PagingSource`.

---

## 41. Repository Contracts

UI and use cases depend on interfaces, not DAOs.

### 41.1 Recording repository

```kotlin
interface RecordingRepository {
    fun observe(id: RecordingId): Flow<RecordingDetails?>
    fun observeMany(ids: Set<RecordingId>): Flow<Map<RecordingId, RecordingSummary>>
    suspend fun get(id: RecordingId): RecordingDetails?
    suspend fun ensureFromSource(source: SourceTrackObservation): RecordingId
    suspend fun resolveRedirect(id: RecordingId): RecordingId
}
```

### 41.2 Library repository

```kotlin
interface LibraryRepository {
    fun songs(query: LibrarySongQuery): Flow<PagingData<LibrarySongRow>>
    fun targets(query: LibraryTargetQuery): Flow<PagingData<LibraryTargetRow>>
    fun relationship(id: RecordingId): Flow<LibraryRelationship>
    suspend fun updateRelationship(command: LibraryCommand)
    suspend fun reorderLayout(command: ReorderLibraryCommand)
}
```

### 41.3 Playlist repository

```kotlin
interface PlaylistRepository {
    fun observePlaylist(id: PlaylistId): Flow<PlaylistDetails?>
    fun entries(id: PlaylistId, query: PlaylistEntryQuery): Flow<PagingData<PlaylistEntryRow>>
    suspend fun create(command: CreatePlaylist): PlaylistId
    suspend fun rename(command: RenamePlaylist)
    suspend fun add(command: AddPlaylistEntries): List<PlaylistEntryId>
    suspend fun remove(entryIds: Set<PlaylistEntryId>)
    suspend fun move(command: MovePlaylistEntry)
    suspend fun delete(id: PlaylistId)
}
```

### 41.4 Source repository

```kotlin
interface SourceRepository {
    fun observe(recordingId: RecordingId): Flow<List<SourceSummary>>
    suspend fun exact(key: SourceKey): SourceReference?
    suspend fun upsert(observation: SourceTrackObservation): SourceReferenceId
    suspend fun updateAvailability(update: AvailabilityUpdate)
}
```

### 41.5 Asset repository

```kotlin
interface MediaAssetRepository {
    fun observe(recordingId: RecordingId): Flow<List<MediaAsset>>
    suspend fun verifiedPlayableAssets(recordingId: RecordingId): List<MediaAsset>
    suspend fun upsert(asset: MediaAsset)
    suspend fun markUnavailable(id: MediaAssetId, reason: AssetFailure)
}
```

### 41.6 History repository

Expose paged recent history and recording aggregates. Last.fm never becomes the only history source.

---

## 42. Transaction Boundaries

The following are atomic database operations:

- save/unsave/like plus destination playlist updates;
- create playlist and initial entries;
- reorder playlist;
- link asset/source to recording;
- merge/unmerge recording;
- confirm identity;
- publish verified download asset;
- update canonical metadata and FTS;
- import one migration unit;
- write checkpoint and entries;
- dequeue accepted Last.fm scrobbles.

Never update canonical metadata, source link, FTS, and Library rows in independent fire-and-forget coroutines.

---

## 43. Pagination

Use Paging 3 for:

- Songs;
- Liked;
- Local;
- Downloads;
- playlist detail;
- albums;
- artists;
- genres where large;
- history;
- provider browse results where supported.

### 43.1 Page size

Start with 50 rows and tune through benchmarks.

### 43.2 Placeholders

Prefer disabled Paging placeholders unless the UI has a reliable fixed-count use. Use explicit loading rows/skeletons.

### 43.3 Remote providers

Provider search/browse uses continuation tokens. A provider-specific pager may feed sections. Do not merge incompatible continuation tokens into one pretend global cursor.

### 43.4 Invalidations

Room invalidates only affected PagingSources. Avoid screen-wide `submitList()` rebuilds on every unrelated database change.

---

## 44. Backup and Export

R16 must support an explicit `ShippyBackupV1` archive containing:

- recording identities and canonical metadata;
- source references without expiring URLs/secrets;
- Library relationships;
- playlists and entries;
- user overrides;
- manual identity/negative decisions;
- download records and asset references, optionally media manifests;
- app settings excluding secrets;
- Last.fm username/config state but not API secret/session unless securely wrapped and explicitly allowed;
- history according to user choice;
- schema/version manifest and checksums.

It must exclude by default:

- cache bytes;
- Crew temporary bytes;
- provider request headers/tokens;
- raw Last.fm secret/session material;
- ephemeral resolved URLs;
- logs.

Backup is necessary both for user trust and for any application-ID transition.

---

# Part VIII — Playback Core

## 45. Playback Architecture Goal

The R16 playback system has one canonical event loop:

```text
UI / notification / headset / Android Auto / Crew
                         ↓
                PlaybackCommandRouter
                         ↓
                PlaybackCoordinator
       (serialized intents, observations, effects)
                         ↓
                 PlaybackSnapshot
          ┌──────────────┼──────────────┐
          ↓              ↓              ↓
       Media3       UI/system views   listeners
                                     history/Last.fm
```

Media3 produces observations such as:

- item committed;
- playing changed;
- buffering changed;
- position discontinuity;
- error;
- playback ended.

Media3 does not independently own the product queue.

---

## 46. PlaybackCoordinator

### 46.1 Scope

One process-scoped coordinator owned by the playback service/application graph.

### 46.2 Event loop

```kotlin
sealed interface PlaybackEvent {
    data class Intent(val command: PlaybackCommand) : PlaybackEvent
    data class SourcePrepared(val result: SourcePreparationResult) : PlaybackEvent
    data class Engine(val observation: PlayerObservation) : PlaybackEvent
    data class Timer(val tick: PlaybackTimerTick) : PlaybackEvent
    data class Restore(val checkpoint: RestoredCheckpoint) : PlaybackEvent
}
```

A single channel serializes state transitions.

I/O is launched as tagged effects and returns events. The event loop itself does not block on network.

### 46.3 Generation safety

Every queue replacement increments `PlaybackGeneration`.

Every source preparation, player transaction, artwork/lyrics request, checkpoint write, and delayed callback carries:

- generation;
- queue entry ID;
- optional transaction ID.

Stale results are discarded.

### 46.4 Immutable state

```kotlin
data class PlaybackSnapshot(
    val generation: Long,
    val sessionId: PlaybackSessionId?,
    val baseQueue: List<QueueEntry>,
    val traversalOrder: List<QueueEntryId>,
    val currentQueueEntryId: QueueEntryId?,
    val phase: PlaybackPhase,
    val playWhenReady: Boolean,
    val repeatMode: RepeatMode,
    val shuffle: ShuffleState,
    val position: PositionAnchor,
    val resolvedSources: Map<QueueEntryId, ResolvedPlaybackSource>,
    val engineWindow: EngineWindow,
    val currentError: PlaybackError?,
)
```

All consumers read this snapshot or screen-specific projections from it.

### 46.5 Commands

```kotlin
sealed interface PlaybackCommand {
    data class PlayContext(
        val entries: List<QueueSeed>,
        val selectedOrigin: QueueOriginSelector,
        val shuffle: Boolean,
    ) : PlaybackCommand

    data object Play : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Next : PlaybackCommand
    data object Previous : PlaybackCommand
    data class GoTo(val queueEntryId: QueueEntryId) : PlaybackCommand
    data class SeekTo(val positionMs: Long) : PlaybackCommand
    data class SetShuffle(val enabled: Boolean) : PlaybackCommand
    data class SetRepeat(val mode: RepeatMode) : PlaybackCommand
    data class AddNext(val seeds: List<QueueSeed>) : PlaybackCommand
    data class AddToEnd(val seeds: List<QueueSeed>) : PlaybackCommand
    data class Move(val entryId: QueueEntryId, val anchor: QueueAnchor) : PlaybackCommand
    data class Remove(val entryIds: Set<QueueEntryId>) : PlaybackCommand
    data object Clear : PlaybackCommand
}
```

No command addresses a numeric index as its durable identity.

---

## 47. Command Routing and Crew

Commands first pass through:

```kotlin
interface PlaybackCommandRouter {
    suspend fun dispatch(command: PlaybackCommand): CommandResult
}
```

Behavior:

- ordinary mode: dispatch to local coordinator;
- active Crew: convert allowed user intent into Crew actions;
- remote Crew state: project canonical Crew intent into local coordinator through a separate trusted path;
- system media controls use the same router.

This replaces after-the-fact mutation interception.

### 47.1 One authority per concern

- Crew state owns shared collaborative intent.
- Local `PlaybackCoordinator` owns actual device playback state.
- Media3 owns decoder/buffer/clock observations.
- The bridge maps between them; it owns none of them.

---

## 48. ListeningSessionTracker

This is shared infrastructure for:

- Shippy history;
- Last.fm scrobbling;
- Continue Listening analytics;
- completion/recent ranking.

### 48.1 Audible time

Count active listening using monotonic time:

- start when the exact queue entry becomes audibly playing;
- stop on pause, buffering beyond policy, item change, route loss, error, or session end;
- account for playback speed;
- do not add seek jumps;
- do not use wall-clock deltas;
- flush periodically and on lifecycle boundaries.

### 48.2 State

```kotlin
data class ActiveListeningSession(
    val id: ListeningSessionId,
    val queueEntryId: QueueEntryId,
    val recordingId: RecordingId,
    val sourceReferenceId: SourceReferenceId?,
    val startedAtWallClock: Instant,
    val lastAudibleAnchorElapsedRealtimeMs: Long?,
    val accumulatedAudibleMs: Long,
    val chosenByUser: Boolean,
)
```

### 48.3 Tick strategy

The tracker does not need a 100 ms database write.

- update in memory from engine state;
- use a low-frequency monotonic tick while playing, e.g. 1 second;
- persist checkpoints every bounded interval and on important transitions;
- finalise on queue-entry change/end;
- UI position sampling is separate.

This directly fixes the current Last.fm defect.

---

## 49. Queue Model

### 49.1 Base queue vs traversal order

`baseQueue` preserves insertion/context order.

`traversalOrder` defines actual playback order.

Unshuffled:

```text
traversalOrder = baseQueue IDs
```

Shuffled:

```text
traversalOrder = deterministic permutation of queue-entry IDs
```

### 49.2 Shippy owns shuffle

Media3 shuffle mode remains disabled. Media3 receives the exact window order Shippy wants played.

This removes:

- raw heap vs projected queue;
- Media3 shuffle mapping;
- stale numeric projection;
- `BetterShuffleOrder` as a product authority;
- the class of metadata/audio mismatches fixed repeatedly in R15.x.

### 49.3 Shuffle invariants

- every queue entry appears exactly once;
- current entry identity never changes when toggling;
- adding/removing entries updates traversal without duplicating;
- duplicate recordings remain distinct queue entries;
- repeat behavior is deterministic;
- checkpoint/restore reproduces order;
- seed/permutation changes only when requested.

### 49.4 Toggling shuffle off

Restore base order while preserving the current queue entry. The next item follows the current entry’s position in base order, subject to repeat policy.

### 49.5 Move semantics

Moves operate by entry ID and neighbor anchors, not source/destination indices alone:

```kotlin
data class QueueAnchor(
    val before: QueueEntryId?,
    val after: QueueEntryId?,
)
```

This supports concurrent Crew edits and avoids stale index moves.

---

## 50. Engine Window

The product queue may contain thousands of lightweight entries. Media3 should not require expensive preparation for all of them.

### 50.1 Window contents

Default:

- one previous entry where useful;
- current entry;
- next two or three resolved/prepared entries.

### 50.2 Rebuild conditions

- new context;
- jump outside window;
- shuffle traversal replacement;
- source failure requiring different window;
- repeat wrap;
- Crew canonical replacement.

### 50.3 Expansion

As playback approaches window end:

1. prepare next source;
2. append/replace MediaItem;
3. update window map;
4. retain queue-entry IDs.

### 50.4 Automatic transition

The next MediaItem must be ready early enough for gapless/crossfade behavior. Preparation priority is based on distance from current entry.

### 50.5 Fallback

If window projection proves incompatible with required MediaSession behavior on some Android versions, keep a lightweight full MediaItem list but resolve only the bounded window. The canonical queue and Shippy shuffle remain authoritative either way.

---

## 51. Media3 Adapter

Split the current `ExoPlaybackStateHolder` into focused types:

```text
Media3PlayerAdapter
MediaItemFactory
PlayerTransactionTracker
PlaybackWindowController
SourceRefreshController
CrossfadeController
AudioSessionController
PlaybackRequestHeaderStore
PlayerDiagnostics
```

### 51.1 Media3PlayerAdapter contract

```kotlin
interface PlayerEngine {
    val observations: Flow<PlayerObservation>

    suspend fun apply(transaction: PlayerTransaction)
    suspend fun setPlayWhenReady(value: Boolean)
    suspend fun seek(queueEntryId: QueueEntryId, positionMs: Long)
    suspend fun setRepeat(mode: RepeatMode)
    suspend fun setPlaybackSpeed(speed: Float)
    suspend fun release()
}
```

### 51.2 Player transaction

```kotlin
data class PlayerTransaction(
    val id: PlayerTransactionId,
    val generation: Long,
    val expectedCurrentEntryId: QueueEntryId,
    val window: List<PreparedEngineItem>,
    val startPositionMs: Long,
    val playWhenReady: Boolean,
)
```

The transaction exists before calling `setMediaItems`.

### 51.3 Observation

```kotlin
sealed interface PlayerObservation {
    data class CurrentItemCommitted(
        val transactionId: PlayerTransactionId?,
        val queueEntryId: QueueEntryId,
        val reason: TransitionReason,
    ) : PlayerObservation

    data class PhaseChanged(
        val queueEntryId: QueueEntryId?,
        val phase: EnginePhase,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
    ) : PlayerObservation

    data class PositionDiscontinuity(
        val queueEntryId: QueueEntryId,
        val oldPositionMs: Long,
        val newPositionMs: Long,
        val reason: DiscontinuityReason,
    ) : PlayerObservation

    data class Failed(
        val queueEntryId: QueueEntryId?,
        val error: EngineError,
    ) : PlayerObservation

    data class Ended(val queueEntryId: QueueEntryId) : PlayerObservation
}
```

### 51.4 Commit rule

Product current-item state changes only when:

- coordinator intentionally selects a queue entry and enters Preparing; then
- Media3 confirms the same `QueueEntryId` for the active generation/transaction.

No pending expected ID may be assigned after player mutation.

---

## 52. Source Preparation

```kotlin
interface PlaybackSourcePreparer {
    suspend fun prepare(
        recordingId: RecordingId,
        queueEntryId: QueueEntryId,
        policy: SourceSelectionPolicy,
        constraints: PlaybackConstraints,
    ): SourcePreparationResult
}
```

### 52.1 Result

```kotlin
sealed interface SourcePreparationResult {
    data class Ready(
        val queueEntryId: QueueEntryId,
        val source: ResolvedPlaybackSource,
    ) : SourcePreparationResult

    data class Unavailable(
        val queueEntryId: QueueEntryId,
        val attempted: List<SourceAttempt>,
        val retryable: Boolean,
    ) : SourcePreparationResult
}
```

### 52.2 Expiry

Resolved provider URLs have:

- source reference;
- expiry;
- headers;
- cache key;
- variant;
- refresh strategy.

They are never written into canonical recording/source metadata as permanent identity.

### 52.3 Cancellation

Preparation for stale generation/entry is cancelled. A cancelled provider request must not be translated into an error row.

---

## 53. Playback Error Policy

### 53.1 Current source fails

1. retry same source if error/expiry policy allows;
2. use another already-known asset/source;
3. perform bounded alternate discovery if user intent is durable/current;
4. remain on the same queue entry during buffering/recovery;
5. skip only after policy/user choice;
6. never silently change canonical recording to fit available audio.

### 53.2 Error presentation

Primary UI:

- brief, understandable status;
- Retry;
- choose another source where useful;
- skip.

Diagnostics sheet:

- provider/source;
- error type;
- attempts;
- timestamp;
- copy/export trace.

### 53.3 No global poisoning

Provider, Last.fm, lyrics, artwork, or history failure cannot pause or corrupt unrelated playback.

---

## 54. Position Model

Use an anchor:

```kotlin
data class PositionAnchor(
    val contentPositionMs: Long,
    val elapsedRealtimeMs: Long,
    val playbackSpeed: Float,
    val advancing: Boolean,
)
```

Current position is derived, bounded to duration.

### 54.1 UI sampling

- Now Playing visible: 100–250 ms.
- Mini-player: 500–1,000 ms or no progress if not shown.
- Widget/notification: event-based or lower frequency.
- No app-wide 100 ms `StateFlow` updates.

### 54.2 Pause accuracy

On pause/seek/discontinuity, commit a fresh anchor from Media3 observation.

---

## 55. Checkpoint and Restore

### 55.1 What is persisted

- queue-entry identities and recording IDs;
- base/traversal order;
- current entry ID;
- position;
- repeat/shuffle;
- play intent;
- origin/contributor;
- minimal presentation fallback;
- checksum/version.

### 55.2 What is not persisted

- expiring URLs;
- request headers;
- cache leases;
- Crew temporary file paths beyond valid session manifest;
- Media3 numeric index as sole identity.

### 55.3 Restore

1. validate checkpoint checksum/version;
2. resolve recording redirects;
3. remove entries whose durable identity is irrecoverable;
4. preserve current entry where possible;
5. compact orders by entry ID;
6. resolve current source;
7. create engine window;
8. commit Media3 transaction;
9. publish restored state;
10. resume only if policy/user intent permits.

### 55.4 Process-death tests

Must cover:

- local;
- provider;
- downloaded;
- mixed queue;
- shuffle;
- duplicate recording occurrences;
- removed file;
- expired provider URL;
- unavailable provider;
- Crew checkpoint compatibility.

---

## 56. System Surfaces

Notification, lock screen, media buttons, widgets, Android Auto, Quick Settings, and Bluetooth all:

- dispatch through `PlaybackCommandRouter`;
- render from `PlaybackSnapshot`;
- use `QueueEntryId` and `RecordingId`;
- never read `musikr.Song`;
- never maintain an independent queue;
- survive UI process surface recreation.

Android Auto browse may expose local/library read models, but selected rows enter the same coordinator.

---

## 57. Crossfade and Gapless

Crossfade is an engine effect, not queue authority.

Requirements:

- next source prepared before transition;
- source failure cancels crossfade without changing identity;
- gapless used where formats support it;
- crossfade disabled for unsupported content/route/setting;
- Crew synchronization can disable or constrain local crossfade if it would violate shared timing;
- tests cover transition and current-entry commit order.

---

## 58. Playback API Compatibility Migration

During migration:

1. introduce `PlaybackCoordinator` alongside legacy manager;
2. adapt legacy local commands into coordinator;
3. migrate Now Playing and mini-player;
4. migrate MediaSession/system surfaces;
5. migrate queue UI;
6. migrate Last.fm/history/lyrics;
7. migrate Crew command routing;
8. remove legacy `Song` callbacks;
9. delete `PlaybackStateManager` only after no production import remains.

A dual-state bridge may exist temporarily but has a deadline and one-way authority:

```text
new coordinator → legacy adapter
```

Never:

```text
legacy ↔ new mutual synchronization
```


# Part IX — Last.fm, Lyrics, Recommendations, and History

## 59. Last.fm Role

Last.fm is:

- a scrobble destination;
- an optional listening-history/profile integration;
- a source of top/recent listening;
- a discovery graph through similar tracks/artists;
- a metadata/identity hint.

Last.fm is not:

- a playable audio provider;
- the canonical Shippy library;
- the local playback authority;
- a reason to block or delay playback;
- visible throughout the app when disconnected.

### 59.1 Disconnected behavior

When no valid Last.fm account exists:

- no Last.fm Home sections;
- no scrobble status icon;
- no Last.fm requests;
- no empty recommendation card;
- no repeated login prompts;
- only the Settings connection entry is visible.

### 59.2 Connected behavior

When connected:

- Now Playing updates;
- eligible scrobbles;
- subtle current-session status;
- recent/top profile data;
- Last.fm-derived discovery;
- clear reauthentication state;
- disconnect/data-clear controls.

---

## 60. Last.fm Scrobble Pipeline

```text
PlaybackSnapshot + ListeningSessionTracker
                   ↓
          canonical recording metadata
                   ↓
             eligibility policy
                   ↓
          durable Last.fm outbox
                   ↓
          signed batch API delivery
                   ↓
       accepted / ignored / retry / reauth
```

### 60.1 Eligibility

Follow Last.fm rules:

- recording duration > 30 seconds;
- active audible playback reaches half the duration or four minutes, whichever is earlier.

Use accumulated audible time, not position jumps.

### 60.2 Metadata

Required canonical fields:

- artist;
- track title;
- start timestamp.

Optional:

- album;
- duration;
- MusicBrainz recording ID where supported;
- chosenByUser.

If artist/title remain unusable or filename-derived only, do not submit garbage. The connected UI may expose “Identify to scrobble” in the song detail, not as an intrusive playback error.

### 60.3 Now Playing

Send once per listening session after audible playback starts. It is informational and does not count as a scrobble.

### 60.4 Durable outbox

- FIFO;
- up to 50 entries per API batch;
- ASCII-correct signature ordering;
- deterministic IDs derived from listening session;
- network/service failures retained;
- invalid session triggers reauth;
- terminal invalid entries recorded/removed with reason;
- duplicates prevented by unique listening-session reference.

### 60.5 Network recovery

A process-scoped network observer or WorkManager constraint may trigger flush. Avoid registering duplicate callbacks.

### 60.6 Status model

```kotlin
enum class ScrobbleStatus {
    HIDDEN,
    ELIGIBLE,
    NOW_PLAYING_SENT,
    WAITING_FOR_THRESHOLD,
    QUEUED,
    DELIVERING,
    SCROBBLED,
    IGNORED,
    RETRYING,
    REAUTH_REQUIRED,
    NOT_IDENTIFIABLE,
}
```

Only expose status when connected and relevant.

### 60.7 Correct identity

Status is keyed by `ListeningSessionId` and `QueueEntryId`, never a queue index.

---

## 61. Last.fm Discovery

The public API exposes recent/top listening and similar tracks, not a guaranteed turnkey personalized recommendation feed.

R16 discovery can derive bounded recommendations:

1. select recent/top seed recordings;
2. call `track.getSimilar` for a small rotating subset;
3. remove already-overplayed/recent duplicates;
4. map Last.fm identity hints into Shippy search candidates;
5. resolve existing canonical recordings first;
6. search enabled providers lazily for play;
7. cache discovery results for a day or measured TTL;
8. show at most a compact row before “See all.”

### 61.1 Artwork

Prefer Shippy canonical/provider artwork. Last.fm image fields may be missing or poor quality.

### 61.2 Failure

If discovery fails:

- keep cached non-stale-enough results;
- hide the row when no useful results exist;
- do not show a full-screen Last.fm error.

---

## 62. Lyrics Architecture

Lyrics are tied to `RecordingId`, while playback highlighting is tied to `QueueEntryId`.

### 62.1 Source chain

Recommended:

1. exact valid local/embedded lyrics;
2. fresh canonical cache;
3. configured Musixmatch broker;
4. LRCLIB exact lookup;
5. conservative search fallback;
6. stale cache when network unavailable.

### 62.2 Request identity

```kotlin
data class LyricsRequest(
    val recordingId: RecordingId,
    val metadataFingerprint: String,
    val title: String,
    val artists: List<String>,
    val release: String?,
    val durationMs: Long?,
)
```

### 62.3 Stale result protection

Every UI request also carries current `QueueEntryId` and generation. A slow response for the previous song cannot overwrite the current lyrics.

### 62.4 Cache validation

A cache hit is valid only if:

- recording ID resolves;
- metadata fingerprint still matches or the entry is explicitly approved;
- source payload parses;
- synced timestamps are sane for duration.

### 62.5 Presentation

- stable bounded preview height;
- no layout movement when current line wraps;
- full lyrics sheet/page with its own scrolling;
- active line large and clear;
- inactive lines matte but readable;
- edge fade;
- loading/none/instrumental/error/retry states;
- album-derived tint with contrast guarantees;
- reduced-motion support.

---

# Part X — Crew Integration with the R16 Core

## 63. Crew Identity Payload

Crew must transmit recording intent, not device-private database assumptions.

```kotlin
data class PortableRecordingDescriptor(
    val portableId: PortableRecordingId,
    val title: String,
    val artistCredit: List<PortableArtistCredit>,
    val durationMs: Long?,
    val version: PortableRecordingVersion,
    val externalIdentifiers: Set<PortableExternalIdentifier>,
    val sourceHints: List<PortableSourceHint>,
    val artworkHint: PortableArtworkHint?,
)
```

Each device maps the descriptor to a local `RecordingId` through exact source/identifier lookup or creates a session-durable recording.

Queue entry IDs are shared session occurrence identities.

### 63.1 Privacy

Do not broadcast:

- local filesystem paths;
- permanent credentials;
- expiring provider request headers;
- private user metadata not needed for playback;
- unrestricted device addresses.

---

## 64. Crew Command Flow

```text
local user action
      ↓
PlaybackCommandRouter detects active Crew
      ↓
Crew action request
      ↓
ordered canonical Crew event
      ↓
all members receive Crew state
      ↓
each device projects target QueueEntryId into local PlaybackCoordinator
      ↓
each device resolves its own source
```

A local Media3 callback is telemetry, not collaborative intent.

### 64.1 System controls

Headset/notification/Bluetooth controls during Crew must route through Crew command handling rather than mutate local playback first and infer intent later.

### 64.2 Coordinator

Coordinator orders events but is not a privileged user role. Existing term/sequence logic is retained where valid.

---

## 65. Crew Source Resolution

Each member follows:

1. session temporary verified asset;
2. permanent download;
3. linked local asset;
4. complete cache;
5. provider sources;
6. Crew peer;
7. unavailable.

Local/private sources are overlaid only on that device unless deliberately offered through Push & Pull.

### 65.1 Unresolved entries

Canonical Crew queue entries remain visible even when a device cannot yet resolve them.

Local engine window may wait/buffer on the same queue entry. It must not silently shrink the shared queue.

### 65.2 Push & Pull

Crew temporary media becomes a session-scoped `MediaAsset`.

- bounded;
- resumable;
- encrypted/authenticated;
- lease-protected;
- verified;
- deleted on session cleanup unless explicitly downloaded;
- promotable through the normal download state machine.

---

## 66. Crew Bridge Refactor

Current `CrewPlaybackBridge.kt` is approximately 795 lines and combines command interception, state projection, source preparation, drift, queue replacement, and lifecycle.

Split into:

```text
CrewCommandRouter
CrewPlaybackProjector
CrewSourceOverlay
CrewDriftController
CrewPlaybackTelemetry
CrewQueueMapper
```

### 66.1 Keep

- pure reducer;
- ordered event log;
- coordinator term/sequence;
- deterministic snapshots;
- bounded media protocol;
- transport separation;
- active runtime process ownership.

### 66.2 Replace

- `PlaybackMutationInterceptor`;
- legacy manager listener coupling;
- index-based player reconciliation;
- dropping unresolved queue items from local projection;
- any source/private URI leakage in shared state.

### 66.3 Crew test gate

No Crew bridge migration is complete until two simulated clients demonstrate:

- duplicate recording occurrences;
- simultaneous queue edits;
- shuffle;
- provider failure on one member;
- local-only track contribution;
- process reconnect;
- coordinator loss;
- out-of-order/duplicate events;
- drift correction without user-intent feedback loops.

---

# Part XI — UI State Architecture

## 67. Views/Fragments Decision

R16 keeps the existing Android Views/Fragments foundation.

Reasons:

- the existing UI and playback choreography are substantial;
- user likes the current Material foundation;
- a Compose rewrite would combine architectural, visual, navigation, and runtime risk;
- Material Components 1.14.0 provides stable Material 3 Expressive Views support;
- R16 core/data/playback boundaries can make a later UI migration optional rather than mandatory.

Material Components for Views entered maintenance mode after Google’s 2026 Compose-first announcement. Therefore:

- upgrade to stable `1.14.0`;
- avoid relying on future Views-only feature work;
- isolate UI from domain/data so future Compose adoption is feasible;
- do not start a partial uncontrolled Compose migration inside R16.

---

## 68. UI Unidirectional Flow

Every screen follows:

```text
Repository / playback source of truth
              ↓
        Screen ViewModel
              ↓
     immutable ScreenUiState
              ↓
         Fragment/View
              ↓
         user action
              ↓
      ViewModel/use case command
```

The Fragment:

- renders;
- owns view-only animation/gesture state;
- dispatches actions;
- does not join databases;
- does not resolve providers;
- does not maintain playback truth;
- does not call Media3 directly.

### 68.1 Screen state

Use explicit sealed loading/error/content states rather than nullable field combinations.

### 68.2 One-off effects

Navigation, permission launch, share sheet, and snackbars use lifecycle-safe effect streams. They are not represented as permanent state and not lost during rotation where important.

### 68.3 Collection

Use `repeatOnLifecycle(STARTED)` and avoid multiple accidental collectors.

---

## 69. UI Read Models

Do not pass domain graphs directly into adapters.

Examples:

```kotlin
data class SongRowUi(
    val recordingId: RecordingId,
    val title: String,
    val subtitle: String,
    val artwork: ArtworkUi,
    val durationText: String?,
    val liked: Boolean,
    val offline: Boolean,
    val playing: PlayingIndicator?,
)

data class PlaylistEntryRowUi(
    val playlistEntryId: PlaylistEntryId,
    val recordingId: RecordingId,
    ...
)
```

Stable adapter IDs:

- recording-based rows: RecordingId if unique in list;
- playlist rows: PlaylistEntryId;
- queue rows: QueueEntryId;
- source rows: SourceReferenceId.

---

## 70. Main Shell

Refactor the 961-line `MainFragment.kt` into:

```text
MainShellFragment
PrimaryNavigationController
PlayerSurfaceController
InsetsController
BackHierarchyController
GlobalDestinationRouter
```

### 70.1 Back hierarchy

1. close open sheet/dialog/menu;
2. close selection/search mode;
3. collapse full Now Playing;
4. navigate destination stack;
5. exit root.

### 70.2 Player surface

Mini-player remains attached above primary navigation while a playback session exists. It expands/collapses without creating a second PlaybackViewModel authority.

### 70.3 Navigation state

Primary destinations:

- Home
- Search
- Library
- Crew

Destination restoration must preserve scroll/search state within memory limits.

---

# Part XII — Product UX Specification

## 71. Experience Character

Shippy should feel:

- native;
- calm;
- immediate;
- music-first;
- spacious without wasting room;
- expressive where state or hierarchy benefits;
- source-agnostic by default;
- powerful without exposing plumbing.

The UI should not visually explain the architecture. The architecture exists so the UI does not have to.

---

## 72. Home

### 72.1 Structure

Recommended order:

1. app bar/profile/settings;
2. Continue Listening/current context;
3. Recently Played horizontal row;
4. pinned Library shortcuts;
5. Last.fm discovery when connected;
6. Crew context when active/recent;
7. optional provider/editorial section only if it has real value.

### 72.2 Continue Listening

- hidden when no resumable/current context exists;
- play affordance starts playback, not merely navigation;
- current title/artwork keyed to QueueEntryId;
- loading state disables repeat tap;
- tapping body may open context; tapping Play starts;
- never shows stale item after queue replacement.

### 72.3 Recently Played

- horizontal;
- bounded item count;
- deduped presentation;
- progressive artwork;
- “See all” for history;
- no full vertical list on Home.

### 72.4 Last.fm

Only when connected and useful. Use clear neutral heading such as “Recommended from your listening.”

### 72.5 Empty state

If no local/library/history exists:

- primary action to choose music folders;
- global search action;
- optional Last.fm connect in Settings, not a nag card.

---

## 73. Global Search

### 73.1 Search field

- clear 48 dp+ touch target;
- obvious placeholder;
- one-tap clear;
- loading indication that does not resize the field;
- recent searches before query;
- back closes keyboard/search mode before leaving.

### 73.2 Results

- On this device / Library first;
- enabled provider sections;
- tracks, albums, artists, playlists clearly grouped;
- source/provider label secondary and restrained;
- progressive section arrival;
- partial failure rows with retry;
- no blank whole screen because one provider failed.

### 73.3 Actions

Track tap:

- immediately dispatches Play;
- retains exact source hit;
- creates/links recording as needed;
- shows buffering state in mini-player.

Long press/overflow:

- play next;
- add to queue;
- save;
- add to playlist;
- download if eligible;
- go to album/artist;
- share;
- song information.

---

## 74. Library

### 74.1 Header

- title;
- contextual Search;
- sort/filter bottom sheet;
- grid/list toggle where supported;
- create action in playlist context;
- profile/settings remains shell-owned.

### 74.2 Tabs

Recommended default:

- Songs
- Playlists
- Albums
- Artists
- Genres

Borrow Samsung Music’s useful principle: allow the user to hide/reorder non-essential tabs in Settings while keeping core tabs available.

### 74.3 Songs

- paged canonical recordings;
- no duplicate source rows;
- local/download/liked state expressed through subtle icons;
- source hidden unless needed;
- batch selection;
- contextual sort.

### 74.4 Playlists

- system collections and user playlists;
- pinned first;
- predictable custom order;
- long press opens action sheet;
- explicit “Edit order” mode exists only here;
- create button belongs here, not every Library tab.

### 74.5 Albums and Artists

Use canonical Release/Artist read models. Do not rebuild from provider/local objects inside Fragment code.

### 74.6 Navigation performance

Switching to Library must render cached/paged data quickly and preserve tab/scroll state. Indexing progress is a small state element, not a full reload.

---

## 75. Collection Detail

### 75.1 Layout

- artwork-led header;
- title and contextual metadata;
- Play and Shuffle primary actions;
- optional Download missing items;
- Search/sort control;
- paged full-width track list;
- stable mini-player inset.

### 75.2 Search

Pull-down reveal or explicit search action may be used. Search remains in collection.

### 75.3 Sort

Bottom sheet:

- Custom/playlist order;
- recently/oldest added;
- title;
- artist;
- album;
- duration.

Current selection is visible and persisted.

### 75.4 Multi-select

Long press a track enters selection mode.

Actions depend on context:

- add to playlist;
- remove from current playlist;
- like/unlike;
- download/remove download;
- delete local files with consent;
- clear cache;
- identify;
- share.

### 75.5 Playlist long press

Bottom sheet:

- pin/unpin;
- play;
- shuffle;
- rename;
- edit artwork;
- edit order;
- export/share;
- delete.

System collections omit unsupported destructive actions.

---

## 76. Save and Like Behavior

### 76.1 Primary control

Default state: plus in a clear circular/icon-button treatment.  
Liked state: filled heart using a consistent semantic liked color.

### 76.2 Interaction

Recommended:

- first tap on plus: save to Liked immediately and animate/acknowledge;
- tapping filled heart may unlike with undo, or open saved-destinations based on final product choice;
- long press or adjacent action opens destination sheet;
- destination sheet shows current playlist membership and create playlist;
- all changes execute in one transaction.

### 76.3 Guarding

Sheet is keyed to current QueueEntryId/RecordingId. If playback changes while open, either:

- keep editing the originally selected recording with a clear header; or
- close safely.

Never apply changes to whichever song happens to be current at submit time.

---

## 77. Now Playing

### 77.1 Hierarchy

1. collapse/navigation affordance and context;
2. artwork;
3. title/artist/release;
4. save;
5. progress/seek;
6. shuffle/previous/play-next/repeat;
7. utility actions such as download, queue, sleep timer;
8. lyrics preview;
9. secondary overflow.

### 77.2 Layout rules

- no unrelated fixed heights;
- use measured artwork bounds and resource dimensions;
- short/tall screens have deliberately tested variants;
- controls never overlap system insets;
- lyrics never push essential controls off-screen;
- dynamic text does not clip;
- rotation/resize preserves current queue entry and sensible scroll.

### 77.3 Album-derived theming

Use a bounded asynchronous palette pipeline:

```text
artwork content hash
        ↓
palette extraction/cache
        ↓
contrast-safe tonal role mapping
        ↓
background/surface/accent tokens
```

Guidance:

- restrained tonal gradient or surface tint;
- no mandatory blur;
- no uncontrolled saturated background;
- preserve text/control contrast;
- animate palette changes only after current QueueEntryId commit;
- respect reduced motion;
- cache palette by artwork hash, not queue index.

### 77.4 State identity

All title, artwork, actions, lyrics, Last.fm status, and source details are projections of the current QueueEntryId.

### 77.5 Player gesture

Swipe/tap expansion and collapse must cooperate with lyrics scrolling. Nested content gets priority until it reaches its boundary, then the player can collapse.

---

## 78. Queue

- displays actual traversal order;
- highlights exact QueueEntryId;
- supports drag reorder by entry ID;
- supports swipe/remove and multi-select where appropriate;
- shows contributor during Crew;
- preserves duplicate recordings;
- updates without remapping the full queue into expensive artwork objects;
- loads presentation in pages/chunks around visible items;
- can optionally show the remaining base context when shuffle is active.

---

## 79. Lyrics

### 79.1 Preview

- stable viewport;
- current line and a bounded number of adjacent lines;
- tap opens full lyrics;
- retry and instrumental states;
- no height changes per lyric line count.

### 79.2 Full lyrics

- large active line;
- matte inactive lines;
- smooth synchronized scroll;
- manual scrolling does not fight auto-scroll;
- return-to-current affordance;
- edge fade;
- share where licensing permits;
- download/queue actions remain consistent with player hierarchy.

---

## 80. Sheets, Drawers, Flyouts, and Menus

### 80.1 Default mobile rule

Use a modal bottom sheet for contextual multi-action workflows:

- song actions;
- playlist actions;
- sort/filter;
- saved destinations;
- source details;
- cache/download options.

### 80.2 Anchored popup rule

Use an anchored menu only for:

- small, immediate, spatially related choices;
- no complex state;
- no scrolling form;
- no action hierarchy needing descriptions.

### 80.3 Navigation drawer

If the side drawer remains for profile/settings/about:

- use Shippy typography, spacing, iconography, surface tokens, and selection state;
- keep it a navigation surface, not a dumping ground for song actions;
- support predictive/back dismissal where practical;
- ensure light/dark contrast.

### 80.4 Reusable sheet host

Create a shared `ShippyBottomSheetDialogFragment`/style contract:

- consistent corner radius;
- drag handle;
- insets;
- max width on large screens;
- swipe dismissal;
- nested scrolling;
- accessibility title;
- action row components;
- loading/disabled states;
- result contract.

---

## 81. Settings

Recommended groups:

1. **Library**
   - music folders;
   - rescan;
   - manage tabs;
   - import/export playlists;
   - identify/cleanup library.

2. **Playback**
   - crossfade;
   - gapless;
   - ReplayGain;
   - equalizer;
   - repeat/pause preferences;
   - audio quality.

3. **Downloads and cache**
   - destination;
   - download quality/network;
   - cache size/age;
   - clear cache;
   - repair managed downloads.

4. **Providers**
   - enabled providers;
   - priority;
   - health;
   - metered behavior.

5. **Lyrics**
   - configured broker;
   - source order;
   - cache.

6. **Last.fm**
   - connect/account;
   - scrobble toggle;
   - status;
   - reconnect/disconnect;
   - discovery toggle.

7. **Crew**
   - profile;
   - Push & Pull;
   - relay;
   - privacy.

8. **Appearance**
   - theme;
   - dynamic color;
   - album-art player tint;
   - reduced motion.

9. **Advanced**
   - diagnostics export;
   - backup/restore;
   - database migration status;
   - clear transient catalogue;
   - developer tools in debug builds.

---

# Part XIII — Material 3 Expressive and Visual Polish

## 82. Material Dependency

Current source uses `com.google.android.material:material:1.14.0-alpha10`.

R16 should move to stable:

```gradle
implementation("com.google.android.material:material:1.14.0")
```

Material Components 1.14.0 includes stable Expressive themes, lists, buttons, icon buttons, button groups, navigation, search, progress, slider, and emphasized type scale support.

Run visual and behavioral regression tests because the stable release includes dependency and component changes.

---

## 83. Shippy Design Tokens

Rename first-party style tokens from `Theme.Auxio` / `Widget.Auxio` to Shippy equivalents.

Create deliberate token groups:

```text
Theme.Shippy
Theme.Shippy.Dialog
ThemeOverlay.Shippy.BottomSheet
Widget.Shippy.Button.*
Widget.Shippy.Player.*
Widget.Shippy.List.*
Widget.Shippy.Search.*
Shape.Shippy.*
Motion.Shippy.*
```

Do not perform a blind textual rename of styles without checking inheritance and resource references.

### 83.1 Shape

- consistent sheets/cards/buttons;
- larger expressive shapes reserved for primary surfaces;
- avoid every element becoming a pill;
- list rows remain dense enough for music browsing.

### 83.2 Typography

Use emphasized typescale where hierarchy benefits:

- Now Playing title;
- lyrics active line;
- collection title;
- primary empty-state call to action.

Do not increase typography at the cost of metadata density or clipping.

### 83.3 Motion

Use:

- contained transforms for player/sheet relationships where practical;
- spring motion for direct manipulation;
- short fades/tonal transitions;
- no decorative animation that blocks input;
- reduced-motion variants.

### 83.4 Dynamic color

Material You remains the base. Album palette is a local player overlay, not a replacement for the global theme.

### 83.5 Translucency

Use restrained surface alpha/scrims only when:

- readability remains guaranteed;
- GPU cost is measured;
- content underneath has meaningful continuity;
- older devices do not jank.

Avoid live blur as a beta requirement.

---

## 84. Spotify and Samsung Music Reference Principles

R16 uses patterns, not pixel cloning.

### 84.1 Spotify patterns worth borrowing

- Library-local search and filters;
- persistent sort/filter choices;
- pin/unpin through long press;
- list/grid choice;
- playlist-local search;
- Now Playing as expansion from persistent player;
- lyrics below/within Now Playing;
- reorderable queue;
- contextual sheets;
- adaptive tablet layout that reconfigures rather than merely scales.

### 84.2 Spotify patterns not automatically adopted

- service-specific catalogue assumptions;
- podcast/audiobook/video hierarchy;
- commercial recommendation surfaces;
- Premium restrictions;
- opaque source ownership;
- social features not in Shippy’s scope.

### 84.3 Samsung Music patterns worth borrowing

- calm local-first library;
- editable track/album metadata;
- editable cover art;
- configurable Library tabs;
- playlist import/export;
- clear separation of user files and player organization;
- predictable native controls.

### 84.4 Shippy synthesis

- Samsung Music for local ownership and cleanup;
- Spotify for discovery, contextual search, player/lyrics, and interaction hierarchy;
- Shippy for source normalization, open providers, downloads, cache, and Crew.

---

# Part XIV — Accessibility and Adaptive Layout

## 85. Accessibility Beta Gate

Required:

- TalkBack labels and roles;
- logical focus order;
- 48 dp minimum targets;
- dynamic type without clipping;
- contrast-safe album palette;
- state not communicated by color alone;
- accessible seek/slider actions;
- drag alternatives for reorder;
- reduced motion;
- content descriptions for artwork/actions;
- headings for sheets/screens;
- error announcements;
- keyboard/D-pad behavior on relevant surfaces.

### 85.1 Queue/playlist reorder alternative

Provide move up/down actions in accessibility mode or contextual menu.

### 85.2 Scrobble status

Do not constantly announce passive status changes. Expose in details/content description when focused.

---

## 86. Adaptive Layout

Phone is the primary R16 target. Tablet/large-screen behavior should still be deliberate.

### 86.1 Large screen

- parallel Library/detail or browse/Now Playing where space permits;
- max-width sheets;
- navigation rail/sidebar where existing architecture supports it;
- no stretched phone cards;
- artwork and lyrics use additional space rather than empty voids.

### 86.2 Orientation

Reconfigure constraints/resources; do not simply scale all controls.

### 86.3 Foldables/multi-window

At minimum:

- no clipping;
- state survives resize;
- player remains accessible;
- sheet max width;
- lists retain position.

---

# Part XV — Performance Architecture

## 87. Performance Is a Contract

Performance work is not postponed until after architecture because data access, identity, and playback preparation determine performance.

### 87.1 Core rule

```text
Cost must be proportional to the user-visible operation.
```

Examples:

- opening a 60-track playlist must not hydrate 20,000 recordings;
- playing one provider track must not resolve all providers;
- shuffling 10,000 entries must not build 10,000 artwork objects;
- changing one liked state must not refresh every Library tab;
- a Last.fm refresh must not block Home or playback.

---

## 88. Target Budgets

Measure on at least one representative mid-range device and one older supported device.

Suggested targets, subject to hardware baselines:

| Operation | Target |
|---|---:|
| Main-thread work per ordinary frame | under 8 ms preferred |
| Cached Library first content | ≤ 150 ms median |
| Tab switch to retained Library | ≤ 100 ms median |
| Local track tap to engine play intent | ≤ 50 ms |
| Verified local/cached audible start | ≤ 250 ms median |
| Warm provider cached start | ≤ 400 ms median |
| Shuffle 10,000 lightweight entries | ≤ 50 ms background/CPU, no long main block |
| Playlist DB page query | ≤ 50 ms typical |
| Search local first results | ≤ 100 ms after debounce |
| Large-list scroll | no sustained visible jank |
| Queue memory | proportional lightweight IDs, not full artwork/domain graphs |
| ANR | zero |
| Playback identity mismatches | zero |

Network cold-start time is variable; the UI must still acknowledge immediately and expose buffering.

### 88.1 Do not game metrics

A spinner appearing in 30 ms while the app spends six seconds doing unnecessary work is not success.

---

## 89. Database Performance

- indexes match query predicates/order;
- use PagingSource;
- avoid `observeAll`;
- use database views;
- batch ID queries;
- transactions for multi-row updates;
- FTS for search;
- EXPLAIN QUERY PLAN tests for critical queries;
- WAL mode/default Room behavior verified;
- no N+1 artwork/source lookups;
- no giant JSON decode on every row bind;
- no main-thread DAO.

### 89.1 Critical benchmark datasets

Generate deterministic databases with:

- 100 recordings;
- 10,000 recordings;
- 50,000 recordings;
- 1,000 playlists;
- a 10,000-entry playlist;
- duplicate recordings/assets;
- 20,000 play-history rows.

---

## 90. UI Rendering Performance

- RecyclerView stable IDs;
- DiffUtil/Paging adapters;
- partial payload updates;
- artwork requests cancelled on recycle;
- Coil memory/disk caches bounded;
- no palette extraction during bind;
- no full queue map for pager;
- no nested unbounded RecyclerViews;
- avoid layout variants with duplicated mistakes;
- use `ViewStub` or screen-specific includes for optional controls;
- measure overdraw and layout passes;
- no synchronous bitmap decode.

### 90.1 Player pager

Load current/adjacent artwork only. The queue screen is the place for the whole queue, and it is paged/chunked.

---

## 91. Provider and Network Performance

- provider concurrency cap, default 3;
- per-provider timeout;
- query cancellation;
- retry only retryable failures;
- circuit breaker after repeated failures;
- response size bounds;
- provider paging;
- metadata and stream resolution separate;
- never perform health stream resolution;
- cache bootstrap tokens/visitor data with TTL;
- no repeated source equivalence search after stored high-confidence link.

---

## 92. Playback Performance

- current source resolved first;
- bounded next window;
- complete cache used before network;
- engine window instead of whole rich queue;
- source refresh before URL expiry;
- no DB/network in Media3 callback;
- actor handles callbacks quickly;
- crossfade prep bounded;
- audio focus/system callbacks dispatch lightweight commands.

---

## 93. Background Work

- WorkManager for durable deferrable work;
- in-process coroutine work for immediate user operations;
- unique work keys;
- constraints;
- bounded batches;
- cancellation;
- backoff;
- no 3-hour “cleanup everything” job triggered on every startup;
- expose progress for explicit cleanup;
- quiet maintenance otherwise.

---

## 94. Baseline Profiles and Benchmarks

Add:

```text
:macrobenchmark
:baselineprofile
```

Scenarios:

- cold start;
- open Library;
- switch Library tabs;
- open 10,000-entry playlist;
- scroll songs;
- search local;
- tap local/cached/provider track;
- open/collapse Now Playing;
- open lyrics;
- open queue;
- reorder playlist/queue.

Ship a generated baseline profile with release builds after profile validation.

Use Perfetto/JankStats/Macrobenchmark output as evidence. `gfxinfo` alone is supplemental.

---

## 95. Debug Performance Guards

Debug builds may enable:

- StrictMode;
- main-thread disk/network detection;
- slow-query logging;
- state-transition trace;
- queue invariant checks;
- source-resolution timing;
- image/palette cache metrics;
- optional leak detection.

These diagnostics must not log credentials or private URLs.

---

# Part XVI — Reliability, Security, and Diagnostics

## 96. Failure Isolation

Subsystem boundaries:

- provider failure cannot stop local playback;
- Last.fm failure cannot stop playback/history;
- lyrics failure cannot mutate current item;
- artwork failure uses fallback;
- cache failure falls back;
- download failure leaves source playable;
- Crew route failure retains local app integrity;
- migration failure leaves legacy data intact.

---

## 97. Structured Errors

Every domain failure has:

- stable code;
- user-facing category;
- retryable flag;
- source/provider context;
- diagnostics detail;
- cause chain where safe.

Do not pass raw exception messages directly to primary UI.

---

## 98. Diagnostics Trace

Maintain bounded in-memory/local traces:

### 98.1 Playback trace

- generation;
- transaction ID;
- QueueEntryId;
- RecordingId;
- source/asset;
- command;
- engine commit;
- phase;
- position discontinuity;
- error/fallback;
- timestamp.

### 98.2 Identity trace

- subject source/asset;
- candidates;
- evidence;
- score;
- decision;
- user confirmation.

### 98.3 Migration trace

- phase;
- source counts;
- target counts;
- warnings;
- checksums.

Provide **Export diagnostics** with redaction.

---

## 99. Credential and Secret Storage

Use Android Keystore-backed encrypted atomic storage for:

- Last.fm API secret/session;
- Crew invite/rejoin secrets;
- relay credentials;
- future provider credentials.

Do not store in:

- generic SharedPreferences;
- Room in plaintext;
- logs;
- backups;
- source code.

API keys that must be distributed publicly are treated as public identifiers, not secrets; provider terms must be respected.

---

## 100. Privacy

- local files remain on-device unless user explicitly shares/uses Crew;
- Last.fm receives only scrobble metadata when connected;
- MusicBrainz/AcoustID queries are explicit/background according to settings;
- diagnostic export is user-triggered;
- no analytics dependency is required for R16;
- Crew media is active-session scoped;
- cache and temporary media are clearable.

---

## 101. Data Safety

Before destructive operations:

- clear labeling;
- distinguish Library removal from file deletion;
- Android consent where required;
- transaction;
- undo where possible;
- backup for migration;
- no wildcard file deletion;
- validate destination/ownership.


# Part XVII — R15.3 to R16 Data Migration

## 102. Migration Principles

1. Preserve before improving.
2. Do not fuzzy-dedupe during the first import.
3. Map old identities deterministically.
4. Import raw data even when incomplete.
5. Repair managed downloads by exact evidence.
6. Re-index local files through the new ingestion path.
7. Produce an audit report.
8. Allow retry.
9. Keep the old database untouched until R16 is verified.
10. Never label a destructive fallback as migration.

---

## 103. Deterministic Legacy ID Mapping

Use a fixed R16 UUID namespace.

```kotlin
fun legacyRecordingId(oldTrackId: String): RecordingId =
    RecordingId(uuidV5(R16_LEGACY_NAMESPACE, "track:$oldTrackId").toString())
```

Likewise for:

- playlist IDs;
- playlist entry IDs;
- source references;
- playback queue entries where old IDs exist.

This makes repeated migration idempotent.

---

## 104. Migration Phases

### 104.1 Phase M0 — Preflight

- verify legacy DB opens;
- export legacy schema/version;
- count each table;
- calculate database checksum;
- inspect selected local/download folders and permissions;
- create `ShippyBackupV1`;
- ensure sufficient free space;
- create migration audit row.

### 104.2 Phase M1 — Canonical track import

For each `canonical_track`:

- create one R16 Recording using deterministic ID;
- preserve title/artists/album/duration/version/artwork;
- create raw migration metadata observation;
- mark provenance as `LEGACY_R15_CANONICAL`;
- default retention based on relationships/assets.

Do not decide that two old provider TrackIds are the same during this phase.

### 104.3 Phase M2 — Candidate import

For each `canonical_track_candidate`:

- `PROVIDER` → SourceReference with exact provider/source item key;
- `LOCAL` → provisional local SourceReference and MediaAsset if location exists;
- `DOWNLOAD` → provisional managed asset only after download record verification;
- `CREW_TEMPORARY` / `CREW_PEER` → do not retain as permanent source;
- preserve technical metadata and raw locator separately;
- discard expired resolved URLs from durable identity.

### 104.4 Phase M3 — Library relationships

For each `library_relationship`:

- map old TrackId to RecordingId;
- preserve liked;
- preserve relationship durability;
- derive downloaded/local later from verified assets;
- set `firstAddedAt` to migration time if unavailable, with provenance.

### 104.5 Phase M4 — User playlists

For each `user_playlist`:

- create R16 Playlist;
- preserve name, pinned, order, artwork;
- normalize order keys with gaps.

For each `playlist_membership`:

- create one deterministic PlaylistEntry;
- preserve position;
- map TrackId;
- note that legacy schema could not represent duplicate recording occurrences.

### 104.6 Phase M5 — Device/Musikr playlists

After local asset ingestion maps Musikr UIDs:

- import user-visible device playlists into Shippy or expose them through a deliberate import adapter;
- preserve order;
- avoid duplicating a Shippy playlist already imported;
- record origin.

Recommended R16 behavior: import into Shippy-owned playlists so there is one playlist architecture. External playlist formats remain import/export sources, not coequal live models.

### 104.7 Phase M6 — Downloads

For each legacy download job:

- map track/source;
- preserve job history;
- verify final URI/access/length;
- if valid, create permanent MediaAsset;
- register exact managed asset identity;
- if file missing, mark job/asset unavailable;
- do not trust `AVAILABLE` state without file verification;
- do not create another local Recording during rescan.

### 104.8 Phase M7 — Lyrics

- map old TrackId to RecordingId;
- preserve payload/source/fingerprint;
- validate metadata fingerprint;
- mark stale where needed.

### 104.9 Phase M8 — Last.fm outbox

- preserve artist/title/album/timestamp;
- link to migrated RecordingId where old queue/track information exists;
- generate deterministic listening/outbox IDs;
- preserve FIFO;
- do not resend entries already terminal if known.

### 104.10 Phase M9 — Playback checkpoint

- map old track/queue IDs;
- preserve duplicate queue occurrences;
- preserve raw order/shuffle/current identity where consistent;
- discard expired resolved URLs;
- build R16 base/traversal order;
- write checksum;
- if inconsistent, skip only invalid entries and record warning.

### 104.11 Phase M10 — Saved provider entities

- preserve exact provider/entity/source item key;
- map to `saved_source_entity`;
- later enrichment may promote to Release/Artist source links.

### 104.12 Phase M11 — Crew

- preserve only version-compatible checkpoint/lease data;
- if protocol identity cannot map safely, expire active session with a clear migration note;
- user playlists/library must not be affected.

### 104.13 Phase M12 — Local re-index

Run the new local ingestion path:

1. managed asset registry;
2. exact legacy local identity;
3. raw metadata;
4. fingerprint only if needed;
5. link/create Recording.

### 104.14 Phase M13 — Verification

Compare:

- liked count;
- playlist count;
- playlist entry count;
- saved source entity count;
- valid download count;
- checkpoint queue length;
- Last.fm outbox count;
- unresolved legacy IDs;
- orphan foreign keys;
- redirect cycles;
- duplicate source keys;
- duplicate asset locations.

### 104.15 Phase M14 — Cutover

- set new DB active;
- run smoke reads;
- keep old DB and backup;
- record completed audit;
- do not delete legacy data in R16.

---

## 105. Migration UI

Migration may take time on a large library.

Required:

- clear one-time screen;
- progress by phase, not fake percentage alone;
- keep screen awake only when necessary;
- safe cancellation before cutover;
- retry;
- export failure report;
- low-storage warning;
- no claim that user files are being uploaded.

The app must not enter normal UI with a half-active mixed database.

---

## 106. Migration Tests

Fixtures:

- empty v10;
- ordinary local-only library;
- mixed providers;
- missing canonical metadata;
- 1,000 playlists;
- broken positions;
- download marked available but missing file;
- duplicate download/local bytes;
- Last.fm outbox;
- shuffled checkpoint;
- invalid checkpoint;
- revoked SAF permission;
- source item ID collision;
- corrupt row;
- process death during every phase;
- rerun after partial import;
- backup/restore round trip.

---

# Part XVIII — Naming, Application ID, and Legal Attribution

## 107. “Everything Is Shippy” Decision

R16 removes first-party Auxio product architecture names:

- packages;
- classes;
- resources;
- styles;
- strings;
- service names;
- public APIs;
- internal documentation.

Examples:

```text
AuxioService → ShippyPlaybackService
AuxioToolbar → ShippyToolbar or neutral Material component
Theme.Auxio → Theme.Shippy
PlaybackStateManager → PlaybackCoordinator
MusicRepository adapter → LocalMediaEngine/LocalLibraryGateway
```

### 107.1 Do not falsify history

Keep:

- GPL license;
- NOTICE;
- copyright headers;
- attribution to Auxio and other projects;
- third-party package names where code remains third-party;
- source-credit documentation.

Product ownership and legal provenance are different.

---

## 108. Application ID Decision Gate

Android private data cannot automatically move between unrelated application IDs.

Current:

```text
namespace/applicationId: org.oxycblt.auxio
debug: org.oxycblt.auxio.debug
```

R16 must choose before implementation branches diverge.

### Option A — Keep applicationId for upgrade compatibility

- rename internal namespace/code to Shippy;
- keep legacy installed package ID as compatibility artifact;
- user data upgrades in place;
- system Settings may still expose old technical package;
- final brand ID remains deferred.

### Option B — Adopt final Shippy applicationId before beta

- choose final domain-backed ID;
- ship an R15.3 bridge release that exports `ShippyBackupV1`;
- install R16 as the final package;
- import backup through user-selected file;
- app becomes cleanly branded before public beta;
- alpha installs do not update in place.

### Recommendation

Because R16 is the first intended beta and the owner prioritizes long-term quality over migration convenience, **choose the final application ID now if a stable domain/identifier can be committed**. This is the least bad time to make the break.

Do not invent a permanent reverse-domain ID merely to unblock code. Record the owner decision as ADR-001.

### 108.1 Namespace and application ID can differ

Even if Option A is selected temporarily, use a Shippy internal namespace. Keep application ID explicitly assigned rather than deriving it from namespace.

---

## 109. Authorities, Deep Links, and Signing

Any rename must update/test:

- ContentProvider authorities;
- FileProvider;
- CoverProvider;
- MediaSession/service;
- shortcuts;
- widgets;
- Android Auto;
- Tasker integration;
- deep links;
- Crew invite links;
- notification channels;
- backup rules;
- release signing;
- ProGuard/R8;
- manifest placeholders;
- tests;
- app links/domain verification.

No mechanical package rename is complete until installed release behavior passes.

---

# Part XIX — Current File and Function Disposition

## 110. Disposition Legend

- **KEEP** — retain implementation with naming/boundary cleanup.
- **HARDEN** — keep concept, strengthen correctness/tests.
- **REFACTOR** — significant internal change, same responsibility.
- **REPLACE** — new architecture supersedes it.
- **ADAPTER** — temporarily wraps legacy code behind new boundary.
- **DELETE LATER** — remove only after migration.
- **REVIEW** — retain only after focused inspection.

---

## 111. Domain and Identity Files

| Current file | Disposition | R16 action |
|---|---|---|
| `shippy/domain/Track.kt` | REPLACE | Split into Recording, SourceReference, MediaAsset, QueueEntry, release/artist models. |
| `shippy/domain/Identity.kt` | REPLACE | Introduce complete typed ID set; provider IDs stop being recording IDs. |
| `shippy/domain/QueueItemFactory.kt` | REPLACE | `QueueEntryFactory` accepts RecordingId/origin and creates occurrence identity without embedding full Track graph. |
| `shippy/domain/PlaybackResolver.kt` | REFACTOR | Become pure source-selection policy over assets/source summaries. |
| `shippy/domain/PlaybackResolutionCoordinator.kt` | REPLACE | `PlaybackSourcePreparer` + source/asset repositories + tagged preparation results. |
| `shippy/domain/LocalTrackCandidateMapper.kt` | ADAPTER | Move under `shippy-sources/local`; output local observation/asset, not canonical Track. |
| `shippy/domain/LocalCandidateResolver.kt` | REPLACE | Asset registry/local engine lookup keyed MediaAssetId. |
| `shippy/domain/LibraryCollection.kt` | REFACTOR | Align system targets, playlists, releases, artists, layout entries. |
| `shippy/media/MediaObjectKey.kt` | HARDEN | Stable source+variant cache key; rename and test collision/version rules. |

---

## 112. Provider Files

| Current file | Disposition | R16 action |
|---|---|---|
| `shippy/provider/MusicProvider.kt` | REFACTOR | Providers return source observations, not canonical Track. |
| `shippy/provider/ProviderRegistry.kt` | HARDEN | Capability/health/priority; add circuit breaker and TTL. |
| `provider/jiosaavn/JioSaavnProvider.kt` | REFACTOR | Preserve API parsing; map to SourceTrackObservation and external IDs where available. |
| `provider/youtube/YouTubeProvider.kt` | REFACTOR | Preserve extraction; source key/video ID; no TrackId minting. |
| `provider/youtube/YouTubeMusicProvider.kt` | REFACTOR | Share source-family identity logic without pretending provider ID is recording ID. |
| `UnifiedSearchRepository.kt` | REPLACE | `GlobalSearchCoordinator` with local Paging/FTS + progressive provider sections + generations. |
| provider DTO/helper tests | KEEP/HARDEN | Retain fixtures, add source-observation and cancellation tests. |

### 112.1 Function-level provider change

Current patterns such as:

```kotlin
private fun toTrack(item: ProviderItem): Track
```

become:

```kotlin
private fun toObservation(item: ProviderItem): SourceTrackObservation
```

The normalization layer performs:

```kotlin
recordingIngestor.ensureRecording(observation)
```

---

## 113. Database and Library Files

| Current file | Disposition | R16 action |
|---|---|---|
| `persistence/library/ShippyDatabase.kt` | KEEP READ-ONLY / NEW V2 | Legacy importer reads v10; create R16 database separately. |
| `CanonicalTrackMetadataRepository.kt` | REPLACE | Recording/source/metadata/provenance repositories. |
| `LibraryRelationshipDao.kt` | REPLACE | New Library, Playlist, read-model DAOs. |
| `LibraryRelationshipRepository.kt` | REPLACE | Recording-level transactions and paged queries. |
| `SavedProviderEntityRepository.kt` | REFACTOR | Saved source entities; promotion path to canonical entities. |
| `LibraryCollectionLayoutStore.kt` | REPLACE | Persist layout in R16 DB; remove duplicate preference authority. |
| `PlaylistTrackOrder.kt` | REFACTOR | PlaylistEntryId + sparse order keys. |
| Room schema exports | KEEP | Export every R16 schema and migration fixture. |

### 113.1 Remove full-catalogue observation

Delete production usage of:

```text
metadata.observeAll()
downloads.observeAll()
```

for playlist detail. Replace with scoped SQL/read views/PagingSource.

---

## 114. Playback Files

| Current file | Disposition | R16 action |
|---|---|---|
| `playback/state/PlaybackStateManager.kt` | ADAPTER → DELETE | New PlaybackCoordinator; one-way bridge during migration. |
| `playback/service/ExoPlaybackStateHolder.kt` | SPLIT/REPLACE | PlayerEngine adapter and focused controllers. |
| `shippy/playback/ShippyPlaybackController.kt` | REPLACE | PlaybackCommandRouter/Coordinator API. |
| `shippy/playback/ProviderPlaybackLifecycle.kt` | REFACTOR | Keep bounded prep; move to SourcePreparationWindow. |
| `playback/PlaybackViewModel.kt` | REWRITE | Screen projection only; no queue mapping or lyrics repository orchestration. |
| `playback/PlaybackDisplayItem.kt` | DELETE LATER | Replace with recording/queue UI projections. |
| `playback/queue/QueueViewModel.kt` | REWRITE | QueueEntryId and paged/lightweight rows. |
| `playback/persist/PersistenceRepository.kt` | REPLACE | R16 checkpoint repository. |
| `shippy/playback/CanonicalPlaybackRestoreCoordinator.kt` | REFACTOR | R16 checkpoint/source resolution. |
| `playback/service/MediaSessionHolder.kt` | HARDEN | Consume snapshot and route commands. |
| `playback/service/PlaybackServiceFragment.kt` | REFACTOR | ShippyPlaybackService composition root. |
| `WidgetComponent.kt` | REFACTOR | Snapshot/command router only. |
| `BetterShuffleOrder` or equivalent | DELETE | Shippy traversal order is canonical. |

### 114.1 `PlaybackViewModel` split

Current ~900-line class responsibilities become:

```text
NowPlayingViewModel
PlayerPositionSampler
LyricsViewModel or NowPlayingLyricsInteractor
QueueViewModel
PlayerPanelEffects
```

All read from coordinator/repositories.

### 114.2 `ExoPlaybackStateHolder` split

Do not move 1,191 lines into a renamed 1,191-line file. Extract according to Section 51.

---

## 115. Last.fm and History Files

| Current file | Disposition | R16 action |
|---|---|---|
| `LastFmScrobbleTracker.kt` | REPLACE | ListeningSessionTracker observer + LastFmScrobbleCoordinator. |
| `LastFmListenPolicy.kt` | KEEP/HARDEN | Pure threshold policy consumes accumulated audible time. |
| `LastFmClient.kt` | HARDEN | Accepted/ignored parsing, batch signature, typed results, fake server tests. |
| Last.fm credential repository | KEEP/HARDEN | Keystore/atomic storage, lifecycle tests. |
| `RecentListeningTracker.kt` | REPLACE | Unified PlayHistoryCoordinator fed by listening sessions. |
| Last.fm Home models | REFACTOR | Hidden disconnected; canonical result resolution and artwork. |

---

## 116. Lyrics Files

| Current file | Disposition | R16 action |
|---|---|---|
| `LyricsRepository` and source chain | HARDEN | Key by RecordingId/metadata fingerprint. |
| lyrics cache DAO/entity | MIGRATE | R16 key and provenance. |
| `PlaybackViewModel` lyrics jobs | MOVE | Dedicated interactor/ViewModel keyed generation/QueueEntryId. |
| lyrics dialog/fragment | REFACTOR | Stable state, palette tokens, accessibility, no player-state ownership. |

---

## 117. Library UI Files

| Current file | Disposition | R16 action |
|---|---|---|
| `home/list/PlaylistListFragment.kt` | REPLACE | Dedicated R16 Library playlists screen; no mixed legacy/device model. |
| `LibraryCollectionsViewModel.kt` | REWRITE | Paged Library target/read model and transactional layout. |
| `ShippyCollectionDetailViewModel.kt` | REWRITE | Scoped PagingSource; no observeAll joins. |
| `ShippyCollectionDetailFragment.kt` | REFACTOR | Screen-owned toolbar/search/sort/selection. |
| `LibraryCollectionAdapters.kt` | REFACTOR | Paging/Diff stable entry IDs. |
| generic `fragment_home_list.xml` | REFACTOR | Neutral list shell only; remove playlist-specific controls. |
| local Songs/Albums/Artists fragments | ADAPTER → REPLACE | New canonical read models, no direct Musikr UI domain. |
| selection infrastructure | KEEP/HARDEN | Generalize IDs/actions, test context. |

---

## 118. Search Files

| Current file | Disposition | R16 action |
|---|---|---|
| `search/SearchViewModel.kt` | REWRITE | Global Search state/actions; local scope separate. |
| `UnifiedSearchRepository.kt` | REPLACE | Progressive search coordinator, exact dedupe, provider paging. |
| legacy SearchEngine | ADAPTER/REVIEW | Keep useful text-ranking helpers, but database FTS is authoritative for large data. |
| search fragments/adapters | REFACTOR | section states, partial failure, stable IDs, cancellation. |

---

## 119. Download and Cache Files

| Current file | Disposition | R16 action |
|---|---|---|
| `ShippyDownloadWorker.kt` | REFACTOR | Recording/source/asset IDs; publish through registry. |
| `DownloadWorkCoordinator.kt` | REFACTOR | command API, unique jobs, cache promotion. |
| `DownloadDestinationReconciler.kt` | REPLACE | ManagedAssetRegistry + exact verification + repair. |
| download persistence | MIGRATE | normalized jobs/assets. |
| `PlaybackCacheManager.kt` | HARDEN | stable key, singleton, size/age metrics. |
| cache promotion helpers | HARDEN | complete verification and atomic publication. |
| filesystem factory combining download folder | REFACTOR | scanner asks registry before ingestion. |

---

## 120. Crew Files

| Current area | Disposition | R16 action |
|---|---|---|
| pure Crew reducer/protocol/session | KEEP/HARDEN | Canonical portable descriptor and invariant tests. |
| `CrewPlaybackBridge.kt` | REPLACE | split routing/projector/drift/telemetry. |
| active runtime | HARDEN | retain process ownership, adopt R16 IDs. |
| Crew temporary media | REFACTOR | `MediaAssetKind.CREW_TEMPORARY`. |
| transport | KEEP/HARDEN | preserve boundaries/security, update payload schema. |
| checkpoint | MIGRATE/COMPAT | versioned mapping; expire incompatible sessions safely. |

---

## 121. UI and Resource Files

| Current area | Disposition | R16 action |
|---|---|---|
| `MainFragment.kt` | SPLIT | shell/navigation/player/insets/back controllers. |
| `PlaybackPanelFragment.kt` | REFACTOR | new state, smaller responsibilities, responsive layouts. |
| `fragment_playback_panel` variants | REBUILD CAREFULLY | shared sections/dimensions; fix 304 dp defect. |
| `Theme.Auxio` / `Widget.Auxio` | RENAME/HARDEN | Shippy tokens, stable M3E. |
| drawers/sheets | STANDARDIZE | shared bottom sheet host/style. |
| light-theme icon selectors | AUDIT | theme-aware colors and contrast tests. |
| current branded resources | RENAME | preserve source/legal files separately. |

---

## 122. Build and Dependency Files

### 122.1 Update

- Material `1.14.0-alpha10` → stable `1.14.0`.
- Add Paging 3.
- Add benchmark/baseline profile tooling.
- Add Room migration/read model tests.
- Consider modern RecyclerView/ViewPager only after reproducing pinned behavior and running regression tests.
- Do not unpin dependencies casually merely for version freshness.

### 122.2 Evaluate

Current pins:

- Fragment 1.6.2 due navigation latency/freeze concern;
- RecyclerView 1.2.1 due queue-sheet collapse behavior;
- ViewPager2 1.0.0 due dependency behavior.

R16 must:

1. reproduce each pinned defect in a focused sample/test;
2. determine whether new architecture removes it;
3. upgrade only with evidence;
4. document any retained pin and issue link.

---

# Part XX — Architecture Decision Records Required

## 123. Required ADRs

- **ADR-001:** Final application ID and migration strategy.
- **ADR-002:** New R16 database vs in-place migration. Recommended: new DB.
- **ADR-003:** Canonical recording boundary and version rules.
- **ADR-004:** Shippy-owned shuffle/traversal vs Media3 shuffle. Recommended: Shippy-owned.
- **ADR-005:** Player engine window strategy.
- **ADR-006:** Module boundaries.
- **ADR-007:** AcoustID/MusicBrainz usage and terms.
- **ADR-008:** Local metadata write policy.
- **ADR-009:** Cache size/age defaults.
- **ADR-010:** Playlist duplicate occurrence policy. Recommended: allowed.
- **ADR-011:** Songs-tab membership semantics.
- **ADR-012:** Views retained for R16 and future Compose boundary.
- **ADR-013:** Crew portable identity schema.
- **ADR-014:** Release signing/update channel.
- **ADR-015:** Last.fm discovery algorithm and cache TTL.

Each ADR records:

- context;
- decision;
- alternatives;
- consequences;
- migration impact;
- verification.


# Part XXI — R16 Implementation Program

## 124. Branch and Snapshot Preparation

### 124.1 Create immutable references

Before code changes:

- import the Aug-19 source into Git;
- tag `r15.3-stabilized-20260819`;
- retain GitHub control commit reference;
- store source ZIP and checksums in release records;
- create `r16/architecture-reset`;
- create a migration fixture from a real owner-device v10 database after redacting secrets;
- archive current APK and screenshots.

### 124.2 No direct work on the control branch

All R16 work occurs on the R16 branch. Recovery fixes may be cherry-picked into an R15 maintenance branch only if the owner needs an interim build.

### 124.3 Commit discipline

One commit should describe one coherent migration/refactor/test slice. Avoid commits whose message is “R16 refactor” and diff is 300 files.

---

## 125. Phase 0 — Freeze, Evidence, and Safety

### Goal

Establish reproducible current behavior and data safety before architecture replacement.

### Tasks

- [ ] Record current build/toolchain/dependency versions.
- [ ] Record SHA-256 of source and APK.
- [ ] Build once and archive logs/artifact.
- [ ] Install on owner device.
- [ ] Capture current bug videos/screenshots.
- [ ] Export a real v10 database and Library state safely.
- [ ] Implement `ShippyBackupV1` in R15.3 if application-ID change remains possible.
- [ ] Add a debug playback trace export.
- [ ] Add a debug migration/source identity report.
- [ ] Decide ADR-001 through ADR-006.
- [ ] Create benchmark datasets.
- [ ] Add a CI job matrix that does not pretend an emulator equals a physical-device test.
- [ ] Mark all pre-R16 canonical docs as superseded where this spec conflicts.

### Exit gate

- Current behavior reproducible.
- Backup round trip tested.
- Package/application ID decision recorded.
- No R16 schema work begins without real migration fixture.

---

## 126. Phase 1 — Immediate Stabilization Regressions

### Goal

Make the Aug-19 branch usable enough to refactor.

### Tasks

- [ ] Fix 304 dp seek bar in all resource variants.
- [ ] Move Edit order out of shared list layout.
- [ ] Patch current new-playback race with expected identity established before player mutation.
- [ ] Add focused reproduction test for synchronous/asynchronous Media3 callback ordering using fake adapter.
- [ ] Add canonical reorder handling to current Last.fm tracker as a temporary fix.
- [ ] Add a temporary monotonic Last.fm tick only if needed for an interim build; final replacement occurs later.
- [ ] Verify light-mode settings/action icon contrast.
- [ ] Re-run known playlist Play/Shuffle/Next/Previous matrix.
- [ ] Capture device screenshots matching the user-reported states.
- [ ] Do not add more UI features to the legacy model.

### Exit gate

- No giant Now Playing void.
- No Edit order outside playlist screen.
- Current track metadata/audio remain synchronized in the known matrix.
- Interim Last.fm scrobble works on a real account or remains explicitly open.

---

## 127. Phase 2 — Pure Core Model

### Goal

Introduce the new identity vocabulary and pure logic without touching UI.

### New module

`:shippy-core`

### Tasks

- [ ] Add typed IDs.
- [ ] Add Recording, Release, Artist, SourceReference, MediaAsset, PlaylistEntry, QueueEntry models.
- [ ] Add metadata observation/provenance models.
- [ ] Add RecordingVersion parser/traits.
- [ ] Add identity evidence/outcome types.
- [ ] Add source-selection policy.
- [ ] Add queue/shuffle reducer.
- [ ] Add merge/redirect pure rules.
- [ ] Add listening-session threshold policy.
- [ ] Add portable Crew descriptor.
- [ ] Add test factories/fixtures.
- [ ] Add forbidden dependency check: no Android, Room, Media3, Musikr, provider DTOs.

### Required tests

- [ ] ID validation.
- [ ] recording/version equality rules.
- [ ] source exact-key idempotence.
- [ ] queue duplicate occurrences.
- [ ] deterministic shuffle property tests.
- [ ] move/remove/add under shuffle.
- [ ] merge/redirect no cycles.
- [ ] user override precedence.
- [ ] Last.fm threshold from audible time.
- [ ] source-selection precedence.
- [ ] local asset requires verified link.

### Exit gate

The core module passes host-JVM tests independently.

---

## 128. Phase 3 — R16 Database and Importer

### Goal

Create the R16 database and fully tested importer while production still reads legacy data.

### New module

`:shippy-data`

### Tasks

- [ ] Implement schema in Section 38.
- [ ] Export Room schema JSON.
- [ ] Implement database views/FTS.
- [ ] Implement scoped repository interfaces.
- [ ] Implement deterministic legacy ID mapping.
- [ ] Implement migration audit and resumable importer.
- [ ] Import fixture data.
- [ ] Verify counts/invariants.
- [ ] Implement redirect resolution.
- [ ] Implement backup/restore against new schema.
- [ ] Add database query benchmarks.
- [ ] Add debug database inspector summary.

### Required tests

- [ ] new empty DB creation.
- [ ] every DAO.
- [ ] every critical view query.
- [ ] playlist duplicate entries.
- [ ] sparse reorder/rebalance.
- [ ] FTS updates.
- [ ] merge/unmerge transactions.
- [ ] all migration fixtures.
- [ ] process death/retry during import.
- [ ] rollback before cutover.
- [ ] no orphan rows.
- [ ] backup round trip.

### Exit gate

A production-like v10 database imports into R16 with a zero-loss audit for user-owned data.

---

## 129. Phase 4 — Source Ingestion and Managed Assets

### Goal

Normalize local/provider inputs into R16 without changing playback yet.

### New module

`:shippy-sources`

### Tasks

- [ ] Introduce LocalMediaEngine wrapper around Musikr.
- [ ] Map local scan output to raw observation + asset.
- [ ] Implement ManagedAssetRegistry.
- [ ] Reconcile existing downloads.
- [ ] Change providers to return SourceTrackObservation.
- [ ] Implement SourceRepository.
- [ ] Implement RecordingIngestor.
- [ ] Add exact source-key lookup.
- [ ] Add external-ID lookup.
- [ ] Add conservative metadata scoring.
- [ ] Add negative decision storage.
- [ ] Add event-driven enrichment work.
- [ ] Add transient catalogue retention/GC.
- [ ] Verify selected folders and download folder behavior.

### Required tests

- [ ] same local file rescanned.
- [ ] Shippy download scanned.
- [ ] unrelated user file in download folder.
- [ ] moved managed file.
- [ ] same provider result repeated.
- [ ] YT/YT Music same video source family.
- [ ] Jio/YT metadata candidate ambiguity.
- [ ] version mismatch veto.
- [ ] cancelled provider search.
- [ ] provider partial failure.
- [ ] transient GC preserves durable references.

### Exit gate

One source creates or reuses exactly one Recording; managed downloads cannot become duplicate local recordings.

---

## 130. Phase 5 — Playback Coordinator and Fake Engine

### Goal

Build the new playback state machine independently from Media3.

### Tasks

- [ ] Implement PlaybackCoordinator actor/event loop.
- [ ] Implement immutable PlaybackSnapshot.
- [ ] Implement PlaybackCommandRouter ordinary mode.
- [ ] Implement queue reducer.
- [ ] Implement Shippy-owned shuffle.
- [ ] Implement generation and stale-result rejection.
- [ ] Implement fake PlayerEngine.
- [ ] Implement PlaybackSourcePreparer fake.
- [ ] Implement engine window model.
- [ ] Implement position anchor.
- [ ] Implement listening-session tracker.
- [ ] Implement checkpoint model.
- [ ] Add trace events.

### Required model tests

- [ ] PlayContext commits selected QueueEntryId.
- [ ] Play/Shuffle selected item never changes identity.
- [ ] source preparation completing out of order.
- [ ] player commit arriving before/after asynchronous work.
- [ ] stale callback from previous generation.
- [ ] duplicate recording entries.
- [ ] next/previous/repeat all/repeat one.
- [ ] jump outside engine window.
- [ ] add/move/remove current entry.
- [ ] remove all.
- [ ] source failure/fallback.
- [ ] buffering/pause listening time.
- [ ] seek does not add audible time.
- [ ] checkpoint/restore.
- [ ] 10,000-entry queue memory/time.
- [ ] random command sequence invariant/property test.

### Exit gate

The fake engine can run long randomized command traces without invariant violation.

---

## 131. Phase 6 — Media3 Engine Integration

### Goal

Replace the current playback authority with the new coordinator/engine adapter.

### Tasks

- [ ] Split ExoPlaybackStateHolder.
- [ ] Implement PlayerTransaction before mutations.
- [ ] Set MediaItem.mediaId to QueueEntryId.
- [ ] Implement observation mapping.
- [ ] Implement engine window.
- [ ] Implement source refresh.
- [ ] Integrate cache data source.
- [ ] Integrate request headers safely.
- [ ] Integrate crossfade/gapless.
- [ ] Integrate audio focus/session.
- [ ] Implement error/fallback.
- [ ] Implement process-scoped service.
- [ ] Keep legacy manager one-way adapter temporarily.
- [ ] Add device playback trace.

### Required tests

- [ ] fake/simulated callback ordering.
- [ ] actual Media3 JVM/Robolectric where reliable.
- [ ] instrumentation local sample playback.
- [ ] current item commit.
- [ ] playlist replacement.
- [ ] rapid play A → B → C.
- [ ] Next/Previous/GoTo.
- [ ] source URL replacement.
- [ ] crossfade item identity.
- [ ] decoder error.
- [ ] Bluetooth route/focus transitions.
- [ ] session end.
- [ ] cache hit/miss.

### Physical exit gate

On device:

- audio, metadata, artwork, queue, notification agree for 100+ transitions;
- Play/Shuffle matrix passes;
- no current Aug-19 state regression;
- local/cached/provider playback works.

---

## 132. Phase 7 — System Surfaces

### Goal

Migrate every non-UI control/display to the new coordinator.

### Tasks

- [ ] MediaSession.
- [ ] notification.
- [ ] lock screen.
- [ ] headset/Bluetooth.
- [ ] widget.
- [ ] Android Auto.
- [ ] Quick Settings.
- [ ] Tasker/external intents.
- [ ] sleep timer.
- [ ] audio-session/equalizer exposure.

### Exit gate

No production system surface imports or listens to legacy `PlaybackStateManager`.

---

## 133. Phase 8 — Canonical Library and Search UI

### Goal

Switch Library/Search from legacy object graphs to R16 read models.

### Tasks

- [ ] Build paged Songs.
- [ ] Build Playlists.
- [ ] Build Albums/Artists/Genres.
- [ ] Build system collections.
- [ ] Build Library layout/pinning.
- [ ] Build Playlist detail PagingSource.
- [ ] Build contextual Library search.
- [ ] Build playlist search/sort/order.
- [ ] Build multi-select.
- [ ] Build long-press sheets.
- [ ] Build Global Search coordinator.
- [ ] Migrate playback origins from rows.
- [ ] Import device playlists.
- [ ] Delete shared Edit-order leakage.
- [ ] Preserve scroll/tab state.

### Exit gate

No Library/Search UI consumes `musikr.Song`, legacy Playlist, or `metadata.observeAll()`.

---

## 134. Phase 9 — Downloads and Cache

### Goal

Complete offline ownership and cache behavior under the new model.

### Tasks

- [ ] Migrate download jobs.
- [ ] Implement permanent asset publication.
- [ ] Implement managed scanner recognition.
- [ ] Implement complete-cache promotion.
- [ ] Implement cache settings/metrics.
- [ ] Implement remove download vs delete local vs clear cache.
- [ ] Implement download collection.
- [ ] Implement collection missing-item download.
- [ ] Implement revoked destination recovery.
- [ ] Implement asset verification maintenance.

### Physical exit gate

- download from each viable provider;
- restart/process death;
- play offline;
- local scanner does not duplicate;
- remove download preserves playlists/likes;
- cache clear preserves download;
- cache promotion avoids duplicate transfer where supported.

---

## 135. Phase 10 — Identify, Edit, and Deduplicate

### Goal

Deliver the library-cleanup experience.

### Tasks

- [ ] Metadata editor.
- [ ] user overrides/provenance view.
- [ ] Identify Track UI.
- [ ] existing catalogue matching.
- [ ] MusicBrainz client/rate limiter.
- [ ] optional AcoustID/Chromaprint integration.
- [ ] Last.fm identity hints.
- [ ] provider progressive candidate search.
- [ ] match evidence cards.
- [ ] confirm/link/unlink.
- [ ] merge/unmerge.
- [ ] bulk cleanup scan/review.
- [ ] optional file tag write.
- [ ] duplicate asset report.

### Exit gate

Owner can take a metadata-poor local file, identify it, and see one corrected recording everywhere without file replacement or duplicate Library row.

---

## 136. Phase 11 — Last.fm and History

### Goal

Replace the current scrobble tracker with session-based correctness.

### Tasks

- [ ] PlayHistoryCoordinator.
- [ ] LastFmScrobbleCoordinator.
- [ ] audible-time policy.
- [ ] Now Playing.
- [ ] durable outbox migration.
- [ ] accepted/ignored/retry/reauth.
- [ ] disconnected hiding.
- [ ] scrobble status.
- [ ] recent/top profile.
- [ ] discovery cache.
- [ ] canonical artwork resolution.

### Physical exit gate

Real account confirms:

- local scrobble;
- provider scrobble;
- downloaded scrobble;
- pause/resume;
- seek;
- skip before threshold;
- offline queue then network recovery;
- duplicate prevention;
- reauth.

---

## 137. Phase 12 — Lyrics

### Goal

Tie lyrics cleanly to canonical recording/current queue identity.

### Tasks

- [ ] migrate cache.
- [ ] dedicated lyrics state holder.
- [ ] stale request protection.
- [ ] fixed preview viewport.
- [ ] full lyrics behavior.
- [ ] palette integration.
- [ ] offline/stale fallback.
- [ ] accessibility.
- [ ] retry/source diagnostics.

### Exit gate

Rapid skipping cannot display previous lyrics; layout never jumps.

---

## 138. Phase 13 — Crew

### Goal

Move Crew onto R16 identity and playback command routing.

### Tasks

- [ ] portable recording descriptor v4.
- [ ] source hint mapping.
- [ ] QueueEntryId session semantics.
- [ ] command router.
- [ ] playback projector.
- [ ] telemetry.
- [ ] drift controller.
- [ ] temporary asset integration.
- [ ] Push & Pull promotion.
- [ ] checkpoint version/migration.
- [ ] compatibility/rejection for older peers.
- [ ] update transport fixtures.

### Exit gate

Two/three-device physical matrix passes without local/shared authority feedback loops.

---

## 139. Phase 14 — M3 Expressive and QoL Polish

### Goal

Apply consistent product polish after core state is stable.

### Tasks

- [ ] update stable Material 1.14.0.
- [ ] rename themes/styles.
- [ ] unify bottom sheets.
- [ ] redesign inconsistent drawer/flyouts.
- [ ] album palette.
- [ ] Now Playing responsive layout.
- [ ] lyrics polish.
- [ ] light/dark audit.
- [ ] loading/empty/error states.
- [ ] touch targets.
- [ ] reduced motion.
- [ ] manage Library tabs.
- [ ] playlist import/export.
- [ ] visual regression screenshots.

### Exit gate

Every primary screen passes a design consistency checklist and owner review.

---

## 140. Phase 15 — Performance Hardening

### Goal

Measure and meet R16 budgets.

### Tasks

- [ ] Paging/query plans.
- [ ] 10k/50k library benchmarks.
- [ ] 10k queue benchmark.
- [ ] macrobenchmarks.
- [ ] baseline profile.
- [ ] artwork/palette cache metrics.
- [ ] app startup.
- [ ] Library tab transitions.
- [ ] player start latency.
- [ ] memory/leak pass.
- [ ] low-storage/cache behavior.
- [ ] battery/background-work inspection.

### Exit gate

Budgets in Section 88 pass on reference devices or deviations are explicitly accepted with evidence.

---

## 141. Phase 16 — Naming, Release, and Beta Gate

### Goal

Finish the product transition and prove release quality.

### Tasks

- [ ] application ID decision executed.
- [ ] first-party Auxio names removed.
- [ ] legal attribution verified.
- [ ] release signing configured.
- [ ] R8/minify release tested.
- [ ] backup/restore tested.
- [ ] migration tested from owner database.
- [ ] accessibility matrix.
- [ ] Android 7–16 matrix.
- [ ] provider live tests.
- [ ] process death.
- [ ] storage permission revocation.
- [ ] Crew matrix.
- [ ] crash/ANR review.
- [ ] final owner acceptance.
- [ ] update canonical docs/status/changelog.
- [ ] remove legacy code only after evidence.

---

# Part XXII — Feature Flags and Rollback

## 142. Flags

Use build/config flags during migration:

```text
R16_DATABASE_READS
R16_LOCAL_INGESTION
R16_PLAYBACK_COORDINATOR
R16_LIBRARY_UI
R16_GLOBAL_SEARCH
R16_DOWNLOADS
R16_LASTFM
R16_CREW_BRIDGE
```

Rules:

- flags are temporary;
- one side is authoritative;
- no bidirectional state syncing;
- release build has a documented final flag state;
- legacy code is deleted after one stable phase, not kept forever.

---

## 143. Rollback

Before database cutover:

- revert flag to legacy;
- leave new DB for diagnostics.

After cutover:

- do not silently write both DBs;
- explicit backup/export supports recovery;
- rollback build must understand R16 backup or clearly refuse downgrade;
- release notes warn that downgrade may require restore.

---

# Part XXIII — Agent Execution Contract

## 144. Agent Rules

The implementation agent must not:

- reinterpret Recording as provider result;
- use provider ID as RecordingId;
- add another playback source of truth;
- make UI state mutable from several repositories;
- solve a migration by clearing data;
- perform fuzzy auto-merge without thresholds/evidence;
- put screen-specific controls in generic layouts;
- use whole-catalogue `observeAll` for screen lists;
- resolve every queue source before play;
- create an unbounded background job;
- remove legal attribution;
- rename application ID without the migration plan;
- claim beta because unit tests pass.

### 144.1 Required workflow per phase

1. Restate phase scope.
2. Inspect relevant current files.
3. Update/create ADR if needed.
4. Add tests for current defect/invariant.
5. Implement smallest coherent slice.
6. Run focused checks.
7. Review diff.
8. Update migration/status docs.
9. Commit.
10. Move only after exit gate.

### 144.2 Parallel agents

At most four active agents.

Workers receive:

- disjoint files;
- fixed architecture;
- exact acceptance checks;
- no authority to invent cross-system decisions.

Root agent integrates and reviews.

### 144.3 Stop conditions

Stop and ask owner/architect when:

- final application ID is required;
- provider terms/credentials conflict with product use;
- migration fixture reveals unmapped user data;
- identity evidence rules would auto-merge a known ambiguous case;
- a physical-device bug contradicts tests;
- a visual decision exceeds the QoL boundary;
- Crew compatibility requires a product tradeoff.

---

## 145. Definition of “Implemented”

- **Implemented:** source exists and compiles in its intended integration.
- **Unit-tested:** deterministic unit tests pass.
- **Integration-tested:** real components interact in test environment.
- **Instrumented:** Android instrumentation passes.
- **Device-tested:** required workflow passed on named physical device/OS.
- **Performance-tested:** measured against budget.
- **Beta-ready:** all release gates pass.

Never collapse these labels.

---

# Part XXIV — Test Architecture

## 146. Test Pyramid

### Pure host-JVM

- domain;
- identity;
- queue;
- shuffle;
- match scoring;
- source selection;
- listening session;
- Crew reducer/protocol;
- serialization.

### Database tests

- DAOs/views/FTS;
- migrations/import;
- transactions;
- query plans;
- redirects;
- backup.

### Integration tests

- source ingestion;
- download publication;
- cache promotion;
- provider fixtures;
- Last.fm fake server;
- playback coordinator + fake engine;
- Crew multi-client simulation.

### Instrumentation

- Media3;
- SAF/MediaStore;
- WorkManager;
- service/notification;
- View layouts/accessibility;
- process death where feasible.

### Physical

- real provider;
- real Last.fm;
- audio routes;
- old Android;
- performance;
- multi-phone Crew.

---

## 147. Playback Invariant Test Catalogue

For every transition, assert:

```text
snapshot.currentQueueEntryId
= engine current MediaItem ID
= Now Playing item
= mini-player item
= notification item
= widget item
= queue highlight
= lyrics request QueueEntryId
= listening session QueueEntryId
```

Scenarios:

- new local playback;
- new provider playback;
- downloaded playback;
- cache playback;
- shuffled playlist;
- duplicate recording twice;
- rapid row taps;
- Next/Previous;
- GoTo;
- automatic end;
- repeat one/all;
- shuffle toggle;
- queue move/remove;
- source refresh;
- source fallback;
- process restore;
- Crew projection;
- error retry.

---

## 148. Identity Test Catalogue

- same provider key repeated;
- same managed URI repeated;
- same checksum;
- same fingerprint;
- same ISRC with matching metadata;
- same ISRC with impossible duration/version conflict;
- same MBID;
- title/artist exact, duration close;
- title exact, live qualifier;
- remix;
- clean/explicit;
- YouTube cover version;
- featured artist punctuation;
- transliterated title;
- missing artist;
- filename only;
- user reject;
- user confirm;
- merge/unmerge;
- redirect chain;
- concurrent ingestion;
- migration rerun.

---

## 149. Database and Paging Test Catalogue

- playlist page ordering;
- search within playlist;
- sort modes;
- duplicate playlist entries;
- sparse move;
- rebalance;
- pin/order;
- system collections;
- asset-derived Local/Downloads;
- Library Songs semantics;
- FTS ranking;
- updates after metadata edit;
- updates after merge;
- 50k dataset query timing;
- invalidation scope;
- no full-table observer in screen repository.

---

## 150. Download and Cache Test Catalogue

- fresh download;
- pause/resume;
- retry;
- process death;
- destination revoked;
- low storage;
- existing complete cache promotion;
- partial cache;
- checksum mismatch;
- Shippy file rescanned;
- moved file;
- user file in download folder;
- remove download;
- clear cache;
- local asset remains;
- source provider unavailable after download.

---

## 151. Last.fm Test Catalogue

- Now Playing once;
- threshold half duration;
- four-minute cap;
- short track ignored;
- pause;
- buffering;
- seek forward/back;
- speed change;
- Next before threshold;
- duplicate callbacks;
- offline queue;
- network recovery;
- accepted;
- ignored;
- malformed success;
- invalid session;
- batch order/signature;
- local identified track;
- unidentified file;
- queue reorder;
- shuffle;
- process restart.

---

## 152. UI Test Catalogue

- all Library tabs;
- Edit order only Playlists;
- light/dark;
- dynamic color;
- album palette contrast;
- short/tall screen;
- landscape;
- large font;
- TalkBack;
- bottom sheet drag/back;
- playlist search/sort;
- selection mode;
- player collapse;
- lyrics scroll/collapse conflict;
- no 304 dp seek view;
- loading/error/empty/offline;
- Last.fm disconnected hidden;
- rapid playback changes.

---

## 153. Performance Test Catalogue

Datasets and actions:

- 50k Songs scroll;
- 10k playlist open/search/sort;
- 10k queue shuffle;
- 1k playlists reorder;
- rapid Home↔Library 20 times;
- rapid global queries;
- provider cancellation;
- 100 artwork rows;
- palette churn across 100 skips;
- cache eviction;
- background enrichment batch;
- process restore 5k queue;
- memory after one hour playback.

---

# Part XXV — Physical Device Matrix

## 154. Android Versions

Minimum matrix:

- Android 7 / API 24;
- Android 9 / API 28;
- Android 11 / API 30;
- Android 13 / API 33;
- Android 14 / API 34;
- Android 15 / API 35;
- Android 16 / API 36.

### 154.1 Device classes

- low-memory/older;
- representative mid-range;
- modern flagship;
- tablet/large screen;
- at least two/three phones for Crew.

### 154.2 Routes

- speaker;
- wired headset where available;
- Bluetooth;
- route disconnect/reconnect;
- phone call/audio focus;
- notification/headset controls.

### 154.3 Storage

- MediaStore;
- SAF folder;
- removable storage if available;
- revoked permission;
- moved/deleted file;
- low disk;
- download destination change.

---

# Part XXVI — Beta Release Gate

## 155. Core Gate

- [ ] One canonical recording architecture active.
- [ ] No provider IDs used as recording IDs.
- [ ] No production UI imports legacy Song/Playlist/MusicParent domain.
- [ ] One playback coordinator active.
- [ ] Media3 current item always keyed QueueEntryId.
- [ ] Shippy-owned shuffle.
- [ ] v10 migration verified.
- [ ] backup/restore verified.
- [ ] managed download duplication eliminated.
- [ ] large lists paged/scoped.
- [ ] source preparation bounded.
- [ ] all P0/P1 regressions closed.

---

## 156. Product Gate

- [ ] Home works.
- [ ] Global Search works with partial provider failure.
- [ ] Library contextual search works.
- [ ] playlist search/sort/order works.
- [ ] batch actions work.
- [ ] Play/Shuffle/Continue Listening work.
- [ ] download/cache/local semantics are clear.
- [ ] Identify Track works.
- [ ] lyrics stable.
- [ ] Last.fm hidden disconnected and correct connected.
- [ ] Crew either passes required matrix or is deliberately feature-gated off with no false completeness claim.

---

## 157. Quality Gate

- [ ] Material 1.14.0 stable.
- [ ] light/dark/dynamic color.
- [ ] album tint contrast.
- [ ] sheets/drawer consistent.
- [ ] no visual regressions in screenshots.
- [ ] TalkBack/dynamic type/touch targets.
- [ ] performance budgets measured.
- [ ] no ANRs.
- [ ] release build/signing/R8 tested.
- [ ] crash-free owner test period.
- [ ] diagnostics/backup available.

---

## 158. Release Truth

A beta may still have documented provider fragility because unofficial external provider APIs can change. It may not have:

- known audio/metadata mismatch;
- data-loss migration;
- broken Last.fm threshold;
- duplicate managed downloads;
- unbounded Library work;
- visibly broken player layout;
- false system controls;
- unverified release signing/update path.

---

# Part XXVII — Risk Register

## 159. High Risks

### RISK-001 — Application ID migration

Mitigation: decide early, bridge backup, test restore.

### RISK-002 — Dual playback authority during migration

Mitigation: one-way adapter, short phase, invariant tests.

### RISK-003 — Incorrect automatic dedupe

Mitigation: conservative thresholds, version vetoes, user review, reversible decisions.

### RISK-004 — Real local data edge cases

Mitigation: owner DB/files fixture, managed registry, no destructive migration.

### RISK-005 — Provider API drift

Mitigation: typed failures, fixtures, kill switch, source fallback, no provider-defined identity.

### RISK-006 — Crew integration breadth

Mitigation: pure core retained, bridge replaced last after local playback stable.

### RISK-007 — UI refactor and architecture refactor overlap

Mitigation: core first, user-owned visual direction, QoL pass after state migration.

### RISK-008 — Views maintenance mode

Mitigation: stable 1.14.0, UI isolation, no R16 Compose rewrite.

### RISK-009 — Performance claims without device evidence

Mitigation: macrobenchmark/reference devices/budgets.

### RISK-010 — Migration document grows stale

Mitigation: status/ADR updates in same commit; generated schema/API docs.

---

# Part XXVIII — Bug Traceability

## 160. SHIP-001 — Playlist playback metadata mismatch

**Root:** dual queue/order identities and asynchronous index acknowledgement.  
**R16 fix:** QueueEntryId everywhere, predeclared player transaction, Shippy traversal order, invariant tests.  
**Gate:** 100+ transition physical matrix.

## 161. SHIP-002 — Continue Listening opens but does not play

**Root:** navigation and playback command not one acknowledged operation.  
**R16 fix:** concrete checkpoint/context command, loading guard, coordinator result.  
**Gate:** tap begins audio and opens correct current entry.

## 162. SHIP-003 — Last.fm Now Playing but no scrobble

**Root:** threshold updated from incidental callbacks, no audible-time clock.  
**R16 fix:** ListeningSessionTracker monotonic active time, durable outbox.  
**Gate:** real account tests.

## 163. SHIP-004 — Playlist search broken

**Root:** no scoped playlist query/state.  
**R16 fix:** FTS + playlist-entry view/PagingSource.  
**Gate:** in-place search title/artist/album.

## 164. SHIP-005 — Playlist order incorrect

**Root:** provider/local IDs and ordering projection; current schema tied to track ID.  
**R16 fix:** PlaylistEntryId + canonical sparse order + distinct display sort.  
**Gate:** append/reorder/reopen/migration.

## 165. SHIP-006 — Library tab sluggish

**Root:** legacy fragment inflation, mixed repositories, whole-list observation.  
**R16 fix:** paged cached read models, retained screen state, smaller shell.  
**Gate:** tab benchmark.

## 166. SHIP-007 — Large lists eager

**Root:** full object graphs and list mapping.  
**R16 fix:** Paging 3, scoped queries, lazy artwork, engine queue window.  
**Gate:** 10k/50k datasets.

## 167. SHIP-008 — Search sluggish

**Root:** in-memory scans plus remote work and screen rebuild.  
**R16 fix:** FTS, generations/cancellation, progressive sections.  
**Gate:** local result budget.

## 168. SHIP-009 — Library search navigates Global Search

**Root:** shared search destination instead of scoped use case.  
**R16 fix:** separate LibrarySearch state within Library.  
**Gate:** no provider calls/navigation.

## 169. SHIP-010 — Weak search field

**Root:** inherited generic layout.  
**R16 fix:** Shippy SearchView/M3E style with clear states/touch target.  
**Gate:** visual/accessibility review.

## 170. SHIP-011 — No batch operations

**Root:** rows keyed by track objects and action APIs designed singly.  
**R16 fix:** stable occurrence IDs, selection command API, transactional batches.  
**Gate:** mixed selection actions and failure handling.

## 171. SHIP-012 — Playlist long press no action

**Root:** no canonical contextual action model.  
**R16 fix:** playlist action bottom sheet.  
**Gate:** user/system action sets.

## 172. SHIP-013 — Pin management

**Root:** duplicate layout/pinned authorities.  
**R16 fix:** one database Library layout authority.  
**Gate:** pin/unpin/reorder/restart.

## 173. SHIP-014 — Sorting controls

**Root:** inconsistent legacy sort UI.  
**R16 fix:** common sort sheet, persisted mode, custom order distinct.  
**Gate:** every mode and return.

## 174. SHIP-015 — Recently Played vertical density

**R16 fix:** compact horizontal paged/bounded section.

## 175. SHIP-016 — Redundant Your Music

**R16 fix:** remove from Home; Library owns organization.

## 176. SHIP-017 — Static Recent Downloads

**R16 fix:** remove; downloads surface in Library and smart context.

## 177. SHIP-018 — Last.fm recommendations

**R16 fix:** connected-only bounded discovery derived from history/similar tracks.

## 178. SHIP-019 — Star artwork placeholders

**R16 fix:** canonical artwork resolver and neutral fallback.

## 179. SHIP-020 — Lyrics visually disconnected

**R16 fix:** canonical player palette/tokens, stable typography, dedicated state.

## 180. SHIP-021 — Lyrics container size changes

**R16 fix:** stable viewport with internal content; layout tests.  
**Current regression note:** do not assign the viewport height to seek bar.

## 181. SHIP-022 — Player artwork integration

**R16 fix:** contrast-safe album palette overlay.

## 182. SHIP-023 — Side flyout inconsistent

**R16 fix:** Shippy navigation drawer tokens or replace with appropriate sheet/navigation pattern.

## 183. SHIP-024 — Save flyout should be drawer

**R16 fix:** guarded saved-destinations bottom sheet.

## 184. SHIP-025 — Like/Add state

**R16 fix:** plus → filled heart, transactional relationship, clear destination path.

## 185. SHIP-026 — Settings icon invisible light mode

**R16 fix:** theme token/contrast audit and screenshot test.

## 186. SHIP-027 — UI crowded

**R16 fix:** Home hierarchy, spacing tokens, negative space, fewer permanent sections.

## 187. SHIP-028 — Sheets should be smooth/consistent

**R16 fix:** reusable Shippy sheet host, motion/insets/accessibility.

---

# Part XXIX — Current Aug-19 Regression Traceability

## 188. REG-001 — Seek bar 304 dp

Fix in Phase 1; resource inflation regression test.

## 189. REG-002 — Edit order on Songs

Fix in Phase 1; screen ownership regression test.

## 190. REG-003 — New playback pending-ID race

Eliminated by Phase 5/6 transaction design.

## 191. REG-004 — Last.fm no continuous clock

Eliminated by Phase 5/11 listening-session design.

## 192. REG-005 — Last.fm stale queue after reorder

Eliminated by QueueEntryId session identity.

## 193. REG-006 — Whole-catalogue collection composition

Eliminated by Phase 3/8 read models/Paging.

## 194. REG-007 — Entire queue display mapping

Eliminated by Now Playing current/adjacent projections and paged queue.


# Appendix A — Reference Identity and Matching Algorithm

## A.1 Purpose

This appendix defines a concrete default algorithm so an implementation agent does not replace “conservative matching” with an undocumented fuzzy string call.

The algorithm produces evidence and a score. It does not make the final policy decision by score alone.

---

## A.2 Preprocessing

For each observation derive:

```kotlin
data class MatchingFeatures(
    val normalizedTitle: String?,
    val normalizedPrimaryArtist: String?,
    val normalizedArtistSet: Set<String>,
    val normalizedRelease: String?,
    val durationMs: Long?,
    val versionTraits: Set<VersionTrait>,
    val explicitness: Explicitness,
    val isrcs: Set<String>,
    val musicBrainzRecordingIds: Set<String>,
    val acoustIds: Set<String>,
    val sourceKeys: Set<SourceKey>,
    val fingerprintHashes: Set<String>,
)
```

Normalization is deterministic and versioned. Store the normalizer version in evidence.

---

## A.3 Veto Rules

Return `NO_MATCH` before scoring when any high-confidence veto exists:

1. explicit user rejection;
2. conflicting verified MusicBrainz Recording IDs;
3. disjoint verified ISRCs plus strong contradictory metadata;
4. live vs non-live;
5. remix vs original;
6. cover vs original artist/performance;
7. instrumental vs vocal where known;
8. clean vs explicit when provider/file clearly distinguishes them and product policy treats them separately;
9. duration difference above policy threshold without a strong exact identifier;
10. provider source explicitly refers to video/non-music content incompatible with recording.

A veto can be overridden only by explicit user confirmation or stronger exact evidence reviewed by the user.

---

## A.4 Evidence Scores

Reference weights:

| Feature | Score |
|---|---:|
| Exact source key | +1.00 terminal |
| Verified checksum | +1.00 terminal |
| High-confidence fingerprint | +0.98 |
| Same MusicBrainz Recording ID | +0.97 |
| Same ISRC | +0.92 |
| Same video/source-family ID | +0.90 |
| Exact normalized title | +0.18 |
| Strong title token similarity | +0.12 |
| Exact primary artist | +0.18 |
| Strong artist set overlap | +0.12 |
| Exact release | +0.08 |
| Duration within 1 second | +0.16 |
| Duration within 3 seconds | +0.12 |
| Duration within policy tolerance | +0.06 |
| Same explicitness | +0.02 |
| Same version traits | +0.08 |
| Trusted provider pair | +0.03 |
| Missing artist | −0.10 |
| Generic/unknown artist | −0.12 |
| Missing duration | −0.05 |
| Filename-only title | −0.15 |
| Release contradiction | −0.08 |
| Weak artist mismatch | −0.20 |
| Version uncertainty | −0.15 |

These values are a starting policy, not sacred constants. They live in one tested `MatchingPolicy`.

### A.4.1 Decision thresholds

- exact/terminal evidence: auto-link after sanity checks;
- score ≥ 0.90 with no veto: auto-link;
- score 0.75–0.90: probable, user review;
- score 0.55–0.75: possible, low-ranked user review;
- below 0.55: keep separate.

Do not auto-link a metadata-only match merely because several weak fields happen to add above threshold; require at least:

- exact artist + title + duration, or
- one strong identifier.

### A.4.2 Provider-specific trust

A provider adapter may declare field trust:

```kotlin
data class MetadataTrustProfile(
    val title: TrustLevel,
    val artistCredit: TrustLevel,
    val duration: TrustLevel,
    val release: TrustLevel,
    val externalIdentifiers: TrustLevel,
    val artwork: TrustLevel,
)
```

Generic YouTube titles/descriptions are less trustworthy than a dedicated music catalogue. Trust affects evidence, never identity by itself.

---

## A.5 Candidate Retrieval

Before scoring, retrieve a bounded candidate set:

1. exact source/external identifier indexes;
2. FTS title/artist search, limit 25;
3. duration bucket;
4. optional release filter;
5. provider remote search only when event policy permits.

Never compare an incoming item against every recording in Kotlin.

---

## A.6 Automatic Link Transaction

```kotlin
suspend fun linkSourceAutomatically(
    sourceReferenceId: SourceReferenceId,
    recordingId: RecordingId,
    evidence: MatchEvidence,
) = database.withTransaction {
    require(evidence.policyVersion == CURRENT_POLICY)
    require(evidence.decision == AUTO_LINK)
    require(!identityRejections.exists(sourceReferenceId, recordingId))
    sourceDao.attach(sourceReferenceId, recordingId)
    identityDecisionDao.insert(evidence.toDecision())
    metadataResolver.recompute(recordingId)
    searchIndexer.refresh(recordingId)
}
```

---

## A.7 User Confirmation

User confirmation is stronger than automatic evidence but reversible.

Store:

- candidate values shown;
- evidence;
- selected target;
- prior link;
- timestamp;
- policy version;
- whether canonical metadata preview was accepted.

---

## A.8 Negative Decisions

A user choosing “not this recording” creates a negative edge. Future searches hide or demote it.

The user can reset negative decisions in advanced identity tools.

---

# Appendix B — Canonical Metadata Resolution

## B.1 Field-by-Field Resolution

Do not select one provider as the owner of all metadata.

Example:

```text
title       ← user-confirmed MusicBrainz/provider observation
artists     ← user override
duration    ← verified local asset
release     ← JioSaavn release observation
artwork     ← YouTube Music high-resolution artwork
year        ← MusicBrainz release
```

Each selected field has provenance.

---

## B.2 Default Field Policies

### Title

1. user override;
2. user-confirmed source;
3. MusicBrainz recording;
4. dedicated music provider;
5. embedded tag;
6. generic provider/video;
7. filename fallback.

### Artist credit

Same order, but do not replace a precise featured credit with a collapsed “Various Artists.”

### Duration

1. playable verified asset measurement;
2. trusted provider;
3. MusicBrainz median/recording length;
4. embedded tag;
5. unknown.

### Release

1. user override;
2. selected contextual release;
3. user-confirmed source release;
4. dedicated provider;
5. embedded tag.

### Artwork

1. user override;
2. selected release artwork;
3. high-resolution verified provider;
4. embedded artwork;
5. neutral generated fallback.

### Explicitness/version

Prefer strong source metadata and user confirmation. Do not infer explicitness from filename alone.

---

## B.3 Recompute Triggers

Canonical projection recomputes when:

- observation added/updated;
- identity link changed;
- user override changed;
- asset verified;
- release preference changed;
- merge/unmerge.

Recompute is transactional and produces a diff so UI/read indexes update only when values change.

---

## B.4 Canonical Metadata Stability

A lower-quality refresh cannot replace a higher-trust selected value merely because it is newer.

A source disappearing does not erase its last useful metadata observation; mark provenance stale if necessary.

---

## B.5 Contextual Presentation

The same recording can be shown in an album context with release-specific:

- artwork;
- album title;
- track number;
- display title.

The underlying RecordingId remains the same.

```kotlin
data class RecordingPresentationContext(
    val recordingId: RecordingId,
    val releaseId: ReleaseId?,
    val origin: PlaybackOrigin?,
)
```

---

# Appendix C — Source Selection State Machine

## C.1 Inputs

- RecordingId
- QueueEntryId
- active Crew context
- asset list
- source list
- user preferred source
- provider priority/settings
- network/metered state
- download/cache state
- requested quality
- decoder/device capabilities
- source availability TTL

---

## C.2 Candidate Ranking

```text
valid user-pinned source
        ↓
session-required Crew asset
        ↓
permanent download
        ↓
verified linked local asset
        ↓
complete cache
        ↓
preferred provider
        ↓
fallback providers
        ↓
Crew peer
```

Apply hard eligibility before quality scoring.

---

## C.3 Quality Scoring

Example internal score:

```text
verified availability             +100
offline/permanent                 +80
complete cache                    +60
lossless                          +20
preferred bitrate close           +15
lower startup latency             +10
user source preference             +8
expires soon                      −20
metered disallowed               reject
unverified file                  reject
identity confidence insufficient reject
```

Do not expose this score.

---

## C.4 Preparation Result Lifecycle

```text
UNREQUESTED
  → PREPARING
  → READY
  → ACTIVE
  → EXPIRING
  → REFRESHING
  → READY

any
  → FAILED_RETRYABLE
  → FAILED_FINAL
```

Results are keyed to queue generation and entry ID.

---

## C.5 Source Switch During Playback

A source may change for the same recording/queue entry due to:

- expiring URL;
- network failure;
- cache completion;
- route/quality change;
- Crew media arrival.

The queue entry and canonical recording remain unchanged.

Source switch should preserve position within tolerance and log the transition.

---

# Appendix D — Playback State Transition Matrix

## D.1 Phases

```kotlin
sealed interface PlaybackPhase {
    data object Idle : PlaybackPhase
    data class Preparing(val entryId: QueueEntryId) : PlaybackPhase
    data class Buffering(val entryId: QueueEntryId) : PlaybackPhase
    data class Ready(val entryId: QueueEntryId) : PlaybackPhase
    data class Playing(val entryId: QueueEntryId) : PlaybackPhase
    data class Paused(val entryId: QueueEntryId) : PlaybackPhase
    data class Recovering(val entryId: QueueEntryId, val attempt: Int) : PlaybackPhase
    data class Failed(val entryId: QueueEntryId?, val error: PlaybackError) : PlaybackPhase
    data object Ended : PlaybackPhase
}
```

---

## D.2 New Context

| Step | State | Effect |
|---|---|---|
| command accepted | Preparing(target) | increment generation, create queue/traversal |
| source result | Preparing(target) | build transaction/window |
| transaction applied | Preparing(target) | Media3 mutation |
| engine commit target | Ready/Buffering | publish current QueueEntryId |
| engine isPlaying | Playing | start listening anchor |
| failure | Recovering/Failed | retry/fallback |

A previous generation result cannot mutate any row.

---

## D.3 Next

| Condition | Action |
|---|---|
| next entry prepared in window | seek by QueueEntryId |
| next not prepared | enter Preparing/Buffering for same intended next ID |
| source fails | fallback while keeping next ID |
| no next, repeat all | wrap |
| no next, repeat off | Ended |
| active Crew | route command to Crew first |

---

## D.4 Previous

Policy:

- if current position exceeds configured rewind threshold, seek to zero;
- otherwise previous traversal entry;
- repeat behavior deterministic;
- identity by QueueEntryId.

---

## D.5 Shuffle Toggle

| Toggle | Action |
|---|---|
| off → on | generate deterministic permutation, pin current ID |
| on → off | restore base order, pin current ID |
| add under shuffle | insert into base; integrate into traversal by policy |
| remove | remove ID from both |
| move queue | product defines whether move affects base, traversal, or both; default queue move affects current traversal and base representation consistently |

Record the precise policy in tests/UI copy.

---

## D.6 Queue Mutation During Source Preparation

- mutation increments queue revision but not necessarily playback generation;
- prepared results apply only if target QueueEntryId still exists and source request revision remains valid;
- current entry removal selects deterministic replacement;
- stale window transaction is discarded.

---

# Appendix E — Screen State Contracts

## E.1 Home

```kotlin
data class HomeUiState(
    val loading: Boolean,
    val continueListening: ContinueListeningUi?,
    val recent: List<RecentRecordingUi>,
    val pinned: List<LibraryShortcutUi>,
    val lastFm: LastFmHomeUi?,
    val crew: CrewHomeUi?,
    val message: HomeMessage?,
)
```

No empty Last.fm state when disconnected.

---

## E.2 Library

```kotlin
data class LibraryUiState(
    val selectedTab: LibraryTab,
    val tabs: List<LibraryTab>,
    val query: String,
    val sort: LibrarySort,
    val layout: LibraryLayoutMode,
    val selection: SelectionState,
    val indexing: IndexingPresentation?,
)
```

Paged content is exposed separately from stable chrome state.

---

## E.3 Playlist Detail

```kotlin
data class PlaylistDetailUiState(
    val playlist: PlaylistHeaderUi?,
    val query: String,
    val sort: PlaylistSort,
    val canReorder: Boolean,
    val selection: SelectionState,
    val playbackRequest: AsyncActionState,
    val downloadAction: CollectionDownloadUi?,
    val error: UserMessage?,
)
```

Rows are PagingData keyed PlaylistEntryId.

---

## E.4 Now Playing

```kotlin
data class NowPlayingUiState(
    val queueEntryId: QueueEntryId?,
    val recordingId: RecordingId?,
    val title: String,
    val subtitle: String,
    val artwork: ArtworkUi,
    val palette: PlayerPalette,
    val phase: PlaybackPhaseUi,
    val position: PositionUi,
    val liked: Boolean,
    val download: DownloadUi,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val lyrics: LyricsPreviewUi,
    val scrobble: ScrobbleUi?,
)
```

No field is looked up from a different current-item source.

---

## E.5 Identify Track

```kotlin
data class IdentifyTrackUiState(
    val subject: IdentificationSubjectUi,
    val query: String,
    val localCandidates: List<MatchCandidateUi>,
    val remoteSections: List<IdentificationSourceSectionUi>,
    val activeSources: Set<IdentificationSource>,
    val selectedCandidate: MatchCandidateUi?,
    val preview: IdentificationPreviewUi?,
    val action: AsyncActionState,
)
```

Results arrive progressively; user can cancel.

---

# Appendix F — Critical SQL Read Queries

## F.1 Playlist page

```sql
SELECT
    e.playlist_entry_id,
    e.playlist_id,
    e.recording_id,
    e.order_key,
    r.canonical_title,
    ac.artist_display,
    rel.canonical_title AS release_title,
    art.location AS artwork,
    CASE WHEN lr.liked = 1 THEN 1 ELSE 0 END AS liked,
    EXISTS(
        SELECT 1 FROM media_asset a
        WHERE a.recording_id = r.recording_id
          AND a.asset_kind = 'PERMANENT_DOWNLOAD'
          AND a.asset_state = 'AVAILABLE'
    ) AS downloaded,
    EXISTS(
        SELECT 1 FROM media_asset a
        WHERE a.recording_id = r.recording_id
          AND a.asset_kind = 'LOCAL_FILE'
          AND a.asset_state = 'AVAILABLE'
    ) AS local
FROM playlist_entry e
JOIN recording r ON r.recording_id = e.recording_id
LEFT JOIN recording_artist_display_view ac
       ON ac.recording_id = r.recording_id
LEFT JOIN release rel ON rel.release_id = r.preferred_release_id
LEFT JOIN artwork_reference art ON art.artwork_id = r.preferred_artwork_id
LEFT JOIN library_recording lr ON lr.recording_id = r.recording_id
WHERE e.playlist_id = :playlistId
ORDER BY e.order_key, e.playlist_entry_id;
```

PagingSource wraps this query or a database view.

---

## F.2 Local system collection

```sql
SELECT DISTINCT v.*
FROM library_song_view v
WHERE EXISTS (
    SELECT 1
    FROM media_asset a
    WHERE a.recording_id = v.recording_id
      AND a.asset_kind = 'LOCAL_FILE'
      AND a.asset_state = 'AVAILABLE'
)
ORDER BY :sortExpression;
```

---

## F.3 Downloads

Same pattern with `PERMANENT_DOWNLOAD`.

---

## F.4 Liked

```sql
SELECT v.*
FROM library_song_view v
JOIN library_recording lr ON lr.recording_id = v.recording_id
WHERE lr.liked = 1
ORDER BY lr.first_added_at_epoch_ms DESC;
```

---

## F.5 Playlist FTS

```sql
SELECT pev.*
FROM playlist_entry_view pev
JOIN recording_search_fts fts
  ON fts.recording_id = pev.recording_id
WHERE pev.playlist_id = :playlistId
  AND recording_search_fts MATCH :ftsQuery
ORDER BY bm25(recording_search_fts), pev.order_key;
```

If Room/SQLite FTS4 lacks `bm25` on supported versions, use supported rank/order strategy and test it. Do not copy an FTS5-only query blindly.

---

# Appendix G — Background Work Policy

## G.1 Priority Classes

```kotlin
enum class WorkPriority {
    INTERACTIVE,
    USER_REQUESTED,
    DURABLE_FOREGROUND,
    BACKGROUND_MAINTENANCE,
}
```

### Interactive

- source resolution for current Play;
- Identify candidate search;
- explicit retry.

In-process, cancellable.

### User requested

- download;
- bulk cleanup;
- explicit rescan/export.

WorkManager where durable; progress visible.

### Durable foreground-adjacent

- Last.fm outbox;
- download verification;
- migration checkpoint.

### Background maintenance

- enrichment;
- fingerprint batches;
- cache age cleanup;
- catalogue GC;
- provider availability refresh.

---

## G.2 Concurrency

Suggested global caps:

- interactive provider resolution: 2–3;
- global provider search: one request per enabled provider, cap 3;
- enrichment: 1–2;
- fingerprints: 1 CPU worker;
- download parallelism: 2–3 depending on settings/network;
- artwork: delegated to bounded Coil;
- MusicBrainz: strict 1 request/second;
- AcoustID public API: at most 3 requests/second and terms-compliant.

---

## G.3 Backoff

- network: exponential with jitter;
- rate limit: provider-provided retry-after or conservative cooldown;
- authentication: stop and require user;
- malformed response: no aggressive retry;
- source unavailable: TTL before background retry;
- explicit user retry may bypass ordinary TTL once.

---

# Appendix H — Current Code Audit Snapshot

## H.1 Technology

Current R15.3 snapshot:

- Kotlin 2.3.10
- Java/JVM 21
- AGP 9.2.1
- compile/target SDK 36
- min SDK 24
- Room 2.8.4
- WorkManager 2.11.2
- Hilt/Dagger
- Views/Fragments/ViewBinding
- vendored Media3/ExoPlayer modules
- Musikr local engine
- Coil 3.4.0
- NewPipe Extractor 0.26.2
- WebRTC/Nearby/OkHttp for Crew
- Material 1.14.0-alpha10

Verify exact root Gradle constants when implementation starts; this list is audit context, not a dependency update command.

---

## H.2 Current Database Limitations

- `playlist_membership` primary key `(trackId, playlistId)` forbids duplicate occurrences.
- `library_relationship` uses old track ID as identity.
- provider-scoped canonical tracks prevent cross-provider unification.
- canonical metadata and relationships are joined in presentation.
- local/download truth is duplicated across subsystems.
- current checkpoint preserves raw queue but remains tied to old IDs.
- saved provider entities are separate from canonical Release/Artist models.

---

## H.3 Current Coupling Indicators

- 414 production Kotlin files.
- 70 Crew files.
- 34 non-Shippy package files import Shippy classes.
- 10 Shippy files import Musikr domain types directly.
- over 2,000 visible `Auxio` occurrences in first-party source/resources.
- several central files exceed 800–1,600 lines.

These numbers do not demand arbitrary splitting. They demonstrate that boundaries must be enforced.

---

# Appendix I — Repository-Wide Dependency Rules

## I.1 Forbidden imports

Automate checks:

- `shippy-core` must not import Android, Room, Media3, Musikr, provider DTOs.
- UI must not import DAOs or provider transport.
- integrations must not mutate playback.
- providers must not import UI.
- Crew pure core must not import Android transport/player.
- source adapters must not emit UI models.
- no first-party package imports legacy `org.oxycblt.auxio` domain after migration gate.

Use Detekt/custom Gradle checks or ArchUnit-like Kotlin tooling where practical.

---

## I.2 Time and randomness

Inject:

```kotlin
interface Clock
interface MonotonicClock
interface IdGenerator
interface RandomSource
```

Pure tests remain deterministic.

---

## I.3 Serialization

Version every persisted/wire payload. Unknown major versions fail safely.

---

# Appendix J — Release and CI Pipeline

## J.1 CI stages

1. formatting/static checks;
2. core JVM tests;
3. data tests/migration fixtures;
4. provider fixture tests;
5. Crew pure tests;
6. app JVM tests;
7. lint;
8. instrumentation selected API;
9. assemble release;
10. R8/proguard smoke;
11. macrobenchmark scheduled/device farm;
12. artifact/signature/hash publication.

### J.1.1 No false green

Live provider, real Last.fm, physical storage, Bluetooth, performance, and multi-phone Crew remain separate evidence.

---

## J.2 Release artifacts

- signed APK/AAB as chosen;
- SHA-256;
- mapping file;
- SBOM/dependency report;
- migration report;
- known issues;
- device matrix;
- backup/export instructions;
- source tag;
- GPL source/NOTICE compliance.

---

# Appendix K — Exact R16 Acceptance Journeys

## K.1 Local-first journey

1. Fresh install.
2. Choose local folder.
3. Index 10k songs.
4. Library usable during/after indexing.
5. Play local song.
6. Like, add to playlist, edit metadata.
7. Identify poor metadata.
8. Restart.
9. Notification/widget agree.
10. Last.fm scrobbles if connected.

### Pass

One recording everywhere; no UI stall; data persists.

---

## K.2 Provider-to-download journey

1. Search YouTube Music.
2. Play result immediately.
3. Like.
4. Add to playlist.
5. Background enrichment discovers JioSaavn if confident.
6. Stream/cache.
7. Download.
8. Restart offline.
9. Play from download.
10. Local scanner runs.
11. Library still shows one recording.

### Pass

Source changed; identity did not.

---

## K.3 Cross-provider failure

1. Queue provider recording.
2. Provider URL expires/fails.
3. Same source refresh attempted.
4. Alternate known source chosen.
5. Position preserved.
6. Now Playing never changes recording.
7. Last.fm one session/scrobble.

---

## K.4 Duplicate local file journey

1. Two files with same fingerprint.
2. Scanner links both assets to one recording.
3. Library displays one song by default.
4. Song Information shows two local assets.
5. User deletes one.
6. Recording remains playable from other.
7. No playlist loss.

---

## K.5 Ambiguous version

1. Local `Song.mp3`, 3:40.
2. Search finds studio 3:42 and live 6:20.
3. Live candidate vetoed/demoted.
4. User selects studio.
5. Link is reversible.

---

## K.6 Playlist journey

1. Create playlist.
2. Add same recording twice.
3. Reorder occurrences.
4. Sort by artist.
5. Return to custom order.
6. Search within playlist.
7. Shuffle.
8. Audio/metadata correct.
9. Restart/order preserved.

---

## K.7 Last.fm journey

1. Disconnected: no Last.fm UI outside Settings.
2. Connect.
3. Play local identified recording.
4. Now Playing appears.
5. Pause/resume.
6. Reach threshold.
7. Scrobble accepted.
8. Go offline.
9. Play eligible track.
10. Reconnect; outbox flushes once.
11. Recommendations appear with artwork.

---

## K.8 Crew journey

1. Host has local-only recording.
2. Member joins.
3. Host/member adds duplicate occurrences.
4. Shared QueueEntryIds preserved.
5. Member lacks source.
6. Push & Pull provides temporary asset.
7. Playback synchronizes.
8. Member downloads explicitly.
9. Session ends; temporary asset removed, permanent asset remains.
10. Coordinator disconnects and recovers.

---

# Appendix L — Owner Decisions Still Required

The architecture is intentionally decisive. These product choices still require owner confirmation before code locks them:

1. Final application ID/domain.
2. Songs-tab membership for playlist-only provider recordings.
3. Whether tapping a filled heart unlikes immediately or opens destination management.
4. Default cache size and age policy.
5. Whether automatic high-confidence fingerprint/ISRC links occur silently or appear in a cleanup log.
6. Whether local asset or permanent Shippy download wins when both exist at similar quality.
7. Whether to write canonical metadata into Shippy downloads by default.
8. Whether device/Musikr playlists are auto-imported or offered as a one-time choice.
9. Crew visibility if its physical matrix lags the rest of beta.
10. Last.fm discovery default when connected.
11. Final provider priority defaults.
12. Whether playlist duplicates are exposed in UI; architecture recommends yes.
13. History retention duration.
14. Whether transient catalogue GC is 30, 60, or 90 days.
15. Tablet scope required for first R16 beta.

None of these questions blocks pure core/schema work except the application ID and Songs-tab semantics where migration counts depend on it.

## L.1 Recommended R16 defaults

Unless the owner records another decision before the dependent phase, use these defaults:

| Decision | Recommended default |
|---|---|
| Application ID | Choose the final public Shippy application ID before beta and perform an explicit old-package export/new-package import. This is the least bad time to pay the Android identity cost. The exact reverse-domain string still requires owner selection. |
| Songs-tab membership | Include every canonical Recording with any durable track-level Library relationship: liked, playlist membership, permanent download, manual identification/edit, or explicit individual save. Transient streamed results remain outside Library. |
| Filled heart behavior | Neutral `+` saves to Liked immediately. Tapping the filled heart opens Save Destinations with Liked selected; removal is explicit in the sheet. This preserves one-tap save while avoiding accidental loss. |
| Cache default | Adaptive 2 GB target with a safe free-space floor; evict items unused for 30 days. Settings offer smaller/larger limits and Never/7/30/90-day policies. |
| Automatic identity linking | Silent only for exact source keys, managed assets, verified strong IDs without conflicts, or strong fingerprints. Metadata-only matches enter the cleanup log/review unless exceptionally conclusive. |
| Multiple permanent assets | Select the best verified asset by user preference, playability, quality, and reliability. Do not hard-code “download always wins” when a better local lossless asset exists. |
| Tagging Shippy downloads | Write clean canonical tags/artwork where the container supports it safely, while keeping the database/managed registry authoritative. Never depend on tags to recover ownership. |
| Device playlist migration | Offer a one-time reviewed import. Do not silently keep two first-class playlist systems forever. |
| Crew if matrix incomplete | Gate it behind an explicit beta/experimental flag and state the limitation. Do not let Crew uncertainty block ordinary playback beta, and do not present unverified Crew as stable. |
| Last.fm discovery | When connected, enable one restrained Home recommendation/listening module by default with a Settings toggle. |
| Provider priority | Default to YouTube Music, then JioSaavn, then general YouTube, subject to enabled providers, source quality, health, region, and user settings. Offline verified assets remain ahead of providers. |
| Playlist duplicates | Supported. PlaylistEntryId exists specifically to preserve occurrences. |
| Local history retention | Keep raw sessions for 365 days by default, retain lightweight aggregates longer, and expose clear-history controls. |
| Transient catalogue GC | Eligible after 60 days with no durable reference, active cache/queue/history need, or pending work. |
| Tablet scope | R16 beta must be responsive and non-broken on tablets/large screens; a bespoke tablet redesign is not required unless owner promotes it into beta scope. |

These defaults are architectural fallbacks, not a substitute for owner review of the final product behavior.


# Appendix M — Target Repository and Source-Tree Blueprint

This appendix turns the architectural boundaries into a concrete repository shape. The purpose is not to create a museum of Gradle modules. The purpose is to make illegal dependencies difficult and core logic inexpensive to test.

R16 should add only the modules whose boundaries pay for themselves:

```text
:shippy-core       pure Kotlin domain, identity, queue, policy, reducers
:shippy-data       Room schema, DAOs, repositories, import/migration, FTS
:shippy-sources    local/provider/download/cache source ingestion and resolution
:app               Android UI, Media3 service/engine, system integration, DI
:musikr            retained local filesystem/indexing engine behind a Shippy wrapper
:relay             retained Crew relay service
```

A separate module is justified when at least one of the following is true:

- it must be usable without Android;
- it requires an independently enforced dependency boundary;
- it has a large deterministic host-JVM test surface;
- it has a materially different lifecycle, build target, or deployment artifact;
- extracting it reduces build/test cost for the work that changes most often.

Do **not** create modules for each feature, provider, or screen. That would replace one mess with a dependency graph that requires air-traffic control.

---

## M.1 Gradle dependency direction

```text
                       ┌───────────────┐
                       │ :shippy-core  │
                       └───────▲───────┘
                               │
                  ┌────────────┴────────────┐
                  │                         │
           ┌──────┴──────┐           ┌──────┴────────┐
           │ :shippy-data│           │:shippy-sources│
           └──────▲──────┘           └──────▲────────┘
                  │                         │
                  └────────────┬────────────┘
                               │
                          ┌────┴────┐
                          │  :app   │
                          └────▲────┘
                               │ wrapper only
                          ┌────┴────┐
                          │ :musikr │
                          └─────────┘
```

Allowed:

- `:shippy-core` depends only on the Kotlin standard library and tightly justified pure libraries.
- `:shippy-data` depends on `:shippy-core`, Room, coroutines, and Android storage primitives needed by persistence.
- `:shippy-sources` depends on `:shippy-core`, source/network/cache APIs, and narrow interfaces exported by data.
- `:app` depends on all three and owns Android UI, Media3, service, notification, widgets, and dependency injection.
- `:app` wraps `:musikr`; no R16 UI or core class imports Musikr models directly.

Forbidden:

- `:shippy-core` importing Android, Room, Media3, Retrofit/HTTP DTOs, ViewModel, Fragment, or Musikr.
- `:shippy-data` importing UI or Media3.
- providers importing Room entities directly.
- UI importing DAOs or provider implementations.
- Musikr importing Shippy domain types.
- source adapters writing playback state.
- playback code writing arbitrary UI state.

---

## M.2 Proposed `:shippy-core` tree

```text
shippy-core/
  src/main/kotlin/app/shippy/core/
    identity/
      RecordingId.kt
      ReleaseId.kt
      ArtistId.kt
      SourceId.kt
      AssetId.kt
      PlaylistId.kt
      PlaylistEntryId.kt
      QueueEntryId.kt
      MergeDecisionId.kt
      ExternalId.kt

    music/
      Recording.kt
      RecordingVersion.kt
      ArtistCredit.kt
      Release.kt
      ArtworkRef.kt
      Genre.kt
      MetadataValue.kt
      MetadataObservation.kt
      MetadataProvenance.kt
      CanonicalMetadata.kt
      UserMetadataOverride.kt

    source/
      SourceReference.kt
      SourceKind.kt
      SourceAvailability.kt
      SourceCapabilities.kt
      SourceQuality.kt
      SourceSelectionPolicy.kt
      PreparedSource.kt
      SourceFailure.kt
      SourceRanking.kt

    asset/
      MediaAsset.kt
      AssetKind.kt
      AssetOwnership.kt
      AssetVerification.kt
      AudioFingerprint.kt
      ManagedAssetKey.kt

    library/
      LibraryRelationship.kt
      SystemCollection.kt
      Playlist.kt
      PlaylistEntry.kt
      PlaylistSort.kt
      LibraryQuery.kt
      SaveDestinations.kt

    identitymatch/
      IdentityEvidence.kt
      MatchCandidate.kt
      MatchScore.kt
      MatchOutcome.kt
      MatchPolicy.kt
      RecordingVersionParser.kt
      MetadataNormalizer.kt
      MergePlan.kt
      MergeReducer.kt

    queue/
      QueueEntry.kt
      QueueContext.kt
      QueueSnapshot.kt
      QueueCommand.kt
      QueueReducer.kt
      ShuffleSeed.kt
      DeterministicShuffle.kt
      RepeatMode.kt

    playback/
      PlaybackSnapshot.kt
      PlaybackPhase.kt
      PlaybackCommand.kt
      PlaybackEvent.kt
      PlaybackReducer.kt
      PositionAnchor.kt
      PlayerObservation.kt
      PlaybackGeneration.kt
      PlaybackInvariant.kt

    history/
      ListeningSession.kt
      AudibleTimeAccumulator.kt
      ScrobbleEligibility.kt
      PlayHistoryEvent.kt

    crew/
      PortableRecordingDescriptor.kt
      PortableSourceHint.kt
      CrewQueueDescriptor.kt

    common/
      Clock.kt
      IdGenerator.kt
      Result.kt
      RetryPolicy.kt
      BoundedWindow.kt
```

### M.2.1 Core design rule

Core models are descriptive and immutable. Side effects occur outside the core. Reducers accept a state and an event/command and return a new state plus explicit effects.

Example:

```kotlin
data class PlaybackTransition(
    val snapshot: PlaybackSnapshot,
    val effects: List<PlaybackEffect>,
)
```

This makes playback behavior testable without constructing ExoPlayer, a service, Room, Hilt, a Fragment, three phones, and an offering to the Android lifecycle gods.

---

## M.3 Proposed `:shippy-data` tree

```text
shippy-data/
  src/main/kotlin/app/shippy/data/
    db/
      ShippyDatabase.kt
      ShippyDatabaseFactory.kt
      ShippySchema.kt

    db/entity/
      RecordingEntity.kt
      RecordingAliasEntity.kt
      ArtistEntity.kt
      RecordingArtistEntity.kt
      ReleaseEntity.kt
      RecordingReleaseEntity.kt
      SourceReferenceEntity.kt
      SourceExternalIdEntity.kt
      MediaAssetEntity.kt
      RecordingAssetLinkEntity.kt
      MetadataObservationEntity.kt
      UserMetadataOverrideEntity.kt
      LibraryRelationshipEntity.kt
      PlaylistEntity.kt
      PlaylistEntryEntity.kt
      PlayHistoryEntity.kt
      PlaybackCheckpointEntity.kt
      PlaybackCheckpointEntryEntity.kt
      LastFmOutboxEntity.kt
      IdentityDecisionEntity.kt
      ManagedAssetEntity.kt
      CachedDiscoveryEntity.kt

    db/dao/
      RecordingDao.kt
      SourceDao.kt
      AssetDao.kt
      MetadataDao.kt
      LibraryDao.kt
      PlaylistDao.kt
      SearchDao.kt
      HistoryDao.kt
      PlaybackCheckpointDao.kt
      LastFmOutboxDao.kt
      IdentityDecisionDao.kt
      ManagedAssetDao.kt

    db/view/
      CanonicalRecordingView.kt
      LibrarySongRowView.kt
      PlaylistEntryRowView.kt
      AlbumRowView.kt
      ArtistRowView.kt
      DuplicateAssetView.kt
      UnidentifiedRecordingView.kt

    db/fts/
      RecordingFtsEntity.kt
      PlaylistFtsEntity.kt
      FtsMaintenance.kt

    repository/
      RecordingRepositoryImpl.kt
      LibraryRepositoryImpl.kt
      PlaylistRepositoryImpl.kt
      SearchRepositoryImpl.kt
      HistoryRepositoryImpl.kt
      PlaybackCheckpointRepositoryImpl.kt
      MetadataRepositoryImpl.kt
      IdentityDecisionRepositoryImpl.kt

    migration/
      LegacyDatabaseReader.kt
      LegacyR15Importer.kt
      ImportPlan.kt
      ImportCheckpoint.kt
      ImportAudit.kt
      ImportVerifier.kt
      RedirectBuilder.kt
      MigrationFixture.kt

    backup/
      ShippyBackupCodec.kt
      BackupExporter.kt
      BackupImporter.kt
      BackupVerifier.kt

    paging/
      LibrarySongsPagingSource.kt
      PlaylistEntriesPagingSource.kt
      SearchPagingSource.kt
      HistoryPagingSource.kt

    maintenance/
      DatabaseMaintenance.kt
      TransientCatalogueGc.kt
      OrderKeyRebalancer.kt
      OrphanVerifier.kt
```

### M.3.1 Data API rule

Repositories return domain/read-model objects, never Room entities. DAOs are `internal`. Migration code is isolated and cannot become a permanent alternate repository.

---

## M.4 Proposed `:shippy-sources` tree

```text
shippy-sources/
  src/main/kotlin/app/shippy/sources/
    api/
      SourceAdapter.kt
      SearchSource.kt
      BrowseSource.kt
      PlaybackSource.kt
      DownloadSource.kt
      SourceRegistry.kt
      SourceDescriptor.kt
      SourceHealth.kt
      SourceResult.kt

    ingest/
      SourceTrackObservation.kt
      RecordingIngestor.kt
      IngestionResult.kt
      SourceIdentityResolver.kt
      MetadataObservationMapper.kt
      IngestionTransaction.kt

    local/
      LocalMediaEngine.kt
      MusikrLocalMediaEngine.kt
      LocalScanObservation.kt
      LocalAssetMapper.kt
      LocalArtworkResolver.kt
      LocalChangeObserver.kt

    managed/
      ManagedAssetRegistry.kt
      ManagedAssetReconciler.kt
      ManagedAssetScannerFilter.kt
      ManagedDownloadPublisher.kt

    provider/
      common/
        ProviderHttpTransport.kt
        ProviderRateLimiter.kt
        ProviderCache.kt
        ProviderRequestPolicy.kt
      jiosaavn/
        JioSaavnAdapter.kt
        JioSaavnDto.kt
        JioSaavnMapper.kt
        JioSaavnPlaybackResolver.kt
      youtube/
        YouTubeAdapter.kt
        YouTubeMusicAdapter.kt
        YouTubeExtractionGateway.kt
        YouTubePlaybackResolver.kt

    discovery/
      EnrichmentCoordinator.kt
      EnrichmentRequest.kt
      EnrichmentPolicy.kt
      ProviderEquivalenceFinder.kt
      MusicBrainzIdentitySource.kt
      LastFmIdentityHintSource.kt
      AcousticFingerprintSource.kt

    playback/
      PlaybackSourcePreparer.kt
      SourcePreparationCoordinator.kt
      SourceFallbackCoordinator.kt
      PreparedSourceLease.kt
      ExpiringStreamManager.kt

    cache/
      PlaybackCache.kt
      CachePolicy.kt
      CacheIndex.kt
      CacheEvictor.kt
      CachePromotionCoordinator.kt

    download/
      DownloadCoordinator.kt
      DownloadJob.kt
      DownloadStateMachine.kt
      DownloadVerifier.kt
      DownloadDestination.kt
      DownloadRecovery.kt
```

### M.4.1 Provider contract

A provider returns observations and prepared sources. It does not create canonical recordings by itself, mutate playlists, decide Library membership, or publish playback state.

---

## M.5 Proposed `:app` R16 tree

```text
app/src/main/java/app/shippy/
  ShippyApplication.kt
  di/
    CoreModule.kt
    DataModule.kt
    SourceModule.kt
    PlaybackModule.kt
    UiModule.kt

  playback/
    PlaybackCoordinator.kt
    PlaybackEffectRunner.kt
    PlaybackCommandRouter.kt
    PlayerEngine.kt
    media3/
      Media3PlayerEngine.kt
      Media3Transaction.kt
      Media3ObservationMapper.kt
      Media3EngineWindow.kt
      Media3DataSourceFactory.kt
      Media3ErrorMapper.kt
    service/
      ShippyPlaybackService.kt
      PlaybackServiceLifecycle.kt
      PlaybackForegroundPolicy.kt
    system/
      ShippyMediaSession.kt
      PlaybackNotification.kt
      PlaybackWidgetBridge.kt
      AndroidAutoBridge.kt
      QuickSettingsBridge.kt
      HeadsetCommandBridge.kt
    trace/
      PlaybackTraceRecorder.kt
      PlaybackTraceExporter.kt

  history/
    PlayHistoryCoordinator.kt
    LastFmScrobbleCoordinator.kt
    LastFmConnectionController.kt
    LastFmDiscoveryRepository.kt

  lyrics/
    LyricsCoordinator.kt
    LyricsState.kt
    LyricsSourceChain.kt

  crew/
    CrewCommandRouter.kt
    CrewPlaybackProjector.kt
    CrewPortableIdentityMapper.kt
    CrewPlaybackTelemetry.kt

  ui/
    shell/
      MainActivity.kt
      MainShellFragment.kt
      PrimaryDestination.kt
      MiniPlayerController.kt
    home/
      HomeFragment.kt
      HomeViewModel.kt
      HomeUiState.kt
    search/
      SearchFragment.kt
      SearchViewModel.kt
      SearchUiState.kt
    library/
      LibraryFragment.kt
      LibraryViewModel.kt
      LibraryUiState.kt
      songs/
      playlists/
      albums/
      artists/
      genres/
    playlist/
      PlaylistDetailFragment.kt
      PlaylistDetailViewModel.kt
      PlaylistDetailUiState.kt
    player/
      NowPlayingFragment.kt
      NowPlayingViewModel.kt
      NowPlayingUiState.kt
      QueueFragment.kt
      QueueViewModel.kt
      LyricsFragment.kt
    identify/
      IdentifyTrackFragment.kt
      IdentifyTrackViewModel.kt
      IdentifyTrackUiState.kt
    settings/
      SettingsFragment.kt
      CacheSettingsFragment.kt
      ProviderSettingsFragment.kt
      LastFmSettingsFragment.kt
    sheet/
      SongActionsSheet.kt
      SaveDestinationsSheet.kt
      PlaylistActionsSheet.kt
      SortFilterSheet.kt
      SourceInfoSheet.kt
    component/
      RecordingRowView.kt
      ArtworkView.kt
      LoadingStateView.kt
      EmptyStateView.kt
      ErrorStateView.kt
      ExpressiveBottomSheet.kt
      AlbumPaletteController.kt
```

Exact class names may change during implementation. The dependency roles may not.

---

## M.6 Legacy adapter containment

During migration, legacy code is contained behind four one-way bridges:

```text
Musikr data -> LocalMediaEngine -> R16 ingestion
Legacy playback commands -> R16 PlaybackCommandRouter
R16 playback snapshot -> temporary legacy display adapter
Legacy database -> one-time R16 importer
```

No bridge may write in both directions. Dual-write synchronization is prohibited because it produces two authorities and makes rollback unknowable.

---

## M.7 Source-tree deletion rule

A legacy package may be deleted only when:

1. all production callers have migrated;
2. migration or compatibility needs no longer require it;
3. focused and device tests pass;
4. system surfaces agree with R16 state;
5. the deletion commit contains no unrelated behavior change;
6. the status document records the removal.

The expected order is:

```text
new core introduced
    -> new path tested
    -> production read cut over
    -> legacy writes disabled
    -> soak/device verification
    -> legacy code deleted
```

Not:

```text
delete old thing
    -> discover what it secretly did
    -> rebuild it under pressure
```


# Appendix N — Current File Transformation Manifest

This is the initial disposition for the most consequential files in the Aug-19 snapshot. It is not a license to edit only these files. It tells the implementation agent where current responsibilities must move.

Disposition vocabulary:

- **KEEP:** retain design and implementation with normal hardening.
- **WRAP:** retain implementation below a Shippy-owned interface.
- **SPLIT:** divide an oversized/mixed-responsibility class.
- **MIGRATE:** move callers/state to the R16 replacement, then delete legacy path.
- **REWRITE:** preserve required behavior, replace implementation around R16 invariants.
- **DELETE:** remove after verified replacement.
- **REPAIR NOW:** fix before architectural migration because it obstructs reliable work.

---

## N.1 Playback and system integration

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `playback/state/PlaybackStateManager.kt` | **MIGRATE → DELETE** | Replace with `PlaybackCoordinator`, pure queue/playback reducer, and narrow system bridges. Do not preserve its dual `Song`/canonical listener API. |
| `playback/service/ExoPlaybackStateHolder.kt` | **SPLIT / REWRITE** | Separate Media3 engine commands, observations, source replacement, cache/data-source construction, crossfade, and error policy. Preserve proven audio behavior only. |
| `playback/PlaybackViewModel.kt` | **SPLIT / REWRITE** | Replace with `NowPlayingViewModel`, `QueueViewModel`, and `LyricsViewModel`, each projecting one `PlaybackSnapshot`. Never map the entire queue for the artwork pager. |
| `playback/PlaybackDisplayItem.kt` | **MIGRATE → DELETE** | UI reads canonical recording/queue read models directly. A local `Song` is not a nullable escape hatch inside the primary player model. |
| `shippy/playback/ShippyPlaybackController.kt` | **REWRITE** | Becomes `PlaybackCommandRouter`/use cases. It submits IDs/contexts, not pre-resolved full queues. |
| `shippy/playback/ProviderPlaybackLifecycle.kt` | **KEEP IDEA / REWRITE BOUNDARY** | Retain bounded current/lookahead preparation, but move it behind `PlaybackSourcePreparer` and generation-safe effects. |
| `shippy/domain/PlaybackResolver.kt` | **MIGRATE TO CORE** | Pure source-ranking policy using R16 sources/assets and explicit availability/quality. |
| `shippy/domain/PlaybackResolutionCoordinator.kt` | **SPLIT** | Source ranking remains pure; side-effectful provider/download/cache preparation moves to sources module. |
| `playback/service/PlaybackServiceFragment.kt` | **REWRITE SHELL** | Replace with process-scoped `ShippyPlaybackService` that owns coordinator/engine lifecycle and attaches observers. |
| `playback/service/MediaSessionHolder.kt` | **WRAP / MIGRATE** | Preserve Android MediaSession behavior while consuming only `PlaybackSnapshot` and submitting `PlaybackCommand`. |
| `playback/service/SystemPlaybackReceiver.kt` | **MIGRATE** | Translate Android intents into commands; no independent state. |
| `playback/service/PlaybackQuickSettingsTileService.kt` | **MIGRATE** | Read one snapshot/submit one command. |
| `widgets/WidgetComponent.kt` / `WidgetProvider.kt` | **MIGRATE** | Widget presentation derives from canonical playback state and RecordingId. |
| `playback/persist/PersistenceRepository.kt` | **MIGRATE → DELETE** | R16 checkpoint repository stores source-neutral QueueEntry identity and position anchor. |
| `shippy/persistence/playback/PlaybackCheckpointRepository.kt` | **REWRITE SCHEMA** | R16 checkpoint and deterministic restore. |
| `shippy/playback/CanonicalPlaybackRestoreCoordinator.kt` | **REWRITE** | Restore queue intent first, prepare only current/window sources, and reject stale/invalid entries deterministically. |
| `playback/service/PlaybackTransitionGuard.kt` | **DELETE AFTER ROUTER** | Crew/ordinary command ownership belongs in `PlaybackCommandRouter`, not a global transition boolean. |

### N.1.1 Immediate playback repair

Before replacement:

- assign expected new-playback identity before `setMediaItems`;
- make a new-player transaction atomic from Shippy's point of view;
- remove `304dp` seek-bar height;
- add reorder callback consumption to Last.fm;
- add a monotonic Last.fm listening tick or replace tracker early;
- write an exact regression test for audio/metadata/artwork identity.

---

## N.2 Canonical model and persistence

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `shippy/domain/Track.kt` | **REPLACE** | Split logical `Recording`, source observations, assets, releases, and artists. A provider result is not a Recording. |
| `shippy/domain/Identity.kt` | **REPLACE / EXPAND** | Add typed durable IDs for recording, source, asset, playlist occurrence, and queue occurrence. |
| `shippy/domain/QueueItemFactory.kt` | **MIGRATE TO CORE** | QueueEntry creation uses injected deterministic ID generator in tests. |
| `shippy/domain/LocalTrackCandidateMapper.kt` | **REWRITE AS INGEST ADAPTER** | Emit local source observation + exact asset; never directly define global canonical metadata truth. |
| `shippy/persistence/library/ShippyDatabase.kt` | **REPLACE WITH R16 DB** | New normalized schema and importer. Preserve v10 reader only in migration package. |
| `shippy/persistence/library/CanonicalTrackMetadataRepository.kt` | **REPLACE** | Canonical metadata becomes a computed/provenance-aware read model, not one replace-on-write row. |
| `shippy/persistence/library/LibraryRelationshipRepository.kt` | **REWRITE** | Relationships point to RecordingId; playlists contain PlaylistEntry occurrences. |
| `shippy/persistence/library/LibraryRelationshipDao.kt` | **REPLACE** | Scoped, indexed, paged queries; no relationship row as a prerequisite for every playlist membership. |
| `shippy/persistence/library/SavedProviderEntityRepository.kt` | **MIGRATE** | Saved albums/artists/playlists become explicit saved catalogue entities with provider provenance. |
| `shippy/persistence/download/DownloadJobRepository.kt` | **MIGRATE** | Jobs target RecordingId + SourceId and publish MediaAsset; they do not mutate canonical source metadata. |
| `shippy/persistence/download/DownloadJobDao.kt` | **REPLACE SCHEMA** | Durable state machine, resumable state, asset publication, verification. |
| `shippy/persistence/lastfm/LastFmScrobbleOutbox.kt` | **MIGRATE** | Preserve durable FIFO semantics; key entries by listening session/event. |
| `shippy/lyrics/LyricsCache.kt` | **MIGRATE** | Cache by recording identity plus matching fingerprint/provenance and staleness. |

### N.2.1 Existing database policy

The v10 database becomes an import source, not an object model R16 continues to elaborate. Do not add more tables to v10 for the new architecture unless required for a safe export bridge.

---

## N.3 Providers, search, and source ingestion

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `shippy/provider/MusicProvider.kt` | **KEEP CONCEPT / CHANGE TYPES** | Separate search/browse observation from playback-source preparation. Return source-owned DTO/domain observations, not canonical `Track`. |
| `shippy/provider/ProviderRegistry.kt` | **KEEP / HARDEN** | Registry of adapters and capabilities; stable health, settings, concurrency, and disable behavior. |
| `shippy/provider/ProviderSettings.kt` | **MIGRATE** | Provider enablement/priority/quality policy. Do not own source identity. |
| `shippy/provider/jiosaavn/JioSaavnProvider.kt` | **SPLIT** | DTO/parser, search/browse adapter, playback resolver, fixture tests. |
| `shippy/provider/youtube/YouTubeProvider.kt` | **SPLIT** | YouTube source adapter and extraction resolver; shared source-family handling with YT Music. |
| `shippy/provider/youtube/YouTubeMusicProvider.kt` | **KEEP THIN ADAPTER** | Distinct discovery surface, shared exact video/source identity where appropriate. |
| `shippy/search/UnifiedSearchRepository.kt` | **REWRITE** | Global search coordinator emits paged provider sections and local/library results; ingestion occurs on selection/save, not every result. |
| `search/SearchViewModel.kt` | **REWRITE** | Explicit `GLOBAL` or `LIBRARY` scope, cancelable/debounced queries, one immutable UI state. |
| `search/ProviderSearchItems.kt` | **MIGRATE** | Typed UI rows generated by presentation mapper; provider details remain optional secondary labels. |
| `settings/ProviderSettingsViewModel.kt` | **MIGRATE** | Reads source descriptors/health, submits settings actions; no direct provider internals. |
| `shippy/share/ProviderTrackSharing.kt` | **REWRITE** | Share original source link or portable Shippy recording descriptor; never share expiring stream URL. |

---

## N.4 Library and organization UI

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `home/list/PlaylistListFragment.kt` | **REWRITE / RENAME** | Dedicated Library playlists surface; no mixed Auxio device playlist graph and Shippy collections in one accidental ConcatAdapter. |
| `home/list/LibraryCollectionAdapters.kt` | **SPLIT** | Focused adapters/list items with stable IDs and DiffUtil; no screen policy embedded in adapter. |
| `shippy/library/LibraryCollectionsViewModel.kt` | **REWRITE** | Paged/scoped R16 Library projection and layout state. |
| `shippy/library/ShippyCollectionDetailViewModel.kt` | **REWRITE** | Query one collection's paged entry rows; no `observeAll()` joins in Kotlin. |
| `shippy/library/ui/ShippyCollectionDetailFragment.kt` | **REWRITE / KEEP VISUAL PARTS** | Playlist/system-collection detail using R16 state; search, selection, sorting, actions. |
| `home/HomeViewModel.kt` | **REWRITE PROJECTION** | Home sections from history/pins/recommendations; no Library duplication. |
| `MainFragment.kt` | **SPLIT / MIGRATE** | Shell/navigation/player-host responsibilities separated. |
| `fragment_home_list.xml` | **REPLACE SHARED USE** | Screen-specific actions belong to screen-specific layout/toolbar. |
| current device playlist UI | **IMPORT/COMPATIBILITY PATH** | Offer import/migration; do not keep two first-class playlist systems indefinitely. |

---

## N.5 Now Playing, actions, lyrics, and polish

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `playback/PlaybackPanelFragment.kt` | **SPLIT / REWRITE STATE BINDING** | Bind one NowPlayingUiState; retain useful view/motion code where sound. |
| `playback/PlaybackBarFragment.kt` | **MIGRATE** | Mini-player binds current QueueEntry/Recording and one progression state. |
| `playback/LyricsDialog.kt` | **REPLACE** | Full lyrics surface integrated with canonical lyrics state and current QueueEntry. |
| `playback/PlayerActionsViewModel.kt` | **REWRITE** | Song-action state/use cases; no duplicate action definitions. |
| `shippy/provider/ui/ProviderTrackActionsSheet.kt` | **REPLACE WITH GENERIC SHEET** | `SongActionsSheet` operates on RecordingId and capability state regardless of source. |
| `layout*/fragment_playback_panel.xml` | **REPAIR / RESPONSIVE REWORK** | Stable responsive constraints, bounded lyrics viewport, no fixed seek-bar void, album palette. |
| current side drawer/flyouts | **SELECTIVE REWORK** | Use consistent bottom sheet/drawer only where interaction semantics fit. |
| `shippy/lyrics/LyricsRepository.kt` | **KEEP CONCEPT / HARDEN** | One source chain and cache; result keyed by RecordingId/request generation. |
| `shippy/lyrics/MusixmatchBrokerLyricsSource.kt` | **KEEP BOUNDARY** | Remain disabled without owner-controlled broker; no embedded secret. |

---

## N.6 Last.fm

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `shippy/lastfm/LastFmScrobbleTracker.kt` | **REPLACE** | `LastFmScrobbleCoordinator` consumes committed listening-session events and durable outbox. |
| `shippy/lastfm/LastFmListenPolicy.kt` | **MIGRATE TO PURE CORE** | Accumulate audible monotonic time, excluding pause/buffer/seek jumps. |
| `shippy/lastfm/LastFmClient.kt` | **KEEP / HARDEN** | API transport, signing, accepted/ignored parsing, typed failures. |
| `shippy/lastfm/LastFmOverview.kt` | **MIGRATE** | Profile/recent/top/discovery repository with cache and canonical matching. |
| `shippy/home/LastFmHomeViewModel.kt` | **REWRITE AS HOME SOURCE** | Emits Home recommendation/history modules only when connected. |
| `settings/LastFmSettingsViewModel.kt` | **KEEP FLOW / MIGRATE STATE** | Connect/reconnect/disconnect and health; no Last.fm UI elsewhere when disconnected. |
| `AndroidKeystoreLastFmCredentialRepository.kt` | **KEEP / SECURITY REVIEW** | Preserve secure credential storage and add rotation/corruption tests. |

---

## N.7 Cache and downloads

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `shippy/media/cache/PlaybackCacheManager.kt` | **REWRITE POLICY LAYER** | Bounded source-byte cache with metrics, age/size policy, and explicit clear. |
| `shippy/download/ShippyDownloadWorker.kt` | **SPLIT** | WorkManager orchestration separate from transfer, verify, publish, and DB transitions. |
| `DownloadTransferEngine.kt` | **KEEP / HARDEN** | Bounded streaming transfer, cancellation, headers/range support. |
| `DownloadPublicationGate.kt` | **KEEP IDEA** | Publish only verified complete asset. |
| `DownloadDestinationReconciler.kt` | **REWRITE AROUND REGISTRY** | Reconcile assets through ManagedAssetRegistry and exact ownership keys. |
| `SafDownloadStorage.kt` | **KEEP / HARDEN** | SAF implementation behind `DownloadDestination`; test revoked permissions and atomic publication. |
| `CrewTemporaryDownloadStaging.kt` | **KEEP IDEA / MIGRATE** | Explicit promotion from verified temporary Crew asset to permanent job. |

---

## N.8 Crew

Crew is feature-rich and high-risk. R16 should preserve protocol work while replacing the playback and identity seam.

| Current file | Disposition | R16 responsibility |
|---|---|---|
| `crew/session/CrewSessionEngine.kt` | **KEEP CORE / SPLIT IF NEEDED** | Ordered session state, membership, coordinator, event log. Remove Android/playback leakage. |
| `crew/protocol/CrewControlProtocol.kt` | **VERSION / MIGRATE IDENTITY** | Protocol v4 portable recording/source/QueueEntry descriptors. |
| `crew/playback/CrewPlaybackBridge.kt` | **REWRITE** | Split command router, state projector, drift telemetry, and source readiness. |
| `crew/runtime/ActiveCrewRuntime.kt` | **KEEP PROCESS OWNER / HARDEN** | Session lifecycle and routes, not local playback authority. |
| `crew/media/CrewActiveMediaRuntime.kt` | **MIGRATE AS SOURCE/ASSET PROVIDER** | Temporary media becomes source/asset availability under R16. |
| LAN/Nearby/WebRTC/relay transport files | **KEEP / TEST** | Preserve transport implementation unless measured defects demand replacement. |
| `PlaybackTransitionGuard` coupling | **DELETE** | Command router establishes ordinary vs Crew authority explicitly. |

---

## N.9 First-party naming

During functional migration, namespace movement is secondary to architecture. After R16 paths are stable:

1. move new first-party packages directly under the final Shippy namespace;
2. migrate retained Auxio-derived classes into Shippy names only when touched or in the final naming phase;
3. retain copyright headers/NOTICE;
4. do not mechanically rename third-party/vendored Material or Media3 code;
5. do not change application ID without ADR-001 and the data/update plan.

“Everything is Shippy” means one product/domain architecture. It does not mean deleting lawful attribution or performing a dangerous package rename before the app can preserve its own data.


# Appendix O — Recommended Commit and Integration Sequence

This sequence is a reference implementation plan for the first major R16 branch. It deliberately creates proof points before destructive cutovers. Commit boundaries may be adjusted when a compiler-safe slice requires it, but the order of authority changes should remain.

Each commit should:

- compile in its intended scope;
- include focused tests;
- update status/ADR where relevant;
- avoid unrelated formatting churn;
- state whether production behavior changed;
- be independently reviewable.

---

## O.1 Safety and immediate stabilization

### Commit 01 — `chore(r16): freeze source snapshots and record checksums`

- add R15.3 control and Aug-19 snapshot references;
- record SHA-256;
- archive current screenshots;
- add `docs/r16/BASELINES.md`;
- no production change.

### Commit 02 — `test(regression): codify reported R15.3 failures`

Add focused tests/fixtures for:

- playlist play/shuffle identity;
- Continue Listening play behavior;
- playlist original order;
- Last.fm listening threshold;
- canonical queue reorder;
- shared Library layout control leakage where testable;
- managed download/local rediscovery.

Tests may initially fail and must be labelled as such only on an R16 development branch, never silently excluded from CI.

### Commit 03 — `fix(ui): restore responsive Now Playing geometry`

- restore seek bar to content height in every layout variant;
- add screenshot/layout assertion where practical;
- no architecture changes.

### Commit 04 — `fix(library): scope edit-order control to playlists`

- remove control from generic `fragment_home_list.xml`;
- place it in playlist-specific surface;
- verify Songs/Albums/Artists/Genres.

### Commit 05 — `fix(playback): establish expected identity before Media3 mutation`

- introduce temporary current-generation/new-playback transaction;
- set selected QueueItemId before `setMediaItems`;
- reject callbacks from older transaction;
- test callback ordering.

### Commit 06 — `fix(lastfm): use audible-time ticker and reorder-safe queue identity`

- temporary correctness patch;
- monotonic clock;
- pause/buffer/seek semantics;
- update canonical queue on reorder;
- real-account device test still required.

---

## O.2 Pure core foundation

### Commit 07 — `build(core): add pure shippy-core module`

- Kotlin/JVM only;
- strict dependency test;
- test fixtures.

### Commit 08 — `feat(core): add typed identity primitives`

Add and test:

- RecordingId;
- SourceId;
- AssetId;
- PlaylistId;
- PlaylistEntryId;
- QueueEntryId;
- ArtistId;
- ReleaseId;
- ExternalId.

### Commit 09 — `feat(core): add recording metadata and provenance model`

- raw observations;
- canonical values;
- user overrides;
- field confidence;
- source provenance.

### Commit 10 — `feat(core): add source and asset model`

- source kinds/capabilities/availability;
- asset ownership/verification;
- managed asset key;
- source quality.

### Commit 11 — `feat(core): add playlist occurrence model`

- duplicates allowed;
- sparse order keys;
- system collection semantics;
- sort modes separate from canonical order.

### Commit 12 — `feat(core): add queue reducer and deterministic shuffle`

- unique QueueEntryId;
- seed-based permutation;
- current identity invariant;
- move/remove/add under shuffle property tests.

### Commit 13 — `feat(core): add playback snapshot and reducer`

- command/event/effect types;
- generation;
- phases;
- position anchor;
- invariant checker.

### Commit 14 — `feat(core): add audible listening-session model`

- monotonic audible time;
- pause/buffer/seek exclusion;
- threshold rules;
- restart/session serialization rules.

### Commit 15 — `feat(core): add identity evidence and merge rules`

- normalizers;
- version markers;
- vetoes;
- scoring;
- auto/link/review thresholds;
- reversible merge plan.

---

## O.3 R16 data foundation

### Commit 16 — `build(data): add shippy-data module and empty R16 database`

- schema version 1 of the new database;
- export schema;
- in-memory creation test;
- no production cutover.

### Commit 17 — `feat(data): implement recording, source, asset, and provenance DAOs`

- constraints;
- indexes;
- cascade/restrict choices;
- DAO tests.

### Commit 18 — `feat(data): implement Library and playlist occurrence DAOs`

- duplicated playlist entries;
- transactional add/remove/reorder;
- system relationships;
- order-key rebalance.

### Commit 19 — `feat(data): add canonical read views and FTS`

- canonical recording view;
- songs row view;
- playlist row view;
- recording/playlist FTS;
- query-plan assertions.

### Commit 20 — `feat(data): add scoped repositories and PagingSources`

- playlist page;
- Library songs page;
- history page;
- local/global search APIs;
- no `observeAll` in screen read path.

### Commit 21 — `feat(data): add R15 legacy reader and import plan`

- read-only legacy connection;
- deterministic ID mapping;
- no writes to legacy DB.

### Commit 22 — `feat(data): implement resumable R15-to-R16 importer`

- checkpoints;
- audit rows;
- count/invariant verification;
- import failure recovery.

### Commit 23 — `test(data): add real-schema migration fixtures and backup round trip`

- v1–v10 fixtures where available;
- owner-device fixture;
- interrupted import;
- backup/export/import.

---

## O.4 Source ingestion and normalization

### Commit 24 — `build(sources): add shippy-sources module and source contracts`

- search/browse/playback/download capabilities;
- typed failures;
- no canonical Recording return from provider.

### Commit 25 — `feat(local): wrap Musikr behind LocalMediaEngine`

- exact local identity;
- local scan observation;
- no R16 caller imports `musikr.Song`.

### Commit 26 — `feat(assets): implement ManagedAssetRegistry`

- pre-publication registration;
- scanner recognition;
- moved/revoked asset states;
- exact download-to-recording association.

### Commit 27 — `feat(ingest): implement idempotent RecordingIngestor`

Order:

1. exact source key;
2. managed asset;
3. strong external ID;
4. fingerprint;
5. metadata candidate;
6. new recording.

### Commit 28 — `refactor(provider): migrate JioSaavn to source observations`

- split DTO/parser/search/playback;
- preserve fixture tests;
- provider result no longer defines RecordingId.

### Commit 29 — `refactor(provider): migrate YouTube and YouTube Music observations`

- shared video identity where exact;
- separate discovery provenance;
- source family handling;
- stream expiry.

### Commit 30 — `feat(identity): add event-driven enrichment coordinator`

- durable triggers for saved/downloaded/identified tracks;
- bounded concurrency;
- provider cooldown;
- negative decisions;
- no search/play blocking.

### Commit 31 — `feat(identity): add Identify Track domain use case`

- existing-catalogue search;
- progressive remote candidates;
- evidence explanation;
- confirm/link/unlink.

---

## O.5 Playback replacement

### Commit 32 — `feat(playback): implement PlaybackCoordinator with fake engine`

- actor/event loop;
- snapshot StateFlow;
- command channel;
- trace recorder;
- deterministic test clock.

### Commit 33 — `feat(playback): implement source-preparation effects`

- current first;
- bounded lookahead;
- stale generation rejection;
- source fallback.

### Commit 34 — `feat(playback): implement engine window and checkpoint model`

- large logical queue;
- small Media3 window;
- jump/rebuild;
- source-neutral checkpoint.

### Commit 35 — `test(playback): add randomized invariant and 10k-queue suites`

- random command traces;
- callback ordering;
- duplicate occurrences;
- source completion races;
- memory/time benchmark.

### Commit 36 — `refactor(media3): extract Media3PlayerEngine`

- commands and observations only;
- QueueEntryId as `mediaId`;
- Media3 shuffle disabled;
- player transaction identity.

### Commit 37 — `refactor(media3): integrate cache, headers, refresh, and fallback`

- safe data-source construction;
- expiring stream refresh;
- error mapping;
- no queue shrink on unavailability.

### Commit 38 — `feat(service): add ShippyPlaybackService and system snapshot bridge`

- process lifecycle;
- foreground policy;
- coordinator/engine attachment;
- trace export.

### Commit 39 — `feat(system): migrate MediaSession, notification, widget, and controls`

- all read canonical snapshot;
- all commands route centrally;
- no legacy manager reads.

### Commit 40 — `feat(playback): add legacy one-way compatibility adapter`

Only if needed for UI migration. It may project R16 state into legacy display contracts. It cannot accept state back or become permanent.

---

## O.6 Library, search, and offline ownership

### Commit 41 — `feat(library): cut over R16 Library read models behind flag`

- Songs;
- Playlists;
- system collections;
- stable IDs;
- Paging.

### Commit 42 — `feat(playlist): add canonical playlist detail and local search`

- occurrence order;
- duplicates;
- search/sort;
- multi-select;
- contextual actions.

### Commit 43 — `feat(search): add scoped global and Library search coordinator`

- local results first;
- paged providers;
- cancellation;
- result selection ingestion;
- recent searches.

### Commit 44 — `feat(download): migrate jobs to Recording/Source/Asset`

- publication transaction;
- ownership;
- managed scanner;
- remove semantics.

### Commit 45 — `feat(cache): add bounded cache policy and promotion`

- size/age settings;
- metrics;
- complete-cache promotion;
- clear cache vs remove download.

### Commit 46 — `feat(home): build R16 Home read model`

- Continue Listening;
- Recently Played horizontal;
- Recommendations;
- smart relevant modules;
- no redundant Your Music/static Downloads.

---

## O.7 Integrations and cleanup

### Commit 47 — `feat(lastfm): replace tracker with session-based coordinator`

- disconnected invisibility;
- Now Playing;
- durable outbox;
- accepted/ignored handling;
- discovery.

### Commit 48 — `feat(lyrics): migrate lyrics to RecordingId and QueueEntry generation`

- stale request protection;
- fixed preview;
- offline cache;
- full surface.

### Commit 49 — `feat(identify): add editor, provenance, and bulk cleanup UI`

- manual overrides;
- Identify Track;
- review queue;
- undo;
- duplicate asset view.

### Commit 50 — `refactor(crew): migrate Crew protocol and playback routing to R16 IDs`

- protocol v4;
- QueueEntry occurrence semantics;
- source hints;
- temporary assets;
- command router.

### Commit 51 — `refactor(ui): migrate Now Playing and mini-player to R16 snapshot`

- no legacy `Song`;
- album palette;
- one progression state;
- responsive layouts.

### Commit 52 — `refactor(ui): unify actions and bottom sheets`

- save destinations;
- song actions;
- playlist actions;
- sorting;
- no duplicate action labels.

### Commit 53 — `style(ui): apply restrained M3 Expressive polish`

- stable Material 1.14.0;
- theme rename;
- shapes/motion/state layers;
- light/dark;
- accessibility/reduced motion.

### Commit 54 — `perf(r16): add macrobenchmarks and baseline profile`

- startup;
- Library;
- playlist;
- search;
- play;
- Now Playing;
- 10k/50k fixtures.

### Commit 55 — `refactor(r16): remove legacy Library and playback authority`

- only after production flags and device gates pass;
- delete dual listeners/adapters;
- verify forbidden imports.

### Commit 56 — `refactor(brand): complete first-party Shippy naming`

- package/class/resource/theme names;
- preserve application data plan;
- preserve attribution.

### Commit 57 — `test(beta): run migration, device, offline, provider, and Crew matrices`

- evidence committed to status/handoff;
- failures block beta label.

### Commit 58 — `release(r16): publish beta candidate snapshot`

- signed artifact;
- checksums;
- SBOM/dependency list;
- migration notes;
- backup instructions;
- known limitations;
- no false “stable” claim.

---

## O.2 Integration cadence

Recommended merge cadence:

```text
core/data/source foundations
        ↓
fake-engine proof
        ↓
Media3 cutover
        ↓
system surfaces
        ↓
Library/search/download cutover
        ↓
integrations
        ↓
Crew
        ↓
polish/performance
        ↓
beta gate
```

Do not merge an incomplete dual-authority playback path into the release branch. Long-lived R16 work can remain behind compile-time/internal flags, but the branch must always state which authority is active.

---

## O.3 Review checkpoints

Require an architectural review after:

- Commit 15: pure model complete;
- Commit 23: importer/data proof;
- Commit 31: ingestion/identity proof;
- Commit 39: playback/system proof;
- Commit 46: Library/offline proof;
- Commit 50: integrations/Crew proof;
- Commit 55: legacy deletion;
- Commit 58: release candidate.

At each checkpoint, review:

- source-of-truth ownership;
- data loss risk;
- performance complexity;
- stale-result handling;
- rollback;
- test evidence;
- whether any convenience shortcut violated a locked decision.


# Appendix P — Current Repository Hotspot Inventory

File size is not automatically a defect. Large files become dangerous when they combine independent lifecycles or authorities. The Aug-19 snapshot contains roughly 82,000 lines of production Kotlin in `:app`; the files below deserve deliberate review because errors in them can affect broad product behavior.

| File | Approx. lines | R16 concern |
|---|---:|---|
| `crew/session/CrewSessionEngine.kt` | 1,694 | Large deterministic core. Preserve semantics, consider internal decomposition by event log, election, membership, and snapshot only if tests remain strong. |
| `playback/service/ExoPlaybackStateHolder.kt` | 1,191 | Combines Media3 commands, callbacks, queue projection, source refresh, crossfade, errors, headers, persistence. Must be split. |
| `crew/protocol/CrewControlProtocol.kt` | 1,188 | Protocol surface and compatibility risk. Version carefully; avoid cosmetic churn. |
| `playback/state/PlaybackStateManager.kt` | 1,087 | Central dual-world authority. Replace, do not continue adding listeners/fields. |
| `MainFragment.kt` | 961 | Shell/navigation/player/UI orchestration mixed. Split by responsibility. |
| `list/recycler/FastScrollRecyclerView.kt` | 907 | Mature UI infrastructure; retain unless measured defect. Do not rewrite merely because it is large. |
| `playback/PlaybackViewModel.kt` | 904 | Player, queue pager, position ticker, lyrics, commands, legacy/canonical state. Split. |
| `crew/nearby/CrewNearbyConnections.kt` | 875 | Transport-specific complexity; retain behind interface and test permissions/routes. |
| `crew/lan/CrewLanSignaling.kt` | 869 | Network lifecycle; retain if verified. |
| `playback/PlaybackPanelFragment.kt` | 855 | Binding, player behavior, lyrics, actions, layout/motion. Migrate to immutable UI state and split helpers. |
| `crew/transport/webrtc/CrewWebRtcTransport.kt` | 824 | Network/media complexity; avoid R16 identity rewrite leaking into transport details. |
| vendored `com/google/android/material/slider/WavySlider.kt` | 803 | Third-party/vendored implementation. Do not first-party rename or casually edit. |
| `crew/playback/CrewPlaybackBridge.kt` | 795 | Authority translation, drift, source preparation, command interception in one class. Rewrite into explicit components. |
| `crew/runtime/CrewLanJoinRuntime.kt` | 745 | Route/session lifecycle; retain behind runtime owner. |
| `crew/runtime/ActiveCrewRuntime.kt` | 744 | Correct process-scoped owner concept; keep and harden. |
| `detail/DetailViewModel.kt` | 716 | Legacy detail object graph; migrate screens to R16 read models rather than broadening it. |
| `library/ui/ShippyCollectionDetailFragment.kt` | 687 | Presentation and collection behavior mixed; replace state/data path, retain useful UI pieces. |
| `crew/ui/CrewFragment.kt` | 686 | UI should migrate to dedicated immutable state; network/session core remains elsewhere. |
| `crew/media/CrewActiveMediaRuntime.kt` | 676 | Temporary media should become a source/asset provider; avoid direct playback mutation. |
| `playback/service/MediaSessionHolder.kt` | 642 | Mature Android integration; migrate its input/output contract, not its whole behavior without cause. |
| `image/CoverView.kt` | 636 | Mature image view; retain, profile, and add canonical artwork inputs. |
| `home/list/LibraryCollectionAdapters.kt` | 629 | Several row types and behaviors; split into screen-specific adapters/components. |
| `download/ShippyDownloadWorker.kt` | 548 | Worker orchestration and transfer/publication concerns; split state machine from WorkManager wrapper. |

---

## P.1 Hotspot policy

A hotspot refactor must start with characterization tests and an explicit responsibility map. The preferred sequence is:

1. identify responsibilities;
2. extract pure logic first;
3. establish interfaces;
4. route one production path;
5. verify traces/device behavior;
6. remove old responsibility;
7. repeat.

Do not split classes by arbitrary line count. Split when:

- state ownership differs;
- lifecycle differs;
- side-effect boundary differs;
- test strategy differs;
- dependency direction is currently reversed;
- one change routinely causes unrelated regressions.

---

## P.2 High-risk cross-cutting seams

### Playback identity seam

Files:

- `PlaybackStateManager.kt`
- `ExoPlaybackStateHolder.kt`
- `PlaybackViewModel.kt`
- `PlaybackDisplayItem.kt`
- `ShippyPlaybackController.kt`
- `ProviderPlaybackLifecycle.kt`
- `CrewPlaybackBridge.kt`

Risk:

- queue order/index/source/UI identity disagreement;
- stale callback application;
- local/canonical dual state;
- system surfaces observing different representations.

### Library identity seam

Files:

- `Track.kt`
- `CanonicalTrackMetadataRepository.kt`
- `LibraryRelationshipRepository.kt`
- `ShippyCollectionDetailViewModel.kt`
- `LibraryCollectionsViewModel.kt`
- provider adapters
- local mapper
- download reconciler

Risk:

- provider-scoped duplicates;
- managed downloads reappearing as local files;
- whole-catalogue reads;
- ordering hidden in replace-all operations;
- metadata replacement without provenance.

### Integration seam

Files:

- `LastFmScrobbleTracker.kt`
- `RecentListeningTracker.kt`
- lyrics repository/view model
- notification/widget/session listeners
- Crew bridge

Risk:

- integrations infer state from callbacks rather than consuming committed events;
- missing continuous time semantics;
- stale queue snapshot;
- duplicate side effects after process death/retry.

---

## P.3 Files that should remain boring

The following classes/packages may be old, large, or unattractively named and still be worth preserving if they pass tests:

- Musikr scanning and exact local file identity;
- mature MediaSession and audio-focus behavior;
- codec/FFmpeg/ReplayGain paths;
- SAF/MediaStore filesystem primitives;
- RecyclerView fast scrolling;
- proven cover decoding/loading primitives;
- network transports whose protocol behavior is already deterministic;
- third-party/vendored components.

R16 is not rewarded for maximizing deleted lines. It is rewarded for eliminating conflicting truth and delivering a reliable product.


# Appendix Q — Detailed Manual and Physical QA Catalogue

Automated tests are necessary but cannot prove Android lifecycle, storage-provider, codec, Bluetooth, visual, or multi-device behavior. These cases are the minimum manual catalogue for the R16 beta candidate. Record device, Android version, build SHA, result, trace/log reference, and evidence.

---

## Q.1 Install, migration, and ownership

### MIG-001 — Clean install

- Install R16 on a device with no prior Shippy.
- Launch, grant only required permissions.
- Confirm empty state is useful, not an error.
- Add a local folder and play a song.
- Restart.
- **Pass:** no crash, no hidden setup dependency, state persists.

### MIG-002 — Upgrade from owner R15.3 database

- Back up R15.3 data.
- Install R16 as an in-place update where package decision permits.
- Run import.
- Compare:
  - playlists;
  - playlist order;
  - likes;
  - downloads;
  - saved provider entities;
  - Last.fm outbox/credentials;
  - lyrics cache where migrated;
  - current queue where compatible.
- **Pass:** audit reports zero unexplained loss and no duplicate managed downloads.

### MIG-003 — Interrupted import

- Kill process at several importer checkpoints.
- Relaunch.
- **Pass:** importer resumes or safely restarts, never produces partial authoritative state.

### MIG-004 — Import failure

- Corrupt one optional legacy row.
- **Pass:** valid user data imports; corrupt row is reported precisely; app does not clear everything.

### MIG-005 — Backup/export round trip

- Export R16 backup.
- Clear app data.
- Restore.
- **Pass:** user-owned identities, links, overrides, playlists, order, likes, and settings return.

### MIG-006 — Upgrade with revoked download-folder permission

- Revoke SAF permission before upgrade.
- **Pass:** metadata and playlist relationships survive; asset becomes unavailable with a recovery action.

### MIG-007 — Application-ID transition, if selected

- Install old package with real data.
- Install/migrate to new package according to ADR.
- **Pass:** explicit guided migration works; no false in-place-update assumption.

---

## Q.2 Local Library and file lifecycle

### LOC-001 — Tagged local file

- Import a file with title, artist, album, artwork, track/disc/year.
- **Pass:** canonical presentation is correct and provenance identifies local tags.

### LOC-002 — Untagged local file

- Import `Wiz_Khalifa_See_You_Again_928171.mp3`.
- **Pass:** Shippy shows an honest unresolved/local title and offers Identify Track; it does not fabricate artist/album.

### LOC-003 — File rename

- Rename an indexed file through external file manager.
- Rescan.
- **Pass:** exact identity is preserved when stable platform identity/fingerprint permits; no duplicate row.

### LOC-004 — File move within granted tree

- Move file to another folder.
- **Pass:** same asset/recording where recoverable; old locator removed.

### LOC-005 — File deletion

- Delete local file.
- **Pass:** asset becomes unavailable; canonical recording remains if playlist/like/history references it.

### LOC-006 — Same bytes in two locations

- Copy identical audio file.
- **Pass:** two assets can attach to one recording; Library normally shows one recording with duplicate-asset maintenance information.

### LOC-007 — Similar metadata, different audio

- Import studio and live files with same base title.
- **Pass:** they remain separate recordings.

### LOC-008 — Multiple formats

Test MP3, M4A/AAC, FLAC, WAV, Ogg/Opus, and device-supported edge formats.

- **Pass:** supported formats index/play; unsupported formats fail honestly without poisoning Library.

### LOC-009 — Artwork stress

- Import large embedded artwork, malformed artwork, no artwork.
- **Pass:** scrolling remains responsive; fallback is clean; malformed image does not crash.

### LOC-010 — Permission removal

- Remove a source folder or revoke permission.
- **Pass:** unavailable assets update without deleting unrelated recording/library relationships.

---

## Q.3 Identification, metadata editing, and deduplication

### IDN-001 — Manual Identify Track success

- Open metadata-poor local file.
- Search exact song.
- Select correct candidate.
- **Pass:** title/artist/album/artwork update everywhere; physical file remains; link is durable.

### IDN-002 — Progressive candidate search

- Search a song present on one provider but absent on another.
- **Pass:** early useful results appear; slower sources append without resetting selection/scroll.

### IDN-003 — Ambiguous version

- Candidate set contains studio, live, remix, acoustic.
- **Pass:** version evidence visible; no silent auto-link across veto.

### IDN-004 — Wrong manual link and Undo

- Link wrong recording.
- Undo/unlink.
- **Pass:** original raw metadata/source remains; playlists/history/asset are not lost.

### IDN-005 — User override precedence

- Identify track.
- Manually change display title/artwork.
- Refresh provider metadata.
- **Pass:** explicit override remains.

### IDN-006 — Merge provider duplicates

- Ingest exact same recording from YT Music and JioSaavn.
- Confirm high-confidence link/merge.
- **Pass:** one Library recording, two sources, existing references preserved.

### IDN-007 — Unmerge

- Split a mistaken canonical merge.
- **Pass:** references are reassigned according to explicit plan; no dangling redirect/cycle.

### IDN-008 — Negative decision

- Mark two similar candidates “not the same.”
- Run enrichment again.
- **Pass:** Shippy does not repeatedly propose the rejected pair unless evidence materially changes.

### IDN-009 — Bulk cleanup

- Scan a mixed library.
- **Pass:** certain/probable/ambiguous buckets; no mass destructive action; each change logged and reversible.

### IDN-010 — Metadata-source conflict

- Local tag and provider disagree on album/year/artwork.
- **Pass:** deterministic field policy; provenance inspectable; user can override.

### IDN-011 — ISRC conflict

- Same ISRC but clearly incompatible version metadata/duration.
- **Pass:** conflict is surfaced and does not blindly merge.

### IDN-012 — Fingerprint match

- Same recording with different tags/encoding.
- **Pass:** high-confidence asset-to-recording association; files remain distinct assets.

---

## Q.4 Search, Library, and playlists

### LIB-001 — Global search

- Search title/artist.
- **Pass:** local/library results appear quickly; provider sections load progressively; cancellation works.

### LIB-002 — Library-only search

- Launch Search from Library.
- **Pass:** no provider request; navigation remains in Library context.

### LIB-003 — Playlist-only search

- Open playlist and search.
- **Pass:** filters current playlist occurrences only; clear restores order and scroll sensibly.

### LIB-004 — Search burst cancellation

- Type and erase rapidly.
- **Pass:** old provider results never replace current query; no UI stalls.

### LIB-005 — Create/rename/delete playlist

- Exercise normal and edge names.
- **Pass:** validation clear; system collections protected.

### LIB-006 — Add same recording twice

- Add same recording twice to a user playlist.
- **Pass:** two PlaylistEntry occurrences exist and can be ordered independently.

### LIB-007 — Original order

- Append entries, close/reopen.
- **Pass:** original/custom order remains exact.

### LIB-008 — Temporary sorting

- Sort by title/artist/recently added.
- Return to custom order.
- **Pass:** canonical occurrence order was never mutated.

### LIB-009 — Reorder while filtered

- Search/filter playlist and attempt drag.
- **Pass:** behavior is either deliberately disabled with explanation or maps safely; no hidden corruption.

### LIB-010 — Batch removal

- Select multiple entries and remove from playlist.
- **Pass:** only selected occurrences removed; recording/library/download remain unless separately requested.

### LIB-011 — Pin/unpin

- Pin system/user collections.
- **Pass:** stable order; protected collection semantics; restart persists.

### LIB-012 — 10,000-song Library

- Scroll, fast-scroll, switch tabs, search.
- **Pass:** Paging works; memory stable; no whole-catalogue rebind.

### LIB-013 — 10,000-entry playlist

- Open and jump/scroll/search.
- **Pass:** first page appears within budget; no full source resolution/display mapping.

### LIB-014 — Empty and partial states

- Empty Library, provider offline, unresolved rows.
- **Pass:** useful states, no blank panel or misleading “no music.”

### LIB-015 — Save destinations sheet

- Like, add/remove from multiple playlists, create playlist.
- **Pass:** one coherent bottom sheet, correct selected states, transactionally consistent.

---

## Q.5 Playback, queue, and identity

### PB-001 — Single local play

- Tap local song.
- **Pass:** audio, mini-player, full player, notification, queue, artwork, lyrics identity agree.

### PB-002 — Provider play

- Tap provider result.
- **Pass:** playback starts without cross-provider enrichment delay.

### PB-003 — Download-preferred play

- Play recording with provider and verified download.
- **Pass:** download selected; UI remains same recording.

### PB-004 — Play context replacement

- Play a song from a playlist while another context is active.
- **Pass:** queue/context replace exactly once; selected occurrence starts.

### PB-005 — Continue Listening

- Tap play on Home card.
- **Pass:** player opens/updates and audio starts; no loaded-but-paused false state.

### PB-006 — Shuffle start

- Shuffle a playlist.
- **Pass:** selected/current QueueEntry identity matches producing audio and every UI surface.

### PB-007 — Shuffle toggle while playing

- Toggle on/off repeatedly.
- **Pass:** current QueueEntry does not change; logical order deterministic and recoverable.

### PB-008 — Next/Previous rapid

- Tap rapidly over local/provider/download mix.
- **Pass:** no stale metadata/artwork/lyrics; stale source completions rejected.

### PB-009 — GoTo distant queue entry

- Jump far outside engine window.
- **Pass:** window rebuilds, correct item commits, UI stays responsive.

### PB-010 — Duplicate queue recording

- Queue same recording three times.
- **Pass:** three QueueEntryIds; removing/moving one affects only that occurrence.

### PB-011 — Reorder current/nearby items

- Drag queue around current entry.
- **Pass:** current identity stable unless user explicitly selects another entry.

### PB-012 — Remove current entry

- Remove current queue entry.
- **Pass:** documented next/fallback behavior, one committed transition, no index crash.

### PB-013 — Source URL expiry

- Force current/next provider URL expiry.
- **Pass:** refresh occurs without creating new queue identity or shrinking queue.

### PB-014 — Provider failure fallback

- Fail selected provider.
- **Pass:** known verified alternate source selected; if none, bounded on-demand recovery; no wrong recording substitution.

### PB-015 — All sources unavailable

- **Pass:** clear unavailable state; queue occurrence remains; app does not loop/crash/skip unpredictably.

### PB-016 — Pause/resume and process background

- **Pass:** position anchor stable; no backward jump; system controls agree.

### PB-017 — Process death/restore

- Kill during local/provider/download playback.
- **Pass:** source-neutral queue restores; current source prepared again; invalid entries compact deterministically.

### PB-018 — Audio focus

- Incoming call/other media.
- **Pass:** pause/duck/resume follows settings and Android rules.

### PB-019 — Bluetooth/headset controls

- Next/previous/play/pause/disconnect/reconnect.
- **Pass:** commands route centrally and state remains coherent.

### PB-020 — Notification/widget/Android Auto

- Issue commands from each surface.
- **Pass:** no surface-specific queue or stale metadata.

### PB-021 — Crossfade/gapless

- Mixed formats/sources.
- **Pass:** audio behavior works without prematurely changing visible current identity.

### PB-022 — 10,000-entry shuffle performance

- **Pass:** play starts within budget; memory does not scale with prepared media for all entries.

### PB-023 — Rapid context replacement race

- Tap A, then B, then C before resolutions finish.
- **Pass:** C is the only committed intent; A/B callbacks are ignored.

### PB-024 — Error trace

- Trigger decoder/provider error.
- **Pass:** trace identifies QueueEntryId, RecordingId, SourceId, generation, player event, recovery action without secrets.

---

## Q.6 Cache and downloads

### OFF-001 — Stream cache hit

- Replay a recently streamed item.
- **Pass:** cache hit where valid; canonical identity unchanged.

### OFF-002 — Bounded eviction

- Fill beyond configured size.
- **Pass:** least valuable/old entries evicted; downloads never evicted.

### OFF-003 — Age eviction

- Use test clock.
- **Pass:** policy follows setting; recently active/current entries protected.

### OFF-004 — Clear cache

- **Pass:** permanent downloads and Library state remain; current playback handles invalidation safely.

### OFF-005 — Download from provider

- **Pass:** job resolves/transfers/verifies/publishes; asset attaches to existing recording.

### OFF-006 — Complete-cache promotion

- Fully cache song, press Download.
- **Pass:** valid bytes are reused/copy-promoted where supported; no duplicate network transfer.

### OFF-007 — Partial-cache download

- **Pass:** implementation either resumes safely or explicitly starts a verified download; no corrupt publication.

### OFF-008 — App restart mid-download

- **Pass:** job resumes/retries according to state; no ghost AVAILABLE row.

### OFF-009 — Destination permission revoked mid-transfer

- **Pass:** retryable failure/recovery prompt; no corrupted canonical state.

### OFF-010 — Scanner sees Shippy download

- **Pass:** ManagedAssetRegistry claims it; no ugly filename duplicate.

### OFF-011 — Remove download

- **Pass:** offline asset removed, recording/likes/playlists remain; provider source still usable.

### OFF-012 — User local file deletion

- **Pass:** Shippy does not delete user-owned local asset when removing a Library relationship.

### OFF-013 — Low storage

- **Pass:** transfer/cache stops safely; user receives actionable status; database remains truthful.

### OFF-014 — Quality preference

- **Pass:** selected bitrate/format respects policy where provider offers it; source identity remains stable.

---

## Q.7 Last.fm and listening history

### LFM-001 — Disconnected invisibility

- Fresh app without Last.fm.
- **Pass:** no Last.fm cards, status icons, or background requests outside Settings connection affordance.

### LFM-002 — Connect

- Complete auth.
- **Pass:** credentials stored securely; profile/discovery appears only after success.

### LFM-003 — Local identified scrobble

- **Pass:** clean canonical artist/title used regardless of file name.

### LFM-004 — Unidentified local item

- **Pass:** do not send obviously invalid metadata; status explains identification requirement only in relevant details, not as a constant nag.

### LFM-005 — Provider scrobble

- **Pass:** source provider does not alter canonical scrobble identity.

### LFM-006 — Download scrobble

- **Pass:** same canonical identity as streamed version.

### LFM-007 — Pause/resume

- **Pass:** only audible time counts.

### LFM-008 — Seek forward/backward

- **Pass:** seek jump not counted as listened time; ordinary playback after seek counts.

### LFM-009 — Buffering

- **Pass:** buffering time not counted.

### LFM-010 — Skip before threshold

- **Pass:** no scrobble.

### LFM-011 — Threshold

- **Pass:** scrobble queued exactly once at policy threshold.

### LFM-012 — Offline outbox

- **Pass:** durable entry survives process death; flushes oldest-first when network returns.

### LFM-013 — Ignored scrobble response

- **Pass:** accepted/ignored status parsed and surfaced in diagnostics; no infinite retry.

### LFM-014 — Invalid session

- **Pass:** reauth state, no credential loss, no duplicate outbox storm.

### LFM-015 — Shuffle/reorder

- **Pass:** tracker keys by QueueEntryId/listening session, never stale numeric index.

### LFM-016 — Recommendations

- **Pass:** canonicalized recommendation cards with artwork; play resolves through Shippy sources.

### LFM-017 — Disconnect

- **Pass:** Last.fm UI disappears, background work stops, queued policy handled explicitly, playback unaffected.

### LFM-018 — History privacy/clear

- **Pass:** clear local history behavior distinct from deleting Last.fm history; confirmation accurately states scope.

---

## Q.8 Lyrics

### LYR-001 — Synced lyrics

- **Pass:** current line follows playback position and correct recording.

### LYR-002 — Plain lyrics

- **Pass:** stable layout and readable fallback.

### LYR-003 — Rapid skip

- **Pass:** response for old QueueEntry cannot overwrite new lyrics.

### LYR-004 — Fixed preview

- **Pass:** one/multi-line changes do not resize surrounding player.

### LYR-005 — Full lyrics open/collapse/back

- **Pass:** Android Back closes hierarchy correctly; player continues.

### LYR-006 — Offline cache

- **Pass:** verified cached lyrics available offline; stale fallback labelled only when useful.

### LYR-007 — Wrong match protection

- **Pass:** duration/title/artist mismatch rejects cache/provider result.

### LYR-008 — Instrumental/no lyrics

- **Pass:** intentional state, not infinite loader.

### LYR-009 — Large text/TalkBack

- **Pass:** no clipped essential content, focus order sensible.

---

## Q.9 UI, M3 Expressive, and quality of life

### UI-001 — Light/dark contrast

- Audit every primary icon/control/state.
- **Pass:** no invisible settings icon or color-only status.

### UI-002 — Dynamic color

- Test several wallpapers/palettes.
- **Pass:** hierarchy/contrast remain Shippy-consistent.

### UI-003 — Album-derived player palette

- Bright/dark/monochrome/low-contrast artwork.
- **Pass:** restrained accents, readable text/controls, stable transition.

### UI-004 — Bottom-sheet consistency

- Song, playlist, save, sort, source info.
- **Pass:** same motion/shape/spacing/back/swipe semantics; actions not duplicated.

### UI-005 — Touch targets

- **Pass:** minimum targets, no tiny accidental actions.

### UI-006 — Mini/full player transition

- Tap/swipe/back/rotation.
- **Pass:** connected motion, no stale scroll, no jump/flash.

### UI-007 — Loading behavior

- Slow provider/artwork/DB.
- **Pass:** progressive placeholders; existing content does not vanish unnecessarily.

### UI-008 — Empty/error states

- **Pass:** contextual action, no developer jargon.

### UI-009 — Reduced motion

- **Pass:** decorative motion reduced/disabled; essential state remains clear.

### UI-010 — Small phone

- Test compact height/width.
- **Pass:** no 304dp-style void, clipping, or inaccessible controls.

### UI-011 — Tablet/large screen if in beta scope

- **Pass:** responsive hierarchy, sensible pane/width use, no stretched phone layout.

### UI-012 — RTL and localization resilience

- **Pass:** controls/order/text do not break; playback controls retain intended LTR semantics where necessary.

### UI-013 — Library tab customization

- **Pass:** only relevant controls appear on each tab; edit mode clearly entered/exited.

### UI-014 — State restoration

- Rotate/recreate each primary screen.
- **Pass:** query, scroll, sheet/dialog state handled deliberately, no duplicate commands.

---

## Q.10 Resilience, security, and diagnostics

### RES-001 — Offline launch

- **Pass:** local/download/Library/queue useful; provider modules degrade independently.

### RES-002 — Network flapping

- **Pass:** bounded retries/cancellation; UI not reset repeatedly.

### RES-003 — Clock change

- Change wall clock/time zone.
- **Pass:** playback/listening monotonic logic unaffected; display timestamps update appropriately.

### RES-004 — Database full/corrupt simulation

- **Pass:** safe failure/backup guidance; no silent reset.

### RES-005 — Secret redaction

- Export logs/traces.
- **Pass:** no Last.fm secret/session key, provider credential, invite secret, request auth header, or private path beyond policy.

### RES-006 — Provider malformed response

- **Pass:** one provider section fails, local/other providers remain.

### RES-007 — Memory pressure

- Background/foreground with large lists/artwork.
- **Pass:** process recreation works; no dependence on in-memory catalogue authority.

### RES-008 — ANR inspection

- StrictMode/profile heavy paths.
- **Pass:** no DB/network/fingerprint/large mapping on main thread.

### RES-009 — Crash recovery

- Crash/kill during active queue/source preparation.
- **Pass:** checkpoint and durable work remain consistent.

### RES-010 — Debug report usefulness

- **Pass:** report answers current QueueEntry/Recording/source/generation/cache/download/scrobble states without requiring source-code archaeology.

---

## Q.11 Performance evidence

Record p50/p95, device model, Android version, cold/warm state.

### PERF-001 — Cold launch to usable shell

### PERF-002 — Warm launch

### PERF-003 — Library tab switch

### PERF-004 — Open 100-entry playlist

### PERF-005 — Open 10,000-entry playlist

### PERF-006 — First local play

### PERF-007 — First provider play

### PERF-008 — Cached provider replay

### PERF-009 — Shuffle 10,000 entries

### PERF-010 — Search local 50,000 recordings

### PERF-011 — Provider search local-results-first time

### PERF-012 — Now Playing expand/collapse frame timing

### PERF-013 — Fast scroll/artwork memory

### PERF-014 — Background enrichment battery/network budget

### PERF-015 — Database import duration and peak memory

A result is not “fast” because it felt okay on the developer's flagship phone. It passes only against the recorded R16 budgets and reference-device set.

---

## Q.12 Crew physical catalogue

At minimum test:

- two devices, same LAN;
- three devices, same LAN;
- mixed Android versions;
- coordinator loss;
- member loss/rejoin;
- local-only source from non-host;
- provider source available to only some members;
- Push & Pull disabled/enabled;
- temporary media completion;
- permanent promotion;
- duplicate queue occurrences;
- reorder/remove/current selection;
- play/pause/seek/skip from each member;
- drift under route latency;
- LAN without internet;
- remote signaling/P2P;
- relay fallback;
- permission denial fallback;
- task removal/process restart;
- incompatible protocol version;
- secret/invite expiry.

Crew does not block non-Crew playback correctness, but public beta must not present unverified Crew paths as dependable. Feature gating is preferable to fiction.


# Appendix R — Official References and Design Evidence

The implementation agent should prefer primary documentation and source code over blog summaries. Product references are used to understand interaction patterns, not to reproduce protected branding or proprietary implementation.

Last reviewed for this specification: **2026-08-19**.

---

## R.1 Android architecture and state

### Android app architecture

- [Guide to app architecture](https://developer.android.com/topic/architecture)
- [Recommendations for Android architecture with Views](https://developer.android.com/topic/architecture/views/recommendations-views)
- [UI layer and UI state](https://developer.android.com/topic/architecture/ui-layer)
- [Offline-first data layer](https://developer.android.com/topic/architecture/data-layer/offline-first)

R16 implications:

- clear single source of truth per concern;
- unidirectional data flow;
- lifecycle-safe observable state;
- data ownership below UI;
- offline behavior modeled as normal capability, not an exceptional blank screen.

### Background work

- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)

R16 implications:

- use durable scheduled work for enrichment, maintenance, and retryable delivery;
- do not use WorkManager for latency-sensitive playback commands;
- make every worker idempotent and bounded.

---

## R.2 Room, Paging, and search

- [Paging 3 with network and database](https://developer.android.com/topic/libraries/architecture/paging/v3-network-db)
- [Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions)
- [Testing Room databases](https://developer.android.com/training/data-storage/room/testing-db)
- [Room database views](https://developer.android.com/training/data-storage/room/creating-views)
- [SQLite FTS support in Room](https://developer.android.com/reference/androidx/room/Fts4)

R16 implications:

- screen queries are scoped and paged;
- schema history and migration fixtures are release artifacts;
- database views/FTS provide read models instead of whole-catalogue Kotlin joins;
- migration success is verified with real data and invariants, not merely successful opening.

---

## R.3 Media3, caching, downloads, and preloading

- [Media3 ExoPlayer downloads](https://developer.android.com/media/media3/exoplayer/downloading-media)
- [Media3 network stacks and caching](https://developer.android.com/media/media3/exoplayer/network-stacks)
- [Media3 preload manager](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager)
- [Player events](https://developer.android.com/media/media3/exoplayer/listening-to-player-events)
- [Media3 `Player.Listener`](https://developer.android.com/reference/androidx/media3/common/Player.Listener)

R16 implications:

- Shippy owns logical queue identity; Media3 is the audio engine and observation source;
- cache/download ownership remains distinct;
- preload is bounded around likely playback, never the whole queue;
- callback ordering is treated as asynchronous and generation-sensitive;
- a current item is committed by QueueEntry identity, not inferred from an old numeric index.

---

## R.4 Performance and release optimization

- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [App startup](https://developer.android.com/topic/performance/vitals/launch-time)
- [Android vitals: ANRs](https://developer.android.com/topic/performance/vitals/anr)
- [Memory overview](https://developer.android.com/topic/performance/memory-overview)

R16 implications:

- startup, Library, search, and playback latency require measurements;
- a Baseline Profile is part of release engineering;
- no main-thread database/network/large-mapping work;
- low-end/reference-device results matter more than developer-machine optimism.

---

## R.5 Material 3 and Material 3 Expressive

- [Material Components for Android repository](https://github.com/material-components/material-components-android)
- [Material Components for Android releases](https://github.com/material-components/material-components-android/releases)
- [Material 3](https://m3.material.io/)
- [Material 3 in Android](https://developer.android.com/develop/ui/compose/designsystems/material3)

R16 implications:

- remain on the existing Views foundation for R16;
- update from alpha Material dependency to the reviewed stable line;
- use M3 Expressive selectively for hierarchy, motion, sheets, shape, and state;
- do not perform a Compose rewrite merely to access every newest component;
- custom Views implementations must preserve accessibility and reduced-motion behavior.

The Android Views Material library's maintenance direction makes new custom architecture inside old UI components a poor investment. Build reusable Shippy components only where they solve a concrete R16 interaction.

---

## R.6 Music identity

### MusicBrainz

- [Recording model](https://musicbrainz.org/doc/Recording)
- [ISRC](https://musicbrainz.org/doc/ISRC)
- [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API)
- [MusicBrainz search](https://musicbrainz.org/doc/MusicBrainz_API/Search)

R16 implications:

- model a specific recording separately from release, provider listing, and file;
- ISRC and MusicBrainz IDs are strong evidence, not infallible replacements for version/duration checks;
- respect rate limits and identify the client properly;
- cache identity results and do not query on every ordinary play.

### AcoustID / Chromaprint

- [AcoustID web service](https://acoustid.org/webservice)
- [AcoustID licensing](https://acoustid.org/license)

R16 implications:

- audio fingerprints are valuable for user-owned local/downloaded assets;
- fingerprint processing is background/user-requested work;
- licensing/API key/privacy decisions must be recorded before public integration;
- a fingerprint links assets to a recording candidate; it does not license metadata or audio.

---

## R.7 Last.fm

- [Last.fm API](https://www.last.fm/api)
- [Scrobbling overview](https://www.last.fm/api/scrobbling)
- [`track.scrobble`](https://www.last.fm/api/show/track.scrobble)
- [`user.getRecentTracks`](https://www.last.fm/api/show/user.getRecentTracks)
- [`track.getSimilar`](https://www.last.fm/api/show/track.getSimilar)

R16 implications:

- Last.fm is discovery/history/identity evidence and scrobbling, not an audio provider;
- scrobbles can represent local, downloaded, or provider playback;
- Shippy must calculate audible listening time correctly before delivery;
- durable outbox and response-body parsing remain required;
- Last.fm-related UI and work disappear when disconnected.

---

## R.8 Spotify product references

Use the current Android app and official support material during R16 visual/interaction QA:

- [Your Library](https://support.spotify.com/article/your-library/)
- [Sort and filter](https://support.spotify.com/article/sort-and-filter/)
- [Lyrics](https://support.spotify.com/article/lyrics/)
- [Play Queue](https://support.spotify.com/article/play-queue/)
- [Spotify product/newsroom](https://newsroom.spotify.com/)

Patterns worth studying:

- contextual Library search;
- compact horizontal Home modules;
- playlist hierarchy and action placement;
- Now Playing breathing room;
- connected lyrics/player surfaces;
- draggable sheets;
- optimistic-but-truthful action feedback;
- keeping source/service complexity out of ordinary listening.

Do not copy:

- Spotify branding/assets;
- service-specific promotion;
- podcast/audiobook/business requirements;
- opaque recommendation controls;
- interactions that conflict with Android conventions or Shippy's offline/local-first model.

---

## R.9 Samsung Music product references

- [Manage Samsung Music tabs](https://www.samsung.com/in/support/apps-services/how-to-manage-the-samsung-music-tabs/)
- [Import and export Samsung Music playlists](https://www.samsung.com/in/support/apps-services/how-to-import-and-export-samsung-music-playlists/)
- [Samsung Music support](https://www.samsung.com/in/support/apps-services/samsung-music/)

Patterns worth studying:

- calm local-library organization;
- Songs/Albums/Artists/Genres clarity;
- user-controlled tabs;
- predictable local sorting;
- metadata/library management;
- offline behavior that does not feel secondary.

Samsung Music is a reference for local ownership and restraint. Spotify is a reference for discovery, playlist interaction, and player flow. Shippy's final behavior should be coherent rather than an alternating imitation of both.

---

## R.10 Source-code evidence precedence

When documentation, tests, and runtime disagree, use this order:

1. recorded physical-device behavior;
2. R16 locked decisions and ADRs;
3. deterministic tests;
4. implementation source;
5. old documents/comments;
6. assumptions.

A comment saying “single source of truth” does not make a system one. The ownership must be visible in dependency direction and runtime state transitions.


# Appendix S — Glossary and Canonical Vocabulary

These words are part of the R16 contract. Do not use them interchangeably in code, database names, tests, logs, or UI requirements.

## Recording

A particular audible version of a musical work as the listener experiences it.

Examples:

- the studio recording of “See You Again”;
- a live recording of the same title;
- an acoustic version;
- a remix.

Those examples are separate Recordings when the audible/version identity differs materially.

## Work

The underlying composition/songwriting concept. R16 does not require a full musicological Work model for core playback. MusicBrainz may expose one, but Shippy's primary user object remains the Recording.

## Release

An album, single, EP, compilation, or other release context on which a Recording appears. The same Recording may appear on several Releases.

## Artist / Artist credit

An Artist is a canonical person/group identity when known. Artist credit is the ordered display credit for one Recording or Release, including join phrases such as “feat.” where available.

## SourceReference

A stable provider/local/Crew reference that claims access to or metadata about a Recording.

Examples:

- JioSaavn song ID;
- YouTube video ID;
- YouTube Music result pointing to that video;
- exact local scanner identity;
- active Crew source hint.

It is not necessarily playable at this instant.

## SourceTrackObservation

Raw data emitted by a provider or local adapter before Shippy decides whether it belongs to an existing Recording.

## MediaAsset

A concrete set of playable bytes or a verified byte-bearing object.

Examples:

- exact local file;
- permanent Shippy download;
- complete cache object;
- temporary Crew asset.

A remote provider listing is normally a SourceReference, not a MediaAsset, until it is prepared into a stream/lease.

## PreparedSource

A short-lived object the player can consume now: URI/data source, headers, MIME/codec, range/cache behavior, expiry, and source/asset identity.

It never becomes the Recording's durable identity.

## Managed asset

A file/object Shippy created or formally adopted and registered before publication. Managed ownership allows the scanner to recognize it and prevents rediscovery as an unrelated local song.

## Cache

Evictable source bytes retained for performance. Cache possession is not Library ownership and not a permanent download.

## Download

A verified permanent MediaAsset intentionally owned by the user through Shippy. Removing a download removes offline availability, not the Recording or its playlist/like relationships.

## Catalogue

Recordings Shippy currently knows enough about to search, display, match, play, or remember. Catalogue membership may be transient.

## Library

The user's durable organization and ownership relationships: likes, playlists, saved albums/artists/songs, downloads, pins, and user-managed metadata/identity decisions.

## System collection

A rule-driven Library view such as Liked, Downloads, or Local. It is not an ordinary mutable playlist row.

## Playlist

A named user collection.

## PlaylistEntry

One occurrence of a Recording inside a Playlist. It has its own ID and order key, allowing duplicates and stable reordering.

## Queue

The logical ordered playback context.

## QueueEntry

One occurrence in the Queue. It has its own ID even when the same Recording occurs several times.

## PlaybackSnapshot

The immutable authoritative product state for queue, current QueueEntry, phase, progression anchor, repeat, shuffle, source preparation, and error status.

## PlayerEngine

The narrow component that commands and observes Media3. It does not own the product queue or canonical metadata.

## Playback generation

A monotonically increasing token identifying a playback intent/transaction. Asynchronous results from an older generation are stale and must be ignored.

## Canonical metadata

Shippy's current best presentation values for a Recording, resolved from observations and overrides through deterministic policy.

## Raw metadata observation

What a particular source/file said, retained with provenance and confidence.

## User override

An explicit user-selected presentation value. It wins until removed.

## Provenance

Evidence describing where a metadata value or identity claim came from, when it was observed, and how trustworthy it is.

## External ID

A source-independent or source-specific identifier.

- Recording-level examples: MusicBrainz Recording ID, verified ISRC.
- Source-level examples: JioSaavn item ID, YouTube video ID.

External IDs are evidence; they do not override contradictory version/duration evidence automatically.

## Link

An association between a SourceReference/MediaAsset and a Recording.

## Merge

Combining two canonical Recording identities into one with redirect/reference migration. It must be transactional and reversible.

## Redirect

A durable mapping from an obsolete RecordingId to the surviving RecordingId after merge.

## Negative identity decision

Stored evidence that two candidates were reviewed and should not be linked/merged under the observed evidence.

## Identify Track

The user-facing workflow for connecting a metadata-poor or incorrect source/asset to the right canonical Recording.

## Enrichment

Bounded background/user-requested work that finds alternate sources, stronger identifiers, artwork, release data, or identity evidence.

## Availability

Whether a source/asset can currently be used. Availability can change without changing Recording identity.

## Scrobble

A Last.fm listening submission generated from a committed Shippy listening session after eligibility is reached.

## Listening session

The state for one QueueEntry's audible playback, including start timestamp and accumulated audible monotonic time.

## Crew

Shippy's collaborative listening mode. Crew owns shared session intent while active, but local Media3 remains a projection through the same R16 playback coordinator.

## Projection

A derived representation of authoritative state for a consumer such as Media3, a screen, notification, widget, Last.fm, or Crew. A projection may not become an independent authority.

## Read model

A query-optimized immutable presentation object assembled by the data layer for a specific screen/use case. It is not a mutable domain authority.

## Adapter

A boundary that translates an external/legacy system into Shippy contracts. An adapter contains foreign concepts rather than spreading them upward.

## Source of truth

The single authoritative owner of a concern. Caches, mirrors, projections, and UI state may derive from it but cannot independently mutate reality.

## Durable

Expected to survive process death/restart and remain until user/policy removes it.

## Transient

May be garbage-collected when no durable reference requires it.

## Beta-ready

Demonstrated against the release gates in this document, including physical-device and migration evidence. It does not mean “the feature compiles.”


# Appendix T — Standalone Master Prompt for the R16 Implementation Agent

The following prompt may be supplied with this document and the Aug-19 source snapshot. The specification remains the detailed authority; this prompt establishes execution behavior.

```text
You are implementing Shippy R16 from the source snapshot
Shippy-Source-R15.3-Stabilized-20260819.zip.

The canonical requirements and architecture are in:
Shippy_R16_Master_Architecture_and_Implementation_Spec.md

The GitHub commit
7fb431c9f3e5bad6805f7ff7b90127c67fd90b58
is the architectural/control baseline, not the implementation target.

MISSION

Transform the current R15.3 snapshot into a hardened beta-quality Shippy R16.
This is an architectural reset, not a conservative patch pass.

Quality, correctness, data safety, identity consistency, performance,
recoverability, usability, and polish take precedence over minimizing code
changes. Large changes are allowed when the target architecture requires them.
Do not rewrite proven infrastructure without evidence.

LOCKED SYSTEM RULES

1. One canonical Recording represents one specific audible recording/version.
2. Provider results, local files, downloads, cache objects, and Crew media are
   sources/assets attached to a Recording, not separate definitions of the song.
3. Provider IDs and URLs are never RecordingId.
4. PlaylistEntryId and QueueEntryId represent occurrences and preserve duplicates.
5. Downloads never create duplicate Recordings.
6. Shippy-managed files are registered before publication and recognized by the
   local scanner.
7. Metadata has raw observations, canonical resolution, and user overrides with
   provenance. User overrides win.
8. Matching and merging are conservative, evidence-based, reversible, and tested.
9. Catalogue knowledge, Library membership, cache, downloads, and availability
   are separate states.
10. Ordinary Search and Play never wait for cross-provider reconciliation.
11. Saved/downloaded/identified content can trigger bounded event-driven
    enrichment.
12. One process-scoped PlaybackCoordinator owns logical queue and playback state.
13. Media3 is a player engine/projection, not another product-state authority.
14. Shippy owns shuffle order by QueueEntryId; Media3 shuffle is disabled.
15. Every player-facing surface consumes the same immutable PlaybackSnapshot.
16. Only the current and bounded nearby queue entries receive expensive source
    preparation.
17. Last.fm is optional, hidden when disconnected, and can scrobble canonical
    local/download/provider/Crew listening using audible monotonic time.
18. Global Search, Library Search, and Playlist Search are explicit scopes.
19. Large lists use scoped indexed database queries and Paging.
20. Everything first-party is conceptually Shippy. Preserve legal attribution.
21. R16 remains Views/Fragments; do not start a Compose rewrite.
22. Use stable Material Components and restrained Material 3 Expressive polish.
23. Do not clear user data to solve migration.
24. Do not maintain two writable authorities during migration.
25. No beta claim without the document's release evidence.

STARTUP PROCEDURE

A. Verify source ZIP checksum and repository state.
B. Create immutable tag/reference for the source.
C. Read the entire master specification before architecture changes.
D. Read current AGENTS.md and canonical docs, but treat the R16 specification as
   authoritative where conflicts exist.
E. Build/record the current source once if the environment permits.
F. Create focused tests/reproductions for known regressions.
G. Decide/record blocking ADRs before dependent code.
H. Work through the implementation phases in order.

EXECUTION METHOD

For every coherent slice:

1. State the exact invariant or defect.
2. Inspect all current callers and persistence implications.
3. Add/adjust deterministic tests first where practical.
4. Implement the narrowest complete architectural slice.
5. Run focused checks.
6. Review the diff for forbidden dependencies and duplicate authority.
7. Update R16 status/migration documentation.
8. Commit with one coherent message.
9. Do not move to the next authority cutover until the phase exit gate passes.

Do not hide incomplete behavior behind broad exception swallowing, placeholder
success, fake data, disabled tests, or comments claiming future verification.

CURRENT URGENT REGRESSIONS

Before large migration work, repair/codify:

- 304dp seek-bar height in the compact Now Playing resource;
- Edit-order control leaking into unrelated Library tabs;
- pending new-playback identity being established after Media3 playlist mutation;
- Last.fm listening policy lacking a continuous audible-time clock;
- Last.fm stale queue identity after reorder;
- whole-catalogue composition for one collection;
- entire playback queue mapped into display models;
- managed downloads rediscovered as local metadata-poor duplicates.

ARCHITECTURE IMPLEMENTATION ORDER

1. safety/evidence and immediate regressions;
2. pure shippy-core types/reducers/invariants;
3. R16 data schema and verified importer;
4. source ingestion and managed-asset registry;
5. PlaybackCoordinator with fake engine;
6. Media3 engine adapter and system surfaces;
7. canonical Library/Search/read models;
8. downloads/cache;
9. identification/edit/deduplication;
10. Last.fm/history;
11. lyrics;
12. Crew identity/playback bridge;
13. M3 Expressive/QoL polish;
14. performance hardening;
15. legacy deletion/naming/beta gate.

REVIEW REQUIREMENTS

At minimum, obtain explicit architecture review after:

- pure core;
- schema/importer;
- ingestion/identity;
- playback/system integration;
- Library/download cutover;
- Crew migration;
- legacy deletion;
- release candidate.

STOP AND ESCALATE ONLY WHEN

- a locked owner decision is genuinely required;
- real migration data has an unmapped user-owned concept;
- provider/API/legal constraints contradict the design;
- physical behavior contradicts deterministic tests;
- an identity match would require lowering safety thresholds;
- a visual decision exceeds the specification's QoL boundary;
- release scope requires a deliberate feature gate.

Otherwise, continue autonomously and record assumptions.

DELIVERABLES

- R16 source;
- exported Room schemas and migration fixtures;
- ADRs;
- focused and full test reports;
- performance measurements and Baseline Profile;
- device acceptance evidence;
- backup/restore path;
- migration audit;
- playback trace/debug report;
- beta checklist;
- signed release candidate and checksums where signing access exists;
- updated docs/status/changelog;
- explicit list of remaining unverified behavior.

DEFINITION OF SUCCESS

A listener can search, play, shuffle, queue, save, identify, edit, download,
go offline, restart, and continue while every Shippy surface agrees on the exact
recording and queue occurrence. Large libraries remain responsive. Provider and
Last.fm failures degrade independently. Shippy's own downloads never become
duplicates. User data survives migration. The app is demonstrably beta-ready,
not merely structurally ambitious.
```

---

## T.1 Required agent progress report format

At each phase boundary, report:

```markdown
# R16 Phase Report — <phase>

## Scope completed

## Invariants now enforced

## Files/modules changed

## Legacy paths still active

## Data migration impact

## Tests run
- command
- result
- skipped/unavailable reason

## Physical verification
- device/OS
- cases
- evidence

## Performance
- metric
- device
- result
- budget

## Known failures / unverified areas

## Architecture deviations
- ADR/reference

## Next exact slice
```

The report must distinguish inspected, implemented, automated-tested, instrumented, device-tested, and performance-verified states.

---

## T.2 Required pull-request description format

```markdown
## R16 slice

## Problem / invariant

## Architectural decision

## Before

## After

## Migration / compatibility

## Test evidence

## Performance impact

## Risk and rollback

## Screenshots / traces

## Spec sections satisfied

## Remaining work
```

A pull request that changes an authority boundary without documenting migration and rollback is incomplete.


# Appendix U — Specification Governance and Supersession

## U.1 Authority

For R16, this document supersedes conflicting pre-R16 statements in:

- `docs/ARCHITECTURE.md`;
- `docs/PRODUCT_SPEC.md`;
- `docs/IMPLEMENTATION_PLAN.md`;
- `docs/UX.md`;
- stabilization plans and audit reports;
- inline comments that describe legacy ownership;
- agent-generated completion claims.

It does not silently supersede:

- legal/license notices;
- third-party licenses;
- security policies unless explicitly updated;
- provider terms;
- platform requirements verified after this document's publication.

---

## U.2 Change process

A change to a locked R16 decision requires:

1. a named ADR;
2. the concrete problem/evidence;
3. alternatives;
4. effect on identity, migration, playback, performance, UI, and tests;
5. owner approval for product-level changes;
6. update to this document or an explicit successor;
7. update to traceability and beta gates.

A convenience implementation choice does not silently alter the architecture.

---

## U.3 Living companion documents

During implementation maintain:

```text
docs/r16/STATUS.md
docs/r16/ADRs/
docs/r16/MIGRATION.md
docs/r16/PERFORMANCE.md
docs/r16/DEVICE_TESTS.md
docs/r16/PROVIDER_STATUS.md
docs/r16/CREW_STATUS.md
docs/r16/KNOWN_ISSUES.md
docs/r16/RELEASE_HANDOFF.md
```

### `STATUS.md`

Must say:

- active phase;
- active authorities/feature flags;
- exact last passing gate;
- current blockers;
- next concrete slice;
- verification level.

### `MIGRATION.md`

Must say:

- source schema/application versions;
- deterministic mappings;
- data not imported and why;
- import audit format;
- downgrade behavior;
- backup/restore instructions.

### `PERFORMANCE.md`

Must contain measured results, not adjectives.

### `DEVICE_TESTS.md`

Must contain device/OS/build/test IDs and evidence.

---

## U.4 Documentation truth rule

When source behavior changes:

- tests change in the same slice;
- canonical docs change in the same slice;
- status changes in the same slice;
- release notes change before release.

Do not leave a “future cleanup” where documentation still promises a source of truth that no longer exists.

---

## U.5 Versioning this specification

Suggested header additions after implementation begins:

```text
Specification revision: 1.0
Architecture freeze commit: <sha>
Last amended: <date>
Approved ADR set: <list>
```

A later revision should include a change log. Do not overwrite important architectural history without recording it.

---

# Appendix V — R16 Final Release Contract

This section is the compact final contract. Every line must be true before the R16 build is called a stable beta candidate.

## V.1 Identity

- [ ] One canonical Recording represents each specific audible version.
- [ ] Provider records are sources, not Recording identity.
- [ ] Local files and downloads are assets, not independent duplicate songs.
- [ ] Managed downloads cannot be rediscovered as ugly local duplicates.
- [ ] Exact provider/local/asset ingestion is idempotent.
- [ ] Automatic matching is conservative and reversible.
- [ ] User overrides and manual identity decisions survive refresh and restart.
- [ ] PlaylistEntryId and QueueEntryId preserve occurrence identity.
- [ ] Merge redirects are acyclic and reversible.

## V.2 Playback

- [ ] One PlaybackCoordinator owns logical queue and playback state.
- [ ] Media3 is a projection/engine, not an authority.
- [ ] Audio, title, artwork, lyrics, queue, notification, widget, Android Auto, Last.fm, and Crew agree on QueueEntryId.
- [ ] Shuffle is deterministic and current identity remains stable on toggle.
- [ ] Rapid commands and stale async results cannot overwrite newer intent.
- [ ] Large queues do not require full source preparation or display mapping.
- [ ] Source failure/fallback never substitutes an uncertain recording.
- [ ] Process death restores source-neutral intent safely.
- [ ] System controls route through the same command path.

## V.3 Library and offline ownership

- [ ] Library, catalogue, cache, downloads, and availability are distinct.
- [ ] Search results do not pollute Library automatically.
- [ ] User playlists preserve exact occurrence order and duplicates.
- [ ] System collections are rule-driven and protected.
- [ ] Large lists use indexed scoped Paging queries.
- [ ] Global, Library, and playlist search have explicit scopes.
- [ ] Download publication is verified and transactional.
- [ ] Cache is bounded/evictable; downloads are durable.
- [ ] Clear cache, remove download, remove from Library, and delete local file are distinct actions.
- [ ] Real R15.3 user data migrates with an auditable zero-unexplained-loss result.

## V.4 Integrations

- [ ] Last.fm is invisible when disconnected.
- [ ] Connected Last.fm uses canonical identity and audible monotonic time.
- [ ] Local, provider, download, cached, and Crew playback can scrobble when metadata is valid.
- [ ] Offline outbox survives and does not duplicate.
- [ ] Lyrics are keyed to current Recording/QueueEntry generation and cannot go stale after skip.
- [ ] Crew uses portable R16 identity and cannot create a second local playback authority.
- [ ] Provider failure is isolated from Local/Downloads/other providers.

## V.5 Performance

- [ ] No main-thread network, DB, fingerprint, or whole-catalogue mapping.
- [ ] Library/playlist/search/play budgets are measured on reference devices.
- [ ] 10k queue and 50k Library fixtures pass memory/latency tests.
- [ ] Artwork/palette work is cached and bounded.
- [ ] Background enrichment is event-driven, constrained, cancellable, and observable.
- [ ] Baseline Profile and macrobenchmarks are included in release engineering.
- [ ] No ANR/leak regression remains open at beta severity.

## V.6 Product experience

- [ ] Current 304dp Now Playing regression is gone in all responsive variants.
- [ ] Screen-specific controls do not leak into unrelated tabs.
- [ ] Home is useful and compact rather than a vertical dump of Library categories.
- [ ] Sheets, drawers, back behavior, spacing, touch targets, loading, empty, and error states are consistent.
- [ ] Light/dark/dynamic/album-derived colors remain readable.
- [ ] M3 Expressive additions are restrained and purposeful.
- [ ] Reduced motion, TalkBack, large text, contrast, and touch-target checks pass.
- [ ] The app remains recognizably Shippy; R16 is not an unrelated visual redesign.

## V.7 Release evidence

- [ ] Clean install tested.
- [ ] Upgrade/import tested from real owner data.
- [ ] Backup/restore tested.
- [ ] Android 7 through current supported versions covered according to matrix.
- [ ] Local/provider/download/cache/offline/process-death paths tested physically.
- [ ] Last.fm tested on a real account.
- [ ] Crew public paths tested on required devices/routes or gated honestly.
- [ ] Release signing/update path tested.
- [ ] Crash/ANR/performance reports reviewed.
- [ ] Known limitations published.
- [ ] No disabled/ignored failing test hides a release requirement.
- [ ] Product owner completes final acceptance journeys.

---

## V.8 Final standard

R16 is complete when the user can treat Shippy as one music system:

```text
find a recording
    -> play it immediately
    -> save or ignore it
    -> attach more sources over time
    -> download it without duplication
    -> organize it without losing order
    -> identify and clean poor metadata
    -> go offline
    -> restart
    -> continue
```

The implementation may be complex. The experience must not be.

The beta label is earned when this behavior is proven across the specified data, lifecycle, device, integration, and performance boundaries. It is not earned by the size of the refactor, the number of generated tests, or the confidence of the agent that wrote it.

