# Shippy Crew Contract

**Status:** Canonical product and protocol behavior  
**Implementation technology:** Selected by source/API spike; runtime validation pending
**Scope:** LAN, remote P2P, hosted relay, synchronized session state, temporary
peer media, lyrics/artwork, and reactions

Crew is a distributed shared player. It must feel like one player being used by
several equal participants even though each phone may obtain the audio from a
different provider, download, local file, or Crew peer.

## 1. Invariants

1. Every accepted action has one deterministic order.
2. Every participant converges on the same current item, queue, and playback
   intent.
3. A device uses its own playable copy before requesting peer media.
4. Peer media is accepted only from the active Crew when Push & Pull is enabled.
5. Peer media is temporary unless the listener explicitly downloads it.
6. Any participant may supply a track.
7. Any participant may control playback and edit the queue by default.
8. The coordinator is an implementation role, not a privileged product role.
9. Loss of one device must not corrupt session state.
10. Network and provider machinery must not leak into normal playback UI.

## 2. Terms

- **Crew:** one active collaborative listening session.
- **Member:** an authenticated device participating in a Crew.
- **Coordinator:** the member currently sequencing events and publishing the
  authoritative session clock/state.
- **Contributor:** the member who caused a queue item to enter the session.
- **Supplier:** a member able to serve temporary media for a queue item.
- **Candidate:** a possible playable representation of the intended recording.
- **Availability:** a member's summarized ability to play or supply an item.
- **Control plane:** small ordered session messages.
- **Media plane:** chunked temporary audio transport.
- **Relay:** optional hosted service for signaling, control fan-out, and media
  forwarding when direct connectivity is unsuitable.

## 3. Session Identity And Invitation

A Crew has:

- Random session identifier
- Short-lived invitation identifier
- Session secret or authenticated key agreement material
- Protocol version
- Optional relay locator
- Expiry and revocation state

The QR/deep link carries only what is necessary to locate and authenticate the
join. It must not expose reusable private device credentials or assume a local
IP address remains stable.

Join flow:

1. Creator opens Crew and selects Start.
2. Shippy creates session identity and starts local advertisement/signaling.
3. Invite QR/link is displayed.
4. Joiner scans/opens it.
5. Devices authenticate the invitation and negotiate compatible protocol
   capabilities.
6. Direct LAN or remote P2P is attempted.
7. Configured relay is used when direct connectivity fails or group policy
   prefers relay.
8. Joiner receives a snapshot and then the ordered event stream.

## 4. Membership And Coordinator

All members have equal default product permissions. One coordinator exists to
avoid conflicting clocks and unordered writes.

Coordinator responsibilities:

- Assign monotonically increasing event sequence numbers
- Resolve concurrent incoming actions
- Publish snapshots/checkpoints
- Define playback target timestamps
- Track member liveness and capabilities
- Trigger coordinator election/handoff before leaving when possible

Coordinator transfer:

- Graceful: current coordinator nominates a connected compatible member and
  transfers the latest checkpoint.
- Ungraceful: remaining members elect deterministically using the latest term,
  checkpoint, and stable member identifier.
- New coordinator increments the term so stale messages cannot overwrite newer
  state.

The UI does not label the coordinator “host” or give it exclusive controls.

Every member action first carries a stable request ID and authenticated issuer.
The coordinator sequencer preserves that issuer, publishes under its own
authenticated identity, and assigns the one canonical term/sequence. The same
request ID becomes the durable event ID so an optimistic client can reconcile
the exact accepted action. Duplicate retries return the original event;
rejected actions do not create gaps in the event sequence.

Clients keep only a small bounded optimistic ledger. Exact accepted events clear
their matching intent; reused IDs with a different issuer or action are
conflicts. Coordinator rejection, timeout, and session replacement explicitly
roll back pending UI instead of pretending the action converged.

## 5. Canonical Session State

```text
CrewState
  sessionId
  protocolVersion
  term
  lastSequence
  coordinatorMemberId
  members[]
  playback
    queueId
    currentQueueItemId
    state: idle | preparing | playing | paused | buffering | ended
    positionAtEpochMs
    sessionEpochMs
    playbackRate
    repeatMode
    shuffleState
  queue[]
  activeLyrics
  reactionsWindow
```

