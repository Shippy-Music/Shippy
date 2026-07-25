# Shippy Product Specification

**Status:** Canonical product source of truth  
**Owner:** Shippy team  
**Last updated:** 2026-07-25  
**Foundation:** Auxio, native Android/Kotlin  
**Platform:** Android only for the foreseeable future

Read this document before making product or architecture decisions. When another
document conflicts with it, this document wins unless the owner explicitly
changes the decision and this file is updated in the same change.

## 1. Mission

Build **Shippy**, the best open-source, Android-native music application for
people who want local music, downloads, streaming providers, and collaborative
listening to feel like one calm, premium product.

Shippy must feel as deliberate and native as Auxio, use the clearest interaction
patterns found in Spotify, preserve the useful multi-source capabilities proven
by Bloomee, and make its difficult networking invisible to the listener.

The product is complete only when the normal player and **Crew** both feel like
parts of the same application. Crew is not a demo, placeholder, or deferred
marketing screen.

## 2. Locked Decisions

These decisions came directly from the product owner.

1. Shippy is Android-only for the foreseeable future.
2. The actual Auxio Kotlin codebase is the implementation foundation.
3. Bloomee is a capability donor and behavioral reference, not the UI or runtime
   foundation.
4. The primary destinations are **Home**, **Search**, **Library**, and **Crew**.
5. Now Playing is not a navigation destination. A persistent mini-player expands
   into the full player and Android Back collapses it before leaving the app.
6. The actual Auxio UI is retained and extended as Shippy's visual/runtime
   foundation, not merely imitated. Its components, layouts, motion, player
   choreography, and native restraint stay wherever they fit the product.
   Spotify is the interaction and information-architecture benchmark for the
   Shippy-specific changes.
7. `Local`, `Downloads`, and `Liked` appear as permanent playlist-like system
   collections and cannot be deleted.
8. User-created playlists can be created, pinned, sorted, edited, and deleted.
9. Local music remains a distinct device realm. It is never silently substituted
   for a provider track merely because metadata looks similar.
10. Provider playback resolution is:
    `Crew temporary cache -> downloaded -> preferred provider -> fallback provider
    -> active Crew peer -> unavailable`.
11. A direct download affordance is visible where it matters. The overflow menu
    must not duplicate it as “Available Offline.”
12. The save affordance is a plus button. One tap saves to Liked; a subsequent
    interaction can change playlist membership.
13. The sleep timer belongs in a secondary utility surface, not among the
    essential player controls.
14. Crew is collaborative. By default, every participant may play, pause, seek,
    choose a track or playlist, and add, remove, or reorder queue items.
15. Crew works over a LAN without internet and remotely when connectivity exists.
16. Crew shares state and metadata first. Every device resolves its own playable
    copy when possible.
17. `Push & Pull` is one user-facing setting. It automatically accepts temporary
    media only from the active Crew.
18. Any active Crew member—not only the creator or coordinator—may supply a local
    or otherwise available track to peers.
19. Peer-streamed media is temporary by default. Keeping it requires an explicit
    Download action.
20. Crew supports QR/link joining, a small low-latency reaction system, and an
    optional hosted relay for reliability and larger groups.
21. The complete application is the target. Engineering may proceed in stages,
    but staging must not quietly remove final product requirements.
22. AI is not part of the product unless a later explicit decision adds a
    narrowly useful feature.
23. Full chat and a general-purpose social network are not part of Crew.
24. Accessibility implementation is a release-hardening pass after the
    functional application and product polish are complete. It remains required
    before public release, but does not block the current implementation pass.

## 3. Product Principles

### 3.1 Listening intent comes first

The listener chooses music, not infrastructure. Provider selection, cache
choice, peer selection, retry behavior, and drift correction stay behind a
single understandable action.

### 3.2 Native calm

Premium means strong hierarchy, excellent typography, responsive gestures,
correct Android behavior, restrained motion, and no visible jank. It does not
mean decorative blur, unnecessary gradients, or overloaded menus.

### 3.3 Offline is a normal state

Local playback, downloads, queue editing, cached metadata, and LAN Crew must
remain useful without internet. “Offline” is not treated as an error page.

### 3.4 One authority per concern

There is one playback state, one queue state, one library truth, and one ordered
Crew event stream. Screens subscribe to them; screens do not invent competing
copies.

### 3.5 Seamlessness is engineered

Shippy may perform substantial work in the background—availability checks,
prefetch, peer negotiation, clock synchronization, retries, coordinator
handoff—but should expose only progress or a decision the listener genuinely
needs to understand.

