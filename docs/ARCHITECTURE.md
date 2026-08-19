# Shippy Architecture

**Status:** Initial architecture from inspected Auxio and Bloomee sources  
**Baseline:** Auxio `dev` at `b8c3cacd6430fc3ec009979eca2eda6424a4ac15`  
**Build constraint:** no heavy local Gradle/Android build during this pass

> Current beta-readiness authority: `PRODUCT_SPEC.md`, `CREW.md`,
> `BETA_READINESS_IMPLEMENTATION.md`, then `STATUS.md`. This document preserves
> the original architecture baseline; the following addendum supersedes any
> older local-only playback or Crew assumptions.

## R16 authority addendum (2026-08-19)

`Shippy_R16_Master_Architecture_and_Implementation_Spec.md` supersedes this
legacy baseline for R16 direction. Its locked invariants, ownership boundaries,
data-loss protections, and target end state are binding; suggested concrete
types and sequencing remain adaptable to repository evidence.

R15.3 remains the only active runtime authority. R16 currently lives behind
separate `:shippy-core`, `:shippy-data`, and `:shippy-sources` boundaries plus an
inactive app-layer playback coordinator. The R16 coordinator serializes commands,
source completions, engine observations, restore, and listening ticks; Shippy
owns QueueEntryId traversal and bounded source preparation, while the future
Media3 adapter remains a projection. Its transaction is fully tagged before
`setMediaItems`, uses QueueEntryId as Media3 `mediaId`, disables Media3 shuffle,
and translates player callbacks back into generation-scoped observations. R16
checkpoint state is source-neutral,
and expiring locators, headers, cache leases, and temporary Crew paths are never
checkpoint identity.

No production cutover is valid until the legacy importer/backup audit, Media3
bridge, system surfaces, device matrix, and rollback gates pass together.

## 0. Beta-Readiness Addendum (2026-08-02)

- `PlaybackStateManager` remains the ordinary local player authority, but typed
  `PlaybackMutation` origins prevent system/player callbacks from becoming Crew
  user intent.
- While Crew is active, `CrewState` is the canonical shared queue/playback
  authority. The local Media3 queue is a playable projection and may omit no
  canonical occurrence from UI even when resolution is unavailable.
- Crew protocol v3 uses ordered CONTROL, dedicated CLOCK probes, readiness,
  availability, MEDIA transfer, and ephemeral reactions. Coordinator terms and
  event sequences are the durable ordering boundary.
- Provider URLs are short-lived playback resolutions. Stable `MediaObjectKey`
  identifies reusable bytes across queue occurrences. Provider cache,
  permanent downloads, local files, and Crew temporary media are separate
  storage/ownership domains.
- Push & Pull is bounded, resumable, disk-backed, active-Crew-only temporary
  media. Verified progressive ranges are exposed to Media3 through reader
  leases; explicit Download is the only promotion to permanent ownership.
- `ActiveCrewRuntime` is the process-wide owner of route, admission, rejoin,
  diagnostics, media routing, and session lifecycle. UI fragments never own
  session survival.
- Alpha package identity remains `org.oxycblt.auxio` (`.debug` for owner-test
  APKs) so upgrades preserve installed data. A branded package migration is a
  future explicit data-migration release, not a mechanical rename.

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

Shippy uses one filesystem factory for both indexing and change observation.
SAF mode retains Auxio's SAF-only behavior. MediaStore mode keeps MediaStore as
the primary source and appends configured SAF folders—including the selected
download destination—through an ordered composite. Exact Musikr `Path`
duplicates are emitted only once with MediaStore winning; no metadata-based file
identity guess is introduced.

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
Android Auto retains Auxio's `MediaBrowserServiceCompat` browse/search tree for
the distinct Local realm. Selecting those rows enters the canonical Shippy
player, while the live car queue and transport commands consume the same
`ResolvedQueueItem`/`PlaybackStateManager` state as every other system surface.

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