Each queue item contains:

- Stable queue-item identifier
- Canonical track identity/metadata
- Provider candidate hints
- Contributor member identifier
- Intended playlist/album context when relevant
- Artwork and lyrics references/hashes
- Availability summary per active member
- Explicit recording/version attributes when known

## 6. Ordered Events

Every durable control event has:

- Session ID
- Protocol version
- Coordinator term
- Event ID for idempotency
- Sequence number assigned by the coordinator
- Authenticated publishing coordinator/sequencer
- Issuing/requesting member, retained separately from the publisher
- Client monotonic timestamp
- Event payload

Core event types:

- Member joined/left/updated
- Queue replaced
- Queue item inserted/moved/removed
- Queue cleared
- Current item changed
- Play/pause/seek/skip
- Repeat/shuffle changed
- Availability updated
- Track preparation state updated
- Coordinator transfer
- Session ended

Transient, unordered or separately sequenced events:

- Emoji reaction
- Fine-grained buffering telemetry
- Peer media chunk request/acknowledgement
- Clock probes

Duplicate event IDs are ignored. Events from an old coordinator term are
ignored. A reconnecting member requests a snapshot when a sequence gap cannot be
filled cheaply.

The reducer accepts a durable event only when its declared publisher matches the
authenticated transport peer and that peer is the current coordinator. A newer
snapshot likewise requires an authenticated publisher and either the current
coordinator or a next-term certificate containing a strict majority of
independently authenticated votes for the same base checkpoint and deterministic
candidate. Merely claiming a coordinator ID or reachable-member subset in a
payload is never authority.

## 7. “Tap Any Song” Behavior

When a Crew member taps a song:

1. The local UI immediately shows pending intent.
2. The client emits a request describing the selected playback context.
3. The coordinator accepts it as `QueueReplaced` plus `CurrentItemChanged`, or
   as the appropriate single-item context.
4. All clients receive the same ordered events.
5. Each client resolves/prepares the selected item.
6. Coordinator schedules a future common start time after readiness policy is
   satisfied.
7. Clients start together; stragglers join at the calculated live position.

Playing a playlist replaces the session queue with its ordered items. Add, Play
Next, and Add to Queue remain explicit separate actions.

## 8. Availability Resolution

For every queue item, each member evaluates:

```text
Crew temporary cache
-> Shippy permanent download
-> preferred enabled provider
-> fallback enabled provider
-> eligible active Crew supplier
-> unavailable
```

Local items:

- The device owning the exact file reports `SUPPLY_AVAILABLE`.
- Other devices may independently match and resolve a provider candidate only
  when identity confidence is sufficient.
- Otherwise they request temporary media when Push & Pull permits it.

Availability messages reveal only necessary capability/status, not full local
file paths or unrelated library contents.

## 9. Prefetch

Prefetch is essential to seamless Crew.

- Evaluate the current item immediately.
- Maintain a small rolling preparation window over upcoming queue items.
- Prefer metadata/provider resolution before peer media.
- Request peer chunks early when a track is predicted to be unavailable.
- Bound temporary storage and upload/download concurrency.
- Cancel obsolete work when the queue changes.
- Prioritize current playback, then next item, then later items.
- Persist enough non-media session metadata to recover after process restart.

The exact look-ahead count is adaptive to duration, network quality, storage,
and group size; it is not a user-facing tuning control.

## 10. Push & Pull Media

### User contract

One setting controls participation. It applies only inside the active Crew.

Enabled means:

- This device may temporarily receive a missing track.
- This device may supply an eligible track it possesses.
- Received data may be cached and redistributed inside the same active Crew.
- Nothing is added permanently to the library without explicit Download.

Disabled means:

- No peer media bytes are sent or accepted.
- Metadata/state synchronization continues.
- If the track is peer-available only, Shippy offers to enable the setting.

### Media object

A supplied item is represented by:

- Queue item ID and recording identity
- Media content hash when computable
- Container/codec metadata
- Byte length when known
- Duration
- Chunk size/version
- Per-chunk integrity data
- Session-scoped encryption context

Local filesystem paths and reusable provider credentials never travel.

### Supplier selection

Select suppliers using:

1. Exact recording/content match
2. Already buffered/session-cached copy
3. Connection quality and estimated completion
4. Supplier upload pressure
5. Battery/network policy
6. Stable deterministic tie-break