### 3.6 Accessibility is a release gate

The first implementation pass prioritizes complete, correct product behavior and
polish. TalkBack, large text, touch targets, focus order, contrast, reduced
motion, and accessible controls are completed together in the dedicated
release-hardening pass before public release.

### 3.7 Exactly enough engineering

Prefer the smallest architecture that correctly supports the locked product.
Do not create abstraction layers for hypothetical platforms or features. Do not
take shortcuts that create two sources of truth or fake a required capability.

## 4. Primary Information Architecture

### Home

Home answers “what should I continue or play now?” It contains recent listening,
continue listening, pinned shortcuts, useful provider recommendations, and
active/recent Crew context. It must not become a grid of every feature.

### Search

Search finds songs, albums, artists, and playlists from enabled providers.
Local results may appear in a clearly labelled **On this device** section for
convenience, but remain local items and are never silently merged with provider
records.

### Library

Library contains saved music and user organization:

- Permanent system collections: Liked, Downloads, Local
- User playlists
- Saved albums
- Saved artists
- Individual saved songs
- Pinned items
- Search, filtering, and sorting

### Crew

Crew creates or joins a synchronized listening session. It shows the current
session, members, join/share actions, connection health when relevant, shared
queue, and lightweight reactions. It is not a chat room.

### Mini-player and Now Playing

The mini-player remains attached above primary navigation while media is active.
Tapping or swiping it expands the full player. The full player contains artwork,
track identity, seek/progress, essential playback controls, save/download
affordances, queue access, lyrics below the primary controls, and a restrained
secondary menu.

## 5. Library And Storage Model

### 5.1 Permanent system collections

`Liked`, `Downloads`, and `Local` look and behave like first-class collections
but are rule-driven views, not ordinary mutable playlist rows:

- **Liked** contains tracks saved with the plus action.
- **Downloads** contains Shippy-managed permanent offline copies.
- **Local** contains indexed files from user-approved device folders.

They cannot be renamed or deleted. Removing a song from one collection changes
that relationship only; it does not unexpectedly erase unrelated copies.

### 5.2 User collections

User playlists support:

- Create, rename, delete, and reorder
- Add or remove songs
- Mixed downloaded and streamable provider tracks
- Local tracks
- Pinning and sorting
- Queue playback in the playlist’s visible order

### 5.3 Local realm

The user selects one or more source folders. Shippy persists Android-granted
access and indexes supported audio recursively. The user can add or remove a
folder and trigger rescan. Local search and metadata operate without internet.

Local items may be placed in normal playlists and Crew. They keep their exact
file identity. Any probable match to a provider record is an optional
relationship, never a destructive merge.

### 5.4 Download destination

The user chooses a Shippy download directory. Selecting it must:

1. Persist read/write access when Android permits it.
2. Index supported music already present there.
3. Reconcile existing Shippy download records with actual files.
4. Write future downloads into that location.
5. Surface unavailable or revoked folder access clearly.

Downloads are availability state, not a separate product universe. A downloaded
track remains visible in its playlist, album, search result, and queue.

## 6. Playback And Queue

### 6.1 Required audio behavior

- Media3/ExoPlayer-based playback
- Background playback and correct audio focus
- MediaSession, notification, lock-screen, headset, and Bluetooth controls
- Gapless playback where the media permits it
- Crossfade as a user audio option
- ReplayGain/loudness normalization where metadata and codecs permit it
- Equalizer integration
- Sleep timer with “finish current track”
- Playback speed only where it makes sense for the selected content
- MP3, M4A/MP4, WAV, FLAC, Ogg/Vorbis/Opus, AAC/ADTS, and other formats supported
  by the selected Media3/device decoder path

### 6.2 Queue behavior

The queue is the single source of playback order. Users can:

- Play now, play next, or add to queue
- Reorder and remove items
- Start from a song, album, playlist, search result, or Crew action
- View who added an item during Crew
- Preserve a useful offline queue
- See availability/buffering without provider jargon

Choosing a song normally replaces the active playback context with the selected
context and begins playing. “Add to queue” is explicit.

### 6.3 Player action hierarchy

Visible primary actions:

- Save (`+`)
- Download/remove download
- Shuffle
- Previous
- Play/pause
- Next
- Repeat
- Queue

Secondary actions:

- Play next
- Add to queue
- Add to playlist / edit saved destinations
- Go to album
- Go to artist
- Start or open Crew
- Share
- Song information
- Sleep timer