The Room v8 playback checkpoint stores source-neutral queue intent separately
from resolved media: ordered queue-item IDs, canonical track references, context
and contributor, shuffle mapping, selected heap item, position, and repeat mode.
Before restore, temporary Crew and synthesized Download candidates are removed
from durable metadata; current download/provider/Crew availability is resolved
again. Missing items are skipped with deterministic queue/index compaction, and
Auxio's legacy local-only checkpoint remains the compatibility fallback. Neither
resolved URLs nor request headers enter the checkpoint.

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
    val descriptor: ProviderDescriptor
    fun search(...)
    fun browse(entity, continuation): ProviderBrowsePage
    fun resolve(candidate, constraints): ResolvedStream
    fun probeHealth(): ProviderHealth
}
```

Actual signatures are suspending and return typed `ProviderResult` values.
`ProviderEntity` is metadata-only and carries an opaque provider token, type,
display metadata, and optional original HTTPS URL. It never carries playable
media. Search may return tracks and album/artist/playlist entities; browse turns
one entity into a playable track page without creating a second catalogue or
player authority.

Provider rules:

- One implementation per provider; no duplicate YouTube stacks.
- Timeouts, cancellation, bounded retry, and provider-scoped partial failure.
- Typed DTOs at network boundary.
- Recorded non-secret fixtures for fragile response traversal.
- No reusable credentials embedded in source.
- Stable metadata is persisted separately from expiring streams.
- Kill/disable provider cleanly without breaking Local/Downloads.
- Settings health checks call one bounded metadata-only `probeHealth` contract;
  they never resolve or download playable media merely to render provider status.

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

At playback preparation, the latest available job is revalidated against the
exact track and originally requested candidate, then projected as one
deterministic `DOWNLOAD` candidate carrying only its verified SAF content URI,
MIME type, and length. This projection occurs before the global resolver so the
same download-first rule applies to Search, Library, playlists, and Crew. It
does not mutate stored provider metadata. Invalid or unavailable persistence
falls back to ordinary candidate resolution rather than blocking playback.

Do not copy Bloomee’s in-memory task list, SSL-ignore behavior, path
concatenation, rename bugs, or stale filename records.

An explicit Download of active Crew temporary media still enters this same state
machine. Before Room/WorkManager ownership, Shippy atomically snapshots the
exact verified `file://` object into a bounded private no-backup staging
directory. This prevents session cleanup from racing a deferred worker. The
stage survives pause/retry and is deleted after verified publication,
cancellation, or final failure; it never becomes a second library record.

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
returning from the browser, reconnect, and disconnect. A process-local health
signal carries delivery-level invalid-session results back to Settings without
duplicating or persisting credentials. Reauthorization reuses the securely
stored application key/secret, while a successful delivery or authorization
clears the signal. A process restart remains honest: the startup outbox flush
can raise the signal again if the saved session is still invalid.

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

Path-free member availability uses a bounded transient message beside durable
control. The engine exposes one per-queue-item summary flow, routes joiner
announcements only through the authenticated coordinator, rejects forged
publisher identities without detaching a healthy peer, and prunes summaries
whenever their exact queue/member/term/sequence checkpoint is no longer current.
It never serializes those summaries into Room snapshots.

Membership uses that same event authority. An authenticated pending peer is
admitted only by the current coordinator, and receives a canonical snapshot
after its `MemberJoined` event so provisional join state cannot become a second
truth. Ordinary members cannot admit or remove another member. Graceful
coordinator departure deterministically transfers to the lowest-ID connected
successor, then sends a self-leave request through that new coordinator. The
departing device clears only its exact active checkpoint after the accepted
`MemberLeft` event reaches it.

A joining device does not invent provisional Crew state. Before constructing
its `CrewSessionEngine`, a bounded `CrewJoinBootstrapAccumulator` consumes only
frames from the already-authenticated coordinator transport, waits through the
preceding admission event, and accepts a normal snapshot only when its
session/protocol match and the exact local member is present. Election-certified
or forged-coordinator snapshots are rejected at this bootstrap boundary.

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