Supplier is per item and may change. The coordinator is not automatically the
supplier.

### Temporary retention

- Stored in bounded Crew cache.
- Removed on normal session end or according to a short cleanup grace period.
- Excluded from permanent Library/Downloads.
- User Download creates a separate permanent download job and ownership record.
- Crash cleanup removes orphaned Crew cache after restart.

Before the permanent job is enqueued, the exact verified Crew file is copied
atomically into bounded app-private staging. The existing WorkManager transfer,
SAF verification, publication gate, and Downloads relationship remain the only
permanent-download authority. Paused/retryable work retains the stage;
successful publication, cancellation, and final failure remove it.

## 11. Playback Synchronization

### Clock

Clients estimate offset and round-trip delay against the coordinator using
repeated monotonic clock probes. Wall-clock time is not trusted for precise
playback.

### Start

1. Coordinator selects a future session epoch.
2. Members prepare and report readiness/buffer.
3. Coordinator may extend the epoch within a bounded window.
4. Ready members start at the target.
5. Late members seek to the calculated live position after becoming ready.

### Drift

- Measure player position against session position periodically and after
  lifecycle/network events.
- Ignore tiny harmless differences.
- Apply brief small playback-speed correction for moderate drift when inaudible.
- Seek only for large drift or discontinuity.
- Publish buffering state so the coordinator can make a bounded group decision.

Thresholds must be tuned through device tests and are configuration constants,
not guessed product promises.

## 12. Network Modes

### LAN

- Discover active invitation/service through Android NSD/DNS-SD where supported.
- QR provides trust and exact session selection.
- Connect directly over the selected encrypted transport.
- Works without internet once session credentials are exchanged locally.
- Handle Android local-network permissions/API differences.

### Remote direct

- Use lightweight signaling to exchange connection candidates.
- Attempt direct encrypted peer connectivity.
- Keep the control plane low bandwidth and reconnectable.
- Do not assume QR alone can traverse NAT.

### Hosted relay

Relay mode may provide:

- Invitation/signaling
- Authenticated membership
- Ordered control fan-out or coordinator support
- TURN/transport relay where direct connectivity fails
- Media fan-out for larger sessions
- Reconnection and short-lived session checkpointing

The relay is configurable/self-hostable. It is not a permanent music library and
must not retain Crew media after the session/cache window.

The first implemented relay slice is intentionally signaling-only. A bounded
binary WebSocket endpoint registers one opaque host rendezvous and isolated
joiner routes, then forwards only opaque end-to-end-encrypted signaling frames.
It never receives the invitation bearer secret, persists no session state, and
closes a route instead of buffering through backpressure. This foundation is not
TURN, control fan-out, media fan-out, or the completed larger-session relay.
Those remain explicit later layers rather than being implied by the signaling
service.

The matching Android adapter keeps each signaling route end-to-end encrypted.
It derives independent host-to-join and join-to-host AES-GCM keys from the
short-lived invitation secret, exact invite identity/lifetime, configured relay
endpoint, and relay-generated route ID. Strict sequence numbers reject replay
and reordering. The first encrypted hello is still only a claim; the existing
WebRTC DTLS-fingerprint-bound join proof authenticates the actual Crew member.

Production hosting registers that route before exposing a relay-bearing invite.
If registration fails, Shippy closes it and creates a fresh LAN-only invite
rather than sharing a locator that cannot work. A relay-capable join races LAN
rendezvous and hosted signaling; the first authenticated signaling peer owns the
existing WebRTC join path and every losing route is closed. The current remote
path supplies STUN candidates but no anonymous TURN credentials, so this is an
honest direct-P2P attempt rather than a claim that every NAT can be traversed.

### Group scaling

Small groups may use direct connections. Avoid an unbounded full media mesh.
For larger groups, a relay or bounded distribution topology prevents one phone
from maintaining excessive uploads and connections.

## 13. Metadata, Artwork, And Lyrics

The control plane carries compact canonical metadata and content hashes/
references.

- Artwork is fetched independently when possible, otherwise transferred once
  and cached.
- Lyrics are fetched/cached independently when possible, otherwise the active
  Crew may share the selected lyrics document.