Song information contains provider/source details, original links, technical
metadata, and maintenance actions. These do not clutter the normal action sheet.
No action appears twice under different names.

### 6.4 Back behavior

Android Back follows visual hierarchy:

1. Close an open sheet/dialog.
2. Collapse full Now Playing to the mini-player.
3. Navigate to the previous app surface.
4. Leave the app only when already at the root.

Playback continues according to normal background-player behavior.

## 7. Source And Availability Model

A logical track can have several provider candidates, a Shippy download, Crew
temporary chunks, and an optional exact local file relationship.

Provider tracks resolve in this order:

```text
Crew temporary cache
    -> Shippy permanent download
    -> preferred enabled provider
    -> fallback enabled provider
    -> active Crew peer
    -> unavailable
```

Local items play their exact local file first. They do not automatically replace
a provider track with similar metadata.

Resolution must verify recording identity strongly enough to avoid substituting
a remix, live version, clean/explicit variant, or differently timed recording.
When confidence is insufficient, keep candidates separate.

## 8. Sharing

Track sharing presents two understandable choices:

- **Share original link:** provider/original URL when available.
- **Share with Shippy:** a Shippy deep link or small metadata payload that lets
  another Shippy installation resolve the same intended recording.

The Shippy payload contains stable identity metadata and candidate provenance,
not a permanent audio attachment. If the exact candidate is unavailable, the
receiver may resolve an equivalent candidate only when identity confidence is
high and the substitution is honestly represented.

## 9. Crew Product Contract

Crew synchronizes the entire listening session, not merely the playback
timestamp.

### 9.1 Equal collaboration

Every member may, by default:

- Choose a song, album, or playlist
- Replace the current playback context
- Play, pause, seek, skip, and repeat
- Add, remove, or reorder queue items
- Contribute an available or local track
- Send lightweight emoji reactions

An invisible coordinator orders concurrent actions and maintains the canonical
session clock. It is not a privileged user role and transfers automatically.

### 9.2 Two data planes

- **Control plane:** ordered session events, queue, current item, target
  timestamp, play state, membership, availability summaries, lyrics, artwork
  references, reactions, and health.
- **Media plane:** encrypted, temporary audio chunks used only when a member
  cannot resolve the track from Crew cache, downloads, or providers.

### 9.3 Joining and connectivity

- QR code and deep-link invitation
- Invitation carries a short-lived session locator and secret, not a permanent
  raw device address
- LAN discovery/connection without internet
- Remote direct peer-to-peer attempt through signaling and ICE
- Hosted relay fallback
- Explicit self-hosted relay configuration
- Small direct sessions and larger relay-backed sessions

### 9.4 Push & Pull

The setting is one clear toggle. When enabled:

- Only members of the active Crew can request or provide media.
- Any member possessing the selected track may supply it.
- Upcoming missing tracks are prefetched before playback.
- Received audio is session-temporary.
- Temporary chunks may help redistribute within the active Crew.
- Permanent retention requires an explicit Download action.

When disabled and a required track is available from a Crew peer, Shippy prompts
the user to enable Push & Pull rather than failing mysteriously.

### 9.5 Synchronization

The coordinator schedules playback against a monotonic session clock. Clients
buffer first, start at a future agreed timestamp, report drift, and apply small
corrections without audible disruption where possible. Queue and control events
are ordered and idempotent so reconnection cannot duplicate or reverse actions.

### 9.6 Reactions

Crew provides ephemeral emoji reactions. A selected emoji floats over the
current player with a restrained animation and disappears. Reactions are not
stored as chat history and respect reduced-motion preferences.

The complete protocol and failure contract live in `docs/CREW.md`.

## 10. Integrations

Required product integrations:

- Streaming providers proven viable from Bloomee behavior, beginning with
  YouTube Music/YouTube and JioSaavn adapters
- Lyrics through an ordered provider chain, with synchronized lyrics where
  available and cached offline behavior. Musixmatch may be preferred only through
  an official securely supplied credential or broker; no reusable key is embedded
  in the open-source APK. LRCLIB is the no-key fallback.
- Last.fm authentication and scrobbling, followed by relevant discovery/stats
- Equalizer and audio settings
- Playlist import/export where implementations are reliable
- Native Android widgets and media controls

Bloomee code is not dropped into the Kotlin app blindly. Each capability is
ported or reimplemented behind a Shippy-owned Kotlin contract.

## 11. Android Experience And Release Accessibility