`relay/` now contains the first self-hostable boundary: a Node WebSocket service
with a versioned bounded binary envelope, one opaque host registration per
session locator/invitation pair, isolated random join routes, a one-time-rotated
32-byte host resume credential verifier with bounded hostless presence, heartbeat/rate/
capacity limits, and no persistence or payload logging. Android peers retain
end-to-end signaling confidentiality and final fingerprint-bound Crew join
authentication; the relay only routes ciphertext. It is a signaling foundation,
not a TURN server, control fan-out, media fan-out, or a membership authority.
When the operator configures a separate coturn service and shared REST secret,
the same HTTP process may mint bounded short-lived coturn credentials for the
exact opaque host registration currently held in memory. It never receives the
invitation bearer secret and does not proxy media.

The Android adapter mirrors that envelope through one bounded OkHttp WebSocket.
Every random route derives directional AES-256-GCM keys and nonce prefixes from
the invitation secret, exact invitation identity/lifetime, relay endpoint, and
route ID. An encrypted hello supplies only a provisional member/display-name
claim; the existing DTLS-fingerprint-bound WebRTC join proof remains the final
membership authority. It drops routes on host loss, keeps no raw resume token,
and loses unreclaimed presence on relay restart. Host registration readiness is explicit so production
launchers cannot advertise a remote invite before the relay accepts it.

The production host owns both LAN and hosted signaling collectors while
retaining one admission coordinator, WebRTC runtime, and session/media engine.
Failed relay registration is closed and replaced by a newly generated LAN-only
bootstrap. A relay-bearing join races LAN and hosted signaling, closes the
loser, and hands only one peer to the existing join coordinator. The direct
WebRTC attempt always retains public STUN and may append strictly validated,
short-lived TURN credentials derived from the configured relay origin.
Joined-session reconnect refreshes those credentials on each dial. Transport
migration and full ordered relay fan-out remain explicit later work; the relay
does not proxy Crew control or temporary media.

Relay Settings stores only a validated HTTPS endpoint. Its health probe derives
the configured origin's public `/healthz`, performs one cancellable bounded GET
through the relay HTTP client, and accepts only the relay's small typed 2xx JSON
response. UI generation checks prevent a stale response from overwriting a
newly replaced or cleared endpoint.

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
fresh 256-bit nonces. Handshake v2 also carries bounded UTF-8 display-name
claims for both peers and binds both names into that transcript. Names remain
presentation claims: the runtime may associate a remote name only after WebRTC
proves the paired member ID, and diagnostics never print the name. After mutual
proof, SDP and generation-tagged trickle ICE
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

The production LAN-host composition now has one explicit ownership chain:
secure host bootstrap -> persisted `CrewSessionEngine` -> per-session WebRTC
runtime -> invitation-authenticated signaling host -> fingerprint-bound
responder admission -> NSD advertisement. One collector forwards accepted
signaling peers into the admission coordinator; neither the launcher nor UI
collects transport frames. Startup becomes active only after NSD registration
and the exact Keystore rejoin lease succeed. Runtime `close()` releases live
resources while preserving recovery state; explicit host-session `end()` also
clears only that session's checkpoint and lease.

The production LAN-join composition follows the inverse single-owner chain:
decode one short-lived invite -> exact NSD rendezvous -> authenticated signaling
client -> one per-attempt WebRTC runtime -> fingerprint-bound initiator ->
bounded coordinator snapshot bootstrap -> one persisted `CrewSessionEngine`.
Discovery ends as soon as signaling owns the route, and the coordinator remains
the only transport-frame collector. Success is published only after the exact
Keystore rejoin lease is saved. Runtime `close()` preserves recovery state;
explicit `leave()` releases the live join and clears only its exact checkpoint
and lease after requesting the ordered graceful membership departure. A sole
remaining member publishes the same ordered terminal event used by an explicit
host end.
Playback remains a separate seam; authenticated media is composed explicitly
beside the engine rather than hidden in transport discovery.

After activation, `CrewJoinReconnectController` watches only the existing
engine's canonical coordinator and attached-peer map. A missing coordinator peer
starts one bounded retry loop; Android network changes only wake that loop.
Each attempt reuses the LAN/hosted signaling selection and WebRTC runtime,
requires the current coordinator's authenticated member identity, then attaches
the replacement transport to the same `CrewSessionEngine` and requests a
snapshot. The controller owns replacement connections and closes stale,
wrong-member, expired, or superseded results. It never creates a new checkpoint,
queue, player, or session engine.

