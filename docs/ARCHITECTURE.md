# Shippy Architecture

**Status:** Initial architecture from inspected Auxio and Bloomee sources  
**Baseline:** Auxio `dev` at `b8c3cacd6430fc3ec009979eca2eda6424a4ac15`  
**Build constraint:** no heavy local Gradle/Android build during this pass

## 1. Decision

Shippy extends the actual Auxio Android project. It does not rewrite Auxio in
Compose and does not embed the Bloomee Flutter runtime.

Keep:

- Auxio `musikr` indexing, metadata, SAF/MediaStore, local library, and covers
- Auxio ExoPlayer/Media3/FFmpeg/ReplayGain audio path
- Auxio single playback state manager and persisted queue semantics
- Auxio service, MediaSession, notification, Android Auto, and widget lifecycle
- Auxio Room/Hilt/coroutines foundations

Extend:

- Queue/playback identity from local `Song` to a resolved Shippy playback item
- Playlist references to support canonical provider and exact local items
- Library presentation with permanent system collections
- Settings with providers, downloads, Crew, integrations, and accessibility

Add:

- Typed canonical track/candidate/availability domain
- Provider registry, search, resolution, and health
- Permanent download state machine and selected destination
- Lyrics and Last.fm repositories
- Crew state machine, transports, temporary cache, and relay

Replace incrementally:

- Auxio’s local-only information architecture and visual shell
- Direct UI dependence on `musikr.Music`
- Crowded or non-Shippy action hierarchy

## 2. Existing Modules

### `:app`

Native Android application using Kotlin, Views/Fragments, ViewBinding,
RecyclerView, Navigation/Safe Args, ViewModels, Hilt, Room, and coroutines.

Important existing authorities:

- `MusicRepository` — local library/index state
- `PlaybackStateManager` — player command and queue authority
- `ExoPlaybackStateHolder` — ExoPlayer/Media3 adapter
- `AuxioService` and `PlaybackServiceFragment` — background lifecycle
- `MediaSessionHolder` — system session/notification behavior
- `WidgetComponent` / `WidgetProvider` — widget state/commands

### `:musikr`

Local filesystem and library engine:

- SAF and MediaStore file systems
- Metadata extraction and TagLib integration
- Room metadata/playlist caches
- Song/album/artist/genre/playlist graph
- Source locations and persisted permissions

`musikr.Song` remains the exact local-file model. It is adapted into Shippy; it
does not become Shippy’s cross-source track model.

### Vendored media modules

Pinned Auxio Media3/ExoPlayer and FFmpeg patches. Keep untouched until a measured
Shippy blocker requires a change.

## 3. New Logical Boundaries

Avoid premature Gradle-module proliferation. Start as clear packages inside
`:app`; extract a module only when compile/test isolation has demonstrated value.

```text
shippy/domain
  model
  library
  playback
  resolver

shippy/provider
  api
  registry
  jiosaavn
  youtube

shippy/download
  model
  persistence
  worker
  storage

shippy/lyrics
  ordered source chain
  musixmatch broker boundary
  lrclib
  parser
  Room-backed offline cache
shippy/lastfm
shippy/share

shippy/crew
  protocol
  state
  sync
  transport
  media
  cache
  relay

shippy/ui
  shell
  home
  search
  library
  player
  crew
  settings
  components
```

Package paths may initially remain below Auxio’s current namespace to minimize a
mechanical rename. Public branding and application identity are changed
separately from internal namespace migration.

## 4. Canonical Domain

### Track

Represents the intended recording as presented to the user.

```text
TrackId
title
artists
album
duration
version markers
artwork
candidates[]
```

### TrackCandidate

Represents an exact source:

```text
CandidateId
trackId
kind: LOCAL | DOWNLOAD | PROVIDER | CREW_TEMPORARY
sourceId
sourceItemId
stableUri/permanentLink
format metadata
availability
identity confidence
```

Expiring provider stream URLs do not live here. They are returned by resolution.

### ResolvedPlayback