- Synchronized lyrics use the same session position.
- Conflicting lyric/provider versions are resolved by the coordinator-selected
  document for the current queue item.
- Large artwork/lyrics payloads are not repeated in every state event.

## 14. Reactions

Reaction event contains:

- Session/member ID
- Emoji from an allowed compact set or safe Unicode selection
- Client event ID
- Short expiry

Reactions are rate-limited, ephemeral, not included in durable chat history, and
not required for state convergence. Clients apply reduced-motion presentation
locally.

The implemented coordinator-centred LAN path carries a bounded binary reaction
event on the dedicated lossy channel. The coordinator authenticates an
originating joiner before fan-out; joiners accept relayed member identity only
from the authenticated current coordinator. Malformed, duplicate, forged, or
rate-limited reactions are dropped without detaching the peer or disturbing
durable playback state.

## 15. Failure Behavior

### Supplier leaves

Use already buffered chunks, select another supplier, try provider resolution,
then pause/skip with a concise explanation only when recovery fails.

### Coordinator leaves

Graceful transfer retains the last checkpoint, increments the term, and resumes
without duplicating queue actions. Ungraceful election requires a strict majority
of checkpoint-bound authenticated member votes. Therefore a direct two-member
Crew cannot safely elect after losing its coordinator: it pauses and waits for
reconnection unless a relay/witness lease is available. This is the deliberate
split-brain-safe tradeoff; the relay path must restore two-member availability.

### Member disconnects temporarily

Continue the Crew for others. Reconnecting member receives a snapshot, resolves
the current item, and joins at live position.

### Internet disappears

LAN Crew continues. Provider-only unavailable tracks resolve from downloads,
temporary cache, or active peer when possible.

### LAN changes

Attempt connection migration/re-discovery. Preserve session identity and event
state during a bounded reconnect window.

### Provider fails

Try fallback provider, then active Crew peer. Never claim a mismatched recording
is the same track.

### Queue changes during prefetch

Cancel obsolete jobs, preserve reusable hashed chunks briefly, and reprioritize.

### Simultaneous actions

Coordinator orders them. Clients reconcile optimistic UI with accepted order
without duplicating items.

### App backgrounds or process is killed

MediaSession/service behavior follows Android requirements. Persist session
checkpoint and rejoin token only for the active session and only for the
necessary lifetime.

## 16. Security And Privacy Requirements

- Authenticate members from the invitation/session key agreement.
- Encrypt control and media in transit.
- Scope media authorization to session and queue item.
- Do not browse or reveal a member’s library to other members.
- Do not reveal local paths.
- Reject replayed/stale-term events.
- Validate payload sizes, media declarations, and chunk integrity.
- Rate-limit joins, reactions, control requests, and media requests.
- Expire invitations and temporary session credentials.
- Provide Leave and End session behavior that revokes active membership.
- Hosted relay logs must exclude media content and sensitive library metadata by
  default.

## 17. Test Matrix

Required deterministic tests:

- Event ordering, duplicates, gaps, snapshot recovery, and term changes
- Simultaneous queue edits
- Coordinator graceful and ungraceful handoff
- Availability resolution for every source order
- Non-coordinator supplier selection
- Push & Pull disabled/enabled transitions
- Temporary retention and cleanup
- Prefetch cancellation and reprioritization
- Clock offset and drift correction decisions
- Provider failure and supplier loss
- Lyrics/artwork deduplication
- Reaction expiry/rate limits

Required later physical-device tests:

- Two and three Android phones on LAN with no internet
- Remote networks behind different NATs
- Relay fallback
- Wi-Fi to mobile-data transition
- Background/lock-screen controls
- One member playing a unique local track
- Large queue edits from several members
- Coordinator and supplier device leaving
- Reduced motion and TalkBack during Crew

## 18. Transport Decision

The 2026-07-25 code/static spike selected Android NSD plus data-only WebRTC:

- `NsdManager`/DNS-SD advertises and discovers the active LAN rendezvous.
- A short-lived versioned Shippy invite authenticates the intended session.
- WebRTC data channels carry encrypted direct control and bounded binary media.
- ICE attempts LAN/remote direct connectivity; configured STUN/TURN supports
  traversal and relay.
- Hosted signaling and larger-session fan-out remain a separate self-hostable
  relay responsibility.