`ActiveCrewRuntime` is the application-wide LAN session owner above those two
launchers. A generation-guarded state machine permits only one host, join, or
end operation, retains the live session outside fragment/ViewModel lifetimes,
and projects only role, stable IDs, canonical `CrewState`, and the host invite
link into presentation state. UI never receives an engine, signaling peer,
transport, invite secret, or runtime session object. Explicit end still uses
the launcher's exact checkpoint/lease cleanup contract after `SessionEnded`
converges. A remote terminal state moves the owner through `Ending` and releases
the same live resources. The session engine clears its checkpoint and emits the
terminal notice without immediately cancelling outbound actors; the owning
runtime performs teardown only after the ordered event has had a delivery
window.
The owner also exposes one suspend local-action gateway. It creates no competing
state: the exact owned session constructs an issuer-bound request and delegates
to `CrewSessionEngine`, while generation checks reject results from a session
that ended or changed during suspension.

Transient reactions use the dedicated lossy reaction channel and never enter
the durable reducer, checkpoint, or replay log. The session engine validates a
bounded versioned payload against the active session and membership, applies
receiver-local expiry/rate limits, and emits accepted reactions through one
ephemeral flow. In the current coordinator-centred topology, a joiner sends to
the coordinator and the coordinator fans the authenticated original member's
reaction to the other peers. `ActiveCrewRuntime` generation-checks the send
surface and forwards only reactions from its exact owned session.

The Crew fragment observes this owner through a thin Hilt ViewModel and never
constructs or retains network/session resources. Its current production surface
supports LAN host, pasted-link join, live canonical membership, host invite
sharing, QR rendering/scanning, and exact explicit end/leave. Invite values are
handed directly to the QR renderer, scanner result, or Android share chooser and
are not displayed or logged. The camera scanner is optional-device compatible,
accepts QR payloads only, and retains pasted-link joining as the no-camera
fallback.

Shippy currently targets SDK 36. Therefore Android 17's
`ACCESS_LOCAL_NETWORK` runtime permission is not declared yet; official Android
guidance says it becomes required when targeting SDK 37. The existing adapter
must add that release-time permission flow when the target SDK is raised.

The selected dependency and Android wrapper are code/static-inspected, not
device-proven. Hosted signaling, network migration, coturn allocation, and
multi-phone behavior remain explicit device-verification work. The LAN
socket path has focused loopback tests authored but not run on this machine. QR
is invitation/authentication, never the transport itself.

The version-3 media channel now carries an exact active-session/request/
duplicate-safe-queue-item/candidate/target/supplier identity. Request, cancel,
manifest acknowledgement, retry, rejection, completion, manifest, and chunk
frames are bounded below the WebRTC media-channel limit. Authorized suppliers
expose bytes only—never paths, provider URLs, or credentials. The receiver
reserves declared object bytes before accepting a manifest, verifies every
chunk and the complete SHA-256 object, and publishes only into the
session-temporary cache. Completion is acknowledged only after the verified
file is published to the local playback overlay. The controller is
deliberately event-driven: a writable callback or acknowledgement resumes one
bounded send; it never busy-loops.

`CrewActiveMediaRuntime` now composes one active session's policy, private
temporary cache/index, receiver, authenticated router, live Push & Pull setting,
and immutable verified-download view. Supplier authorization rechecks the exact
session, authenticated requester/target, local supplier, active membership,
duplicate-safe queue occurrence, and original candidate against current
canonical state. Only exact private temporary bytes, exact available Local
`content://` bytes, or a verified Shippy download tied to the requested
candidate can be opened; provider URLs, paths, credentials, and arbitrary
discovered devices cannot cross the seam. Both production LAN launchers create
one runtime before their session engine, pass it as that engine's authenticated
media lifecycle, and tear the engine down before media/cache cleanup. Rolling
prefetch now binds to live engine state for the current item and next two queue
items. Local evaluation combines temporary completion, exact owned Local media,
verified Downloads, enabled provider priority, and authenticated path-free peer
summaries. `CrewPrefetchPlanner` chooses capability-based suppliers, but the
runtime requests only an attached peer: in the coordinator-centred topology the
coordinator first fetches from another joiner, publishes temporary availability,
and then redistributes. Reconciliation is serialized, obsolete requests are
cancelled, and rejected/detached suppliers enter one bounded cooldown.