Short-lived playable result:

```text
queueItemId
candidateId
uri/dataSource
mime/container
bitrate/contentLength
expiry
headers/range behavior
source diagnostics
```

### LibraryRelationship

Tracks:

- liked
- saved
- downloaded
- present in user playlist(s)
- recently played

Local membership is derived from indexed exact local candidates.

### QueueItem

Has its own unique identity even when the same track is queued twice. Contains
track intent, context, contributor for Crew, and a resolved playback reference
that may change after failure.

## 5. Local Adapter

`MusicRepository` and `musikr` remain the local authority.

```text
musikr.Song
    -> LocalTrackCandidateMapper
    -> Track + exact LOCAL candidate
```

Rules:

- Preserve `Music.UID` for exact local lookup.
- Preserve current indexing/cache behavior.
- Do not write provider records into `musikr`.
- Do not silently deduplicate a local file against provider content.
- Adapt local songs for global Search’s labelled local section and mixed user
  playlists.

Auxio already supports SAF and MediaStore selection, source/exclusion queries,
persistable tree access, and reindex. Extend the Settings presentation rather
than rebuilding the scanner.

## 6. Playback Boundary

Keep `PlaybackStateManager` as the sole app player/queue command authority while
generalizing its input.

Target flow:

```text
UI / MediaSession / Crew command
    -> PlaybackStateManager
    -> PlaybackResolver
    -> ResolvedPlayback
    -> ExoPlaybackStateHolder
    -> ExoPlayer
```

Transition strategy:

1. Characterize existing local queue behavior.
2. Introduce `QueueItem` alongside current `Song` representation.
3. Provide a local compatibility resolver.
4. Version persisted queue identity with migration/fallback to `Music.UID`.
5. Move UI and service metadata to canonical items.
6. Add download/provider/Crew candidates without creating a second player.

MediaSession, widgets, and Android Auto continue to invoke the same manager.

## 7. Persistence

Keep existing Auxio databases for local metadata, local playlists during
migration, and playback persistence. Add Shippy-owned Room tables/databases for:

- Canonical track/provider identity cache
- Provider candidates and stable metadata
- Library relationships
- Download jobs and verified artifacts
- Lyrics cache
- Last.fm durable scrobble outbox
- Crew active checkpoint and temporary-cache manifest

Prefer one Shippy database when schemas share transactions; do not create one
database per feature.

Persistence rules:

- Every schema has explicit version/migration.
- Queue persistence keeps duplicate queue items.
- Provider stream URLs have separate expiry-aware cache.
- Secrets use Android secure storage/Keystore-backed encryption, not generic
  preferences or logs.
- Crew media bytes are outside Room in bounded temporary files; Room stores only
  session-scoped manifests.

The active Crew checkpoint is one replaceable Room row containing a versioned
snapshot payload, indexed session/term/sequence identity, update time, and a
SHA-256 integrity value. Loading cross-checks the indexed identity against the
decoded snapshot and removes a corrupt row only if no newer checkpoint replaced
it. Session secrets and rejoin credentials are deliberately not stored in this
row; their persistence uses one Keystore-backed encrypted lease file outside Room and
generic preferences. The lease contains only the active session/member and short-lived
invite/rejoin material, is cleared on expiry, authenticated revocation, and accepted local
leave/end, and is paired with the checkpoint before any restore attempt. Restores reconnect
through the normal authenticated transport/session-engine boundary; they cannot bypass
checkpoint validation, ordered events, or strict-majority election rules.

## 8. Provider Platform

```kotlin
interface MusicProvider {
    val id: ProviderId
    val capabilities: Set<ProviderCapability>
    fun search(...)
    fun album(...)
    fun artist(...)
    fun playlist(...)
    fun related(...)
    fun resolve(candidate, constraints): ResolvedStream
    fun health(): ProviderHealth
}
```

Actual signatures use coroutines/Flow and typed results/errors.

Provider rules:

- One implementation per provider; no duplicate YouTube stacks.
- Timeouts, cancellation, bounded retry, and provider-scoped partial failure.
- Typed DTOs at network boundary.
- Recorded non-secret fixtures for fragile response traversal.
- No reusable credentials embedded in source.
- Stable metadata is persisted separately from expiring streams.
- Kill/disable provider cleanly without breaking Local/Downloads.

Bloomee donor order:

1. Pure fixture-backed helpers: URL classification, JioSaavn decode/quality,
   LRC parsing, Last.fm signing, M3U.
2. JioSaavn typed adapter.
3. Consolidated YouTube Music metadata adapter.
4. YouTube stream resolver.
5. Spotify metadata import/matching if retained.

The current anonymous YouTube Music adapter bootstraps the public Innertube key,
WEB_REMIX version, and visitor data from `music.youtube.com` at runtime. Search
uses WEB_REMIX; playback retries with Android Music and iOS client contexts and
accepts only direct HTTPS audio formats. Cipher-only formats are rejected
honestly until a separately reviewed decipher path exists. Bootstrap data and
resolved URLs are short-lived cache material, never durable track metadata.

## 9. Downloads

Download state machine:

```text
REQUESTED
-> RESOLVING
-> QUEUED
-> TRANSFERRING
-> VERIFYING
-> FINALIZING
-> AVAILABLE

Any active state -> PAUSED | CANCELLED | FAILED_RETRYABLE | FAILED_FINAL
```

The selected SAF destination and existing-file index must reconcile against
Room state. Final availability is published only after the output exists,
matches expected constraints, and is indexable/playable.

Do not copy Bloomee’s in-memory task list, SSL-ignore behavior, path
concatenation, rename bugs, or stale filename records.

## 10. Lyrics

Lyrics are an ordered source chain behind one repository:

1. Fresh exact offline cache entry.
2. Musixmatch through a configured Shippy-controlled HTTPS broker.
3. LRCLIB exact lookup, then conservative search fallback.
4. Stale cached lyrics when the network sources are unavailable.

Musixmatch's official API supports title/artist matching and synchronized LRC
subtitles using duration constraints. It also requires an API key on every
request. The key therefore belongs in a hosted broker or another owner-controlled
secret boundary, never in the open-source APK. The Android client sends only the
recording identity needed for matching and accepts a normalized lyrics document;
the broker owns provider authentication and rate-limit handling.

The credential-free Android broker client is disabled until an endpoint is
configured. It sends normalized recording identity only, validates the returned
recording before use, and applies an endpoint-scoped bounded cooldown after
rate limiting so LRCLIB can continue without repeatedly hitting the broker.

The cache stores provider/source identity, recording metadata used to validate
the hit, plain/synchronized payloads, instrumental state, and fetch time. A
TrackId hit is rejected if the cached recording metadata no longer matches the
request. Lyrics do not require a separate encryption dependency.

### Last.fm

Scrobbling is an optional playback observer, not a second player state. It
counts only ordinary advancing playback, queues an eligible listen durably in a
Room FIFO outbox, sends oldest entries first in batches of at most 50, and
inspects Last.fm's response body even when HTTP succeeds. Network/service errors
retain the outbox; invalid entries may be dropped; invalid sessions require
reauthentication.

The user supplies the API key and secret at runtime. The pending application
credentials and request token remain only in the Settings ViewModel while the
browser authorization is in progress; process death intentionally restarts that
flow. Only a successfully exchanged API key, API secret, session key, and
username enter the credential repository, which stores them in an
Android-Keystore AES-GCM `AtomicFile` envelope. No reusable Last.fm credential is
embedded in the APK. Settings supports connect, explicit completion after
returning from the browser, reconnect, and disconnect. Delivery-level invalid
session state still needs to be surfaced back to Settings as a reauthorization
prompt.

## 11. Crew Architecture

Crew is layered so deterministic behavior can be tested without Android network
or media runtime:

```text
CrewReducer / EventLog / Snapshot
        |
Coordinator / Membership / Availability / SyncClock
        |
ControlTransport interface
MediaTransport interface
        |
LAN direct / remote P2P / hosted relay adapters
        |
Temporary media cache -> PlaybackResolver
```

### Pure core

- Immutable/versioned state transitions
- Coordinator-authenticated event publication while retaining the requesting
  member separately
- Event ordering/idempotency and bounded replay protection
- Versioned authenticated snapshots and majority next-term election certificates
- Queue operations
- Availability and supplier decisions
- Prefetch priorities
- Monotonic clock probes, scheduled starts, and configurable clock/drift decisions
- Active-session reaction expiry, sender checks, rate limiting, and bounds

Ungraceful election certificates contain independently authenticated votes tied
to one exact session, term, sequence, coordinator, and membership checkpoint.
The reducer requires a strict majority and one deterministic candidate. A
caller-supplied reachable-member subset is not authority and cannot install a
snapshot. Without an external relay/witness, a two-member partition favors
safety and waits rather than allowing two coordinators.

This layer has deterministic JVM tests.

`CrewCoordinatorSequencer` is the single request-to-event serialization point
for the active coordinator. It accepts an action only from the authenticated
member named by the request, keeps that issuer distinct from the coordinator
publisher, assigns the next term/sequence, applies the reducer before
publication, and reuses the request ID as the durable event ID for optimistic
reconciliation. Rejected actions do not consume sequence numbers, and bounded
recent results make retries idempotent even across a graceful handoff.

`CrewOptimisticActionTracker` is the matching bounded client ledger. Pending UI
intent is confirmed only by the exact durable ID, issuer, and action; ID reuse
with different content becomes a visible protocol conflict. Coordinator
rejection, timeout, and session replacement return explicit rollback reasons
instead of leaving speculative UI state behind.

`CrewControlCodec` is the stable binary boundary between that pure state model
and all transports. It encodes requests, accepted events, snapshot requests,
authenticated snapshot installs/election votes, and explicit request
rejections. Every string, collection, queue, candidate list, and logical message
is bounded. `CrewControlFramer` splits a logical message into SHA-256-bound
reliable control frames below the 64 KiB data-channel limit; the reassembler
bounds concurrent assemblies, memory, chunk count, and lifetime before decoding.
This permits real playlist replacement and snapshots without pretending every
valid session fits in one WebRTC frame.

`CrewSessionEngine` is the active authenticated control-plane owner. Each peer
has one bounded outbound actor, so concurrently sequenced events remain ordered
even when a logical message spans frames. The engine compares every embedded
issuer/publisher/requester with the authenticated transport member, routes
member requests only to the current coordinator, applies reducer results,
reconciles exact optimistic intent, requests snapshots on gaps, and persists
each accepted state. Durable-control queue overflow or send timeout detaches the
peer explicitly; it never silently drops a state event. Raw election votes from
one payload are not treated as independently authenticated quorum evidence.

Membership uses that same event authority. An authenticated pending peer is
admitted only by the current coordinator, and receives a canonical snapshot
after its `MemberJoined` event so provisional join state cannot become a second
truth. Ordinary members cannot admit or remove another member. Graceful
coordinator departure deterministically transfers to the lowest-ID connected
successor, then sends a self-leave request through that new coordinator. The
departing device clears only its exact active checkpoint after the accepted
`MemberLeft` event reaches it.

`CrewLivenessTracker` keeps transport loss separate from membership mutation.
It uses monotonic configurable reconnect grace, cancels expiry on authenticated
activity, lets only the coordinator remove an expired ordinary member, and
permits ungraceful election only when connected survivors are a strict majority
of the full persisted checkpoint. The candidate is the lowest stable member ID;
two-member coordinator loss therefore remains paused without a relay witness.

`CrewElectionVoteCollector` is the bounded bridge from that pure decision into
the session engine. A vote is accepted only when its voter matches the
authenticated peer and its checkpoint/candidate match the current liveness
decision. The active engine periodically turns ordinary expiry into sequenced
membership removal, broadcasts each survivor's standalone vote, and lets only
the majority-elected candidate publish the next-term snapshot. Snapshot votes
are usable only when the receiver previously authenticated the same standalone
votes; an early snapshot is held once until its certificate converges.