The Android dependency is
`io.github.webrtc-sdk:android-prefixed-stripped:144.7559.09`. It is shadowed to
avoid `org.webrtc` collisions and stripped of irrelevant software video codecs.
Shippy creates no microphone, camera, audio, or video WebRTC tracks.

Four channels isolate behavior:

1. `control`: ordered and reliable.
2. `clock`: unordered with no retransmission.
3. `reaction`: unordered with no retransmission.
4. `media`: ordered, reliable, chunk-bounded, and backpressure-limited.

Ordinary Crew frames are not exposed until the join authenticator verifies a
session/member transcript bound to the short-lived invitation. The WebRTC
wrapper treats a member ID presented during signaling as a claim, not authority.
Trickle ICE candidates are tagged with an increasing negotiation generation and
queued until the matching remote description is installed.

The join proof is a bounded initiator hello, responder proof, initiator proof,
and responder-finished confirmation. The final confirmation prevents either
side from opening ordinary Crew traffic before mutual authentication converges.
HMAC-SHA-256 uses the QR invitation secret without transmitting it. The
canonical transcript binds invitation/session identity and lifetime, both
member IDs, fresh 256-bit nonces, protocol version, roles, and both WebRTC DTLS
SHA-256 fingerprints. Replayed state-machine steps, wrong secrets, expired
invites, changed fingerprints, and mismatched sessions/members are rejected.

LAN DNS-SD publishes only protocol, opaque session locator, and invite ID. A
joiner resolves only the service matching its decoded QR/link invitation.
Android's legacy multicast-lock requirement is handled without exposing the
secret or treating an NSD result as authenticated. The resolved address is only
a short-lived signaling rendezvous; the join proof and WebRTC DTLS remain the
security boundary.

At that rendezvous, a bounded TCP channel performs mutual invitation-secret
proof before accepting SDP or ICE. Its transcript binds protocol, invitation
identity/lifetime, canonical session, both member claims, and fresh nonces.
Subsequent versioned signaling frames use independent directional
HKDF/HMAC-SHA-256-derived AES-256-GCM keys, monotonically ordered nonces, strict
size limits, and finite connection/handshake timeouts. A LAN signaling member ID
remains only a secret-holder claim until the later WebRTC join proof binds both
members to the negotiated DTLS fingerprints.

The direct-connection driver assigns one offerer, serializes offer/answer and
ICE-restart generations, and deliberately holds locally gathered ICE until its
matching SDP has been sent. After the data channels report ready, it drives the
four join-authentication messages and publishes an ordinary Crew transport only
after the exact offer/answer fingerprints and opposite member identity verify.
Signaling and pre-authentication frames therefore cannot bypass the transport
gate.

Inbound control overflow is a protocol failure because durable state cannot be
dropped. Clock/reaction overflow is intentionally lossy. Media overflow emits a
bounded drop signal for manifest/chunk acknowledgement and retry instead of
terminating the Crew connection.

Durable control messages now have one explicit versioned binary representation:
member action requests, coordinator events, snapshot requests, snapshot
installs with optional election votes, and request rejection receipts. The
codec represents complete canonical queue/track/candidate metadata with strict
per-field, collection, and total-message bounds. Logical messages larger than
one 64 KiB control-channel payload are split into digest-bound frames and
reassembled under fixed in-flight byte/count/expiry limits. This is control
message framing only; authenticated peer identity and event authority remain
session-engine checks.

The active process-restart checkpoint uses the same snapshot representation in
one integrity-checked Room record. Its separately indexed session, protocol,
term, and sequence must agree with the decoded snapshot. Media is not persisted
there. Invitation/rejoin secrets live only in a single Android-Keystore-encrypted active
lease outside Room and generic preferences. Restore accepts the lease only when it is
unexpired and matches the persisted session/member checkpoint; orphaned, mismatched,
expired, locally revoked, or authentically refused leases are discarded. A process/network
restore only reconnects by attaching an authenticated transport to the ordinary session engine,
which retains the existing checkpoint, ordered-event, reconnect-grace, and election rules.