`CrewPrivateSourceRegistry` is the separate per-device source projection for
exact Local media. It exists only for the one active Crew, keys access by
session/member/queue occurrence/candidate identity, and never enters Room,
snapshots, or transport payloads. Local action submission captures the exact
`content://` source before publicizing the queue; playback and supplier
authorization overlay it only on the contributing phone. The control codec
publicizes again defensively, stripping every playable locator and all
Download/Crew-temporary projections while retaining stable provider identity
and credential-free HTTPS artwork.

Primary evidence:

- Android NSD: https://developer.android.com/reference/android/net/nsd/NsdManager
- Android local-network permission:
  https://developer.android.com/privacy-and-security/local-network-permission
- WebRTC Android API: https://webrtc.googlesource.com/src/+/main/sdk/android/README
- WebRTC Android artifact: https://github.com/webrtc-sdk/android

### Playback bridge

Crew never owns a second ExoPlayer. It emits canonical playback/queue commands
to `PlaybackStateManager` and contributes temporary candidates to
`PlaybackResolver`. `CrewTemporaryMediaIndex` is a per-device, active-session
overlay keyed by exact QueueItem and source-candidate identity. It may augment a
local copy of a queue item with a `CREW_TEMPORARY` file candidate, but that
candidate is never serialized into canonical Crew state and is cleared at
session end. `PlaybackResolutionCoordinator` applies this exact active overlay
before verified-download projection, so an already received Crew object wins
without altering the duplicate-safe queue identity or creating another player.
Exact completion triggers one serialized queue re-resolution, allowing a
previously blocked current item to enter the same Media3 player without a
permanent library relationship.

`CrewPlaybackBridge` is attached beside the existing playback-service
observers. Active Crew state is prepared through the normal resolver before it
may replace the Auxio queue; equal queue IDs reuse the current prepared player
state. Repeat, shuffle, current duplicate-safe occurrence, monotonic position,
and play intent are then applied through `PlaybackStateManager`. Normal player,
MediaSession, headset, and UI callbacks debounce into one final-state
comparison and submit only real differences through `ActiveCrewRuntime`, so a
remote application does not ordinarily echo back as a second action. An
existing host player seeds an empty new Crew once.

The bridge removes synthesized `DOWNLOAD` and `CREW_TEMPORARY` candidates before
publication, captures exact Local sources into the active private registry, and
compares only public queue projections. Canonical shared state therefore remains
locator-free without creating a second playback queue.

## 12. UI Runtime

Auxio is an Android Views/Fragments application. Preserve its real UI runtime
and modify it in place. Build Shippy with:

- Existing navigation and lifecycle patterns
- ViewBinding, RecyclerView, fragments, ViewModels, and Hilt
- New Shippy shell and reusable views
- Incremental screen replacement

Compose is not prohibited, but introducing it requires a measured reason. Do not
convert the entire application merely for design fashion.

Home continuation remains projection-only. `RecentListeningTracker` observes
canonical queue selection beside the playback service and writes at most twenty
metadata-only records to one bounded `AtomicFile`; it stores no candidate,
locator, local path, stream URL, Crew identity, or credential. Home combines
that history with verified recent Downloads, pinned Library projections, the
active Crew, and a cache-first Last.fm overview. Row actions return through
unified Search or `ShippyPlaybackController`; Home owns no playback state.

The Quick Settings tile likewise observes `PlaybackStateManager` only while the
tile is listening and sends an explicit media-button command to Auxio's existing
`MediaButtonReceiver`. It does not bind another player or service.

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