Shippy must support:

- Edge-to-edge Android layouts
- Predictive Back
- Material You/dynamic color without sacrificing Shippy’s hierarchy
- Dark and light themes
- Home-screen widgets in useful sizes
- Notification and lock-screen controls
- Android Auto after the core MediaSession path is stable
- Quick Settings action where it provides real value
- TalkBack labels and ordered focus
- Dynamic type without clipping
- 48 dp minimum interactive targets
- Accessible seek/volume controls
- Contrast-safe states that do not rely on color alone
- Reduced motion and disabled decorative reactions when requested
- Keyboard/D-pad behavior where Android surfaces require it

## 12. Technical Foundation

Shippy is built from Auxio’s native Kotlin/Android foundation. Preserve its
proven local-library, Media3, MediaSession, playback-service, widget, and Android
integration paths unless an inspected limitation requires replacement.

Bloomee is inspected as a donor for provider behavior, resolver logic,
downloads, lyrics, Last.fm, imports, and product edge cases. Dart/Flutter
implementations are not treated as directly reusable Kotlin components.

Architecture must remain:

```text
Android Views/Fragments UI (existing Auxio runtime)
        -> presentation state
        -> Shippy domain use cases
        -> repositories and Crew engine
        -> Auxio playback/local adapters + Shippy provider/download adapters
        -> Android Media3, storage, networking, and persistence
```

Detailed boundaries live in `docs/ARCHITECTURE.md`.

## 13. Complete Scope And Delivery Strategy

The target is the complete product described here. Work proceeds through
vertical stages because each stage must leave one coherent, testable path:

1. Foundation and canonical contracts
2. Shippy shell and player hierarchy
3. Library, storage, downloads, and source resolution
4. Provider, lyrics, Last.fm, and audio integrations
5. Crew control plane
6. LAN and remote media plane with Push & Pull
7. Hosted relay and larger-session behavior
8. Android integration, performance, and resilience
9. Accessibility and release hardening

Stages are implementation order, not permission to omit later requirements.

## 14. Explicit Non-Goals

- iOS, desktop, web, or cross-platform UI
- A general-purpose social feed
- Text chat inside Crew
- AI chat, AI DJ, or AI branding
- A Shippy-owned commercial music catalogue
- Exposing provider/network debugging in primary user flows
- Decorative animation that compromises responsiveness or accessibility

## 15. Product Acceptance

Shippy is product-complete when:

- Home, Search, Library, and Crew form one coherent app shell.
- Mini-player/full-player transitions and Back behavior are correct.
- Local folders and the chosen download folder index reliably.
- Liked, Downloads, and Local are permanent correct collections.
- Provider tracks follow the locked resolution order.
- Queue, MediaSession, notification, widget, and UI never disagree.
- A user can search, play, save, queue, download, disconnect, and continue.
- Two or more phones can create/join Crew using QR/link.
- Crew operates on LAN without internet.
- Remote Crew attempts direct P2P and uses a configured relay when required.
- Every participant can modify session playback and queue.
- A non-host local song can be prefetched and played by the rest of the Crew.
- Push & Pull media remains temporary unless explicitly downloaded.
- Lyrics, artwork, queue, and reactions remain synchronized.
- Coordinator loss, peer loss, provider failure, and network changes recover
  without corrupting the session.
- TalkBack, large text, contrast, reduced motion, and touch-target checks pass.
- No placeholder feature is represented as complete.

## 16. Verification Constraint

This development machine must not be subjected to repeated heavy Gradle/Android
builds. During implementation, prefer static inspection, focused JVM/Kotlin
tests where inexpensive, formatting, lint/type checks that do not require a full
application build, and deterministic protocol tests.

The product owner will perform final APK/device build and physical-phone testing.
The handoff must include exact build instructions, known unverified surfaces,
and a device test checklist. Do not claim full verification before that occurs.

## 17. Canonical Companion Documents

- `docs/UX.md` — screen behavior and interaction hierarchy
- `docs/CREW.md` — Crew state, transport, sync, Push & Pull, and recovery
- `docs/ARCHITECTURE.md` — code boundaries and foundation/donor mapping
- `docs/IMPLEMENTATION_PLAN.md` — staged executable work and acceptance checks
- `docs/STATUS.md` — live progress, current blockers, and next concrete task
- `docs/AUXIO_FOUNDATION_AUDIT.md` — inspected Auxio evidence
- `docs/BLOOMEE_DONOR_AUDIT.md` — inspected Bloomee donor evidence