The active session engine now connects these messages to authenticated peer
transports. A member request is queued only to the current coordinator; the
coordinator sequences it once, persists the accepted state, and broadcasts the
event through one ordered outbound actor per active member. Receivers validate
the transport publisher, reduce, reconcile optimistic intent, and persist.
Sequence gaps trigger an authenticated snapshot request. Exact coordinator
rejections roll back the matching request. Control backpressure is retried for
a bounded time and then becomes an explicit peer failure rather than a dropped
event. Higher-term election snapshots remain rejected until their votes have
been collected over independently authenticated peer paths.

Admission and graceful departure now remain inside the ordered stream. The
current coordinator alone admits an authenticated pending peer, then sends that
peer the canonical post-admission snapshot. A normal member may update or remove
itself but cannot admit or kick another member. A departing coordinator first
transfers to the deterministic lowest-ID connected successor; its self-leave
request follows the transfer on the same ordered channel. The leaving device
clears its exact checkpoint only after receiving the accepted leave event.

Normal session termination also remains inside the ordered stream. The owner
publishes `SessionEnded`; every member converges to terminal playback, clears
its recoverable checkpoint, pauses its canonical player, and then releases
transport and temporary-media resources. The session engine deliberately keeps
outbound actors alive until its owning runtime tears down, so the terminal
event cannot be cancelled immediately after it is queued. A joined member
leaving an active multi-member Crew still uses ordered self-leave/handoff
instead of ending the session for everyone.

The LAN runtime now has explicit host and join ownership seams. The host turns a
QR-authenticated signaling peer into one fingerprint-bound responder transport,
rechecks the claimed member ID, attaches it to the active engine, and sequences
admission. The joiner owns one initiator attempt, consumes only the authenticated
coordinator's control frames until the canonical admission snapshot arrives,
then creates its engine from that exact snapshot and hands the same transport to
the engine without leaving a second collector behind. The current functional
topology is coordinator-centered; direct all-member mesh connectivity, safe
coordinator-independent peer media routing, and relay fan-out remain explicit
runtime work rather than implied completion.

Transport loss now enters a monotonic reconnect grace instead of immediately
removing membership. Authenticated activity cancels expiry. After grace, the
current coordinator may sequence removal of an ordinary member. Coordinator
loss produces an election opportunity only when connected survivors are a
strict majority of the full checkpoint, with the lowest stable member ID as
candidate. A two-member Crew still pauses safely after coordinator loss.

The session engine now consumes those decisions on a bounded periodic loop.
Ordinary-member expiry becomes a normal ordered `MemberLeft` event. Ungraceful
election votes travel as standalone control messages, so each receiver matches
the embedded voter to its authenticated peer before retaining the vote. Votes
are bound to the exact term/sequence/membership checkpoint and deterministic
candidate, and one voter cannot change its vote. The elected candidate installs
and broadcasts the next-term snapshot only after strict majority. A receiver
may hold one early election snapshot until every vote in its attached
certificate has arrived over those independently authenticated paths.

Availability is a separate transient control message, not a durable reducer
event. A member publishes only queue-item IDs and an availability enum tied to
the exact session, protocol, coordinator term, and accepted sequence. Joiners
send their own summary only to the coordinator. The coordinator authenticates
the embedded publisher against that peer and fans it out; joiners accept a
relayed publisher only from their authenticated current coordinator. Membership,
queue, term, or sequence changes prune the summary, and no availability data is
written into the active checkpoint.

The active media runtime derives only the current item plus the next two local
summaries. It prefers active temporary bytes, an exact owned Local candidate,
verified Downloads, and enabled provider priority before publishing `PEER_ONLY`
or `UNAVAILABLE`. Prefetch consumes authenticated summaries through the pure
supplier planner, requests only an attached peer, cancels obsolete work, and
temporarily cools a failed supplier. In the coordinator-centred topology, a
joiner does not attempt an unreachable joiner directly: the coordinator first
fetches and republishes its temporary availability, then redistributes it.

This decision and the join/NSD adapters satisfy the architecture gate at
source/API level only. Authenticated local signaling sockets are implemented and
have loopback tests authored, but are not compiled or device-proven. NAT, TURN,
migration, hosted relay, and multi-phone runtime proof remain implementation
work and owner device acceptance.

QR is an invitation/authentication mechanism. Signaling, ICE/STUN/TURN, LAN
discovery, and relay remain necessary and are not collapsed into “QR contains
the address.”