### Transport adapters

The 2026-07-25 focused spike selected:

- Android `NsdManager`/DNS-SD for foreground LAN advertisement and discovery.
- WebRTC SCTP data channels over DTLS for encrypted direct peer traffic.
- The maintained, shadowed, stripped
  `io.github.webrtc-sdk:android-prefixed-stripped:144.7559.09` Android artifact.
- WebRTC ICE with STUN/TURN for remote direct attempts and transport relay.
- A separate authenticated signaling boundary for LAN rendezvous and hosted
  relay WebSocket exchange.

The stripped artifact keeps the current WebRTC data-channel/ICE APIs while
removing unneeded software video codecs. Its published minimum SDK is 21
(Shippy is 24), and the inspected AAR is about 13.8 MB before APK ABI splitting
and shrinking. Shippy creates no WebRTC audio/video tracks.

One peer connection exposes separate channels:

- Reliable ordered control
- Unordered, no-retry clock probes
- Unordered, no-retry reactions
- Reliable ordered bounded media chunks

Each channel has an application payload limit and buffered-byte ceiling.
Backpressure is returned to the caller; media traffic cannot silently consume
unbounded memory or share the control channel.

The peer is a negotiating connection until a separate invitation authenticator
supplies a verified session/member transcript binding. Normal Crew frames stay
gated before that point. ICE candidates carry a negotiation generation and wait
for the matching remote description, preventing ordinary trickle candidates
from being lost during offer/answer ordering.

The implemented join authenticator is a four-message challenge/proof/confirmation
exchange. The final responder confirmation prevents the initiator from exposing
ordinary Crew frames before both sides have completed mutual authentication.
It uses HMAC-SHA-256 with the short-lived QR invitation secret and binds the
protocol, invitation/session identity, both member IDs, both random nonces,
invitation lifetime, and the exact initiator/responder WebRTC DTLS certificate
fingerprints. The secret never travels over signaling or the data channel.
Successful peers independently create the same transcript hash, while the
transport still verifies that the authenticated binding matches its expected
session and claimed signaling member.

The Android LAN adapter advertises and discovers `_shippy-crew._tcp.` through
`NsdManager`. DNS-SD TXT records contain only protocol, opaque session locator,
and invite ID; the QR/link secret is never advertised. Discovery is scoped to the
exact decoded invitation, serializes legacy resolution requests, bounds results,
redacts addresses from diagnostics, and acquires the legacy Wi-Fi multicast lock
only on platform/extension versions that require it.

The resolved LAN rendezvous now feeds a bounded TCP signaling channel. A
four-step HMAC-SHA-256 challenge proves both sides possess the scanned
invitation secret without sending it. The transcript binds the protocol,
invitation lifetime/locator, canonical session, both claimed member IDs, and
fresh 256-bit nonces. After mutual proof, SDP and generation-tagged trickle ICE
messages use directional HKDF-derived AES-256-GCM keys, ordered nonces, exact
sequence checks, payload bounds, timeouts, and a fixed peer limit. This channel
only authenticates invitation possession; the WebRTC join authenticator still
binds those claims to both DTLS fingerprints before ordinary Crew traffic opens.

`CrewDirectPeerConnection` now owns the per-peer transition across those two
boundaries. One deterministic offerer creates the data channels, SDP is sent
before generation-matched trickle ICE, the responder returns the answer, and
renegotiation uses monotonically increasing generations. Once all WebRTC
channels are open, the driver runs the four-message DTLS-fingerprint-bound join
proof and exposes `CrewPeerTransport` only after both sides authenticate. A
small factory/negotiation interface keeps this orchestration deterministically
simulatable without creating native WebRTC objects in unit tests.

Shippy currently targets SDK 36. Therefore Android 17's
`ACCESS_LOCAL_NETWORK` runtime permission is not declared yet; official Android
guidance says it becomes required when targeting SDK 37. The existing adapter
must add that release-time permission flow when the target SDK is raised.

The selected dependency and Android wrapper are code/static-inspected, not
device-proven. Hosted signaling, network migration, TURN, and multi-phone
behavior remain explicit implementation and device-verification work. The LAN
socket path has focused loopback tests authored but not run on this machine. QR
is invitation/authentication, never the transport itself.

The media channel now carries an exact active-session/request/candidate/target/
supplier identity. Request, cancel, manifest acknowledgement, retry, rejection,
completion, manifest, and chunk frames are bounded below the WebRTC media
channel limit. Authorized suppliers expose bytes only—never paths, provider
URLs, or credentials. The receiver reserves declared object bytes before
accepting a manifest, verifies every chunk and the complete SHA-256 object, and
publishes only into the session-temporary cache. The controller is deliberately
event-driven: a writable callback or acknowledgement resumes one bounded send;
it never busy-loops. Authenticated-peer lifecycle, rolling prefetch, supplier
selection, and cache-to-player integration remain the next seam.

Primary evidence:

- Android NSD: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android local-network permission:
  https://developer.android.com/privacy-and-security/local-network-permission
- WebRTC Android API: https://webrtc.googlesource.com/src/+/main/sdk/android/README
- WebRTC Android artifact: https://github.com/webrtc-sdk/android

### Playback bridge

Crew never owns a second ExoPlayer. It emits canonical playback/queue commands
to `PlaybackStateManager` and contributes temporary candidates to
`PlaybackResolver`.

## 12. UI Runtime

Auxio is an Android Views/Fragments application. Preserve its real UI runtime
and modify it in place. Build Shippy with:

- Existing navigation and lifecycle patterns
- ViewBinding, RecyclerView, fragments, ViewModels, and Hilt
- New Shippy shell and reusable views
- Incremental screen replacement

Compose is not prohibited, but introducing it requires a measured reason. Do not
convert the entire application merely for design fashion.

## 13. Dependency Rules

- UI depends on Shippy domain/presentation contracts, not provider DTOs or
  `musikr` types.
- Providers do not call player or UI.
- Downloads consume provider resolution but do not mutate UI directly.
- Crew core does not depend on Android transport implementation.
- Crew media contributes candidates through the resolver.
- Player does not know provider-specific parsing.
- MediaSession/widgets use the same player command surface as UI.
- No package reads another feature’s Room tables directly; use repositories.

## 14. Verification Strategy

Cheap/local:

- Pure Kotlin model/resolver/protocol tests
- Room migration tests where feasible
- Fixture parser/provider tests
- Serialization compatibility tests
- Formatting/static inspection
- Graphify updates for architecture navigation

Deferred owner/device:

- Full Gradle/NDK/Media3 build under WSL/Linux/CI
- APK installation
- Local scan and folder permission tests
- MediaSession/background/widget/Auto tests
- Provider live health
- Multi-phone LAN/remote/relay Crew
- Audio synchronization and Push & Pull

## 15. Known Constraints

- Upstream `dev` requires Unix tooling/custom Media3/TagLib build; native Windows
  builds are explicitly unsupported.
- Auxio app-module automated coverage is sparse.
- Provider behavior is based on fragile external response formats.
- Queue/playback currently depends deeply on local `Song`; migration must be
  incremental.
- Crew’s exact transport dependency is not yet proven and must not be guessed
  into the foundation.

## 16. First Code Slice

The first implementation slice is deliberately small but architectural:

1. Add pure Kotlin canonical IDs, `Track`, `TrackCandidate`, `Availability`,
   `QueueItem`, and `ResolvedPlayback`.
2. Add deterministic resolution policy tests using local/download/provider/Crew
   candidates.
3. Add `musikr.Song` local candidate adapter.
4. Add a compatibility resolver that produces the current local media input.
5. Keep existing playback behavior unchanged.

Only after this passes should the shell/player presentation be replaced.
