# Shippy Live Status

**Updated:** 2026-07-26
**Current stage:** Stages 2–6 — Library/player completion and Crew connectivity
**Overall state:** In progress

**Current-pass boundary:** Complete and polish functional behavior first.
Accessibility is deferred to the dedicated pre-release hardening pass.

This is the first file to read after `PRODUCT_SPEC.md` whenever work resumes.
Keep it factual and short. Move durable decisions into the canonical documents.

## Completed

- Product owner confirmed Android-only direction.
- Product owner confirmed actual Auxio/Kotlin foundation.
- Clean Auxio source cloned into `X:\piko\shippy` on upstream `dev`.
- Clean Bloomee donor source exists at `X:\piko\bloomee`.
- Canonical product contract created in `docs/PRODUCT_SPEC.md`.
- Detailed UX contract created in `docs/UX.md`.
- Detailed Crew contract created in `docs/CREW.md`.
- Executable staged plan created in `docs/IMPLEMENTATION_PLAN.md`.
- Goal constraint recorded: no repeated heavy Android/Gradle builds on this PC.
- Auxio foundation audit completed with exact source references.
- Bloomee donor audit completed with exact source references.
- Architecture and keep/extend/add/replace boundary documented.
- Working branch frozen from Auxio `dev` as `shippy/main`.
- Graphify code maps created for Auxio `:app` and `:musikr` and merged locally.
- First Shippy code seam implemented:
  - Canonical identifiers, Track, TrackCandidate, QueueItem, and availability.
  - Deterministic playback resolver with Local separation and Crew policy.
  - Eight focused resolver/domain tests authored.
- Exact `musikr.Song` adapter and compatibility resolver implemented without
  changing Auxio's runtime playback path.
- Permanent Liked, Downloads, and Local collection rules modelled.
- Provider capability/health/priority contracts implemented.
- Download lifecycle reducer implemented through verified permanent artifact.
- Deterministic Crew state/event reducer implemented with equal-member queue and
  playback actions, sequence/term checks, snapshot gaps, and coordinator transfer.
- Shippy app label and required network permissions introduced.
- Four primary destinations implemented in the real Android navigation graph:
  Home, Search, Library, and Crew.
- Auxio mini-player/full-player/queue sheets retained and offset above the
  primary navigation; full-player expansion fades navigation away.
- Top-level destination state restoration and Library-only Auxio FAB behavior
  implemented.
- Home now reflects real current playback and real local-library statistics.
- Search is a top-level destination; the mature Auxio local library is retained
  under Library.
- JioSaavn media URL/response utilities and LRC parser ported with focused tests.
- Shippy-owned Room persistence now stores Liked, Downloaded, and user-playlist
  relationships while keeping Local derived.
- Live JioSaavn search and quality-aware stream resolution are implemented
  behind the Shippy provider contract and registered through Hilt.
- Provider HTTP transport enforces HTTPS, bounded responses, and timeouts
  without adding another networking stack.
- Unified provider search isolates partial provider failures and preserves
  cancellation.
- The live JioSaavn search response shape was checked on 2026-07-25; no secret
  credential was required or stored.
- Auxio's single playback manager and ExoPlayer holder now store canonical
  duplicate-safe Shippy queue items while deriving local Song compatibility.
- Local and provider media use one Media3 path with content/file/HTTPS routing,
  per-queue-item request headers, canonical metadata, and no second player.
- Unified Search now renders independent provider sections, scoped failures,
  and a labelled On this device section.
- Provider Search results resolve asynchronously before entering the synchronized
  player authority and can start real JioSaavn playback.
- Mini/full player identity, artwork, queue, MediaSession, notification metadata,
  headset/media-button presence checks, and widgets consume canonical playback
  items; exact local artwork behavior is retained.
- Legacy playback persistence remains local-only and never stores expiring
  provider URLs or headers.
- User-playlist metadata and ordered membership are persisted with explicit Room
  migrations; create, rename, pin, reorder, replace-tracks, and delete operations
  enforce the permanent-system-collection boundary.
- A user-selected SAF download destination now persists Android read/write access,
  reports revoked access, scans supported existing audio, and releases a replaced
  destination grant.
- Durable download jobs now persist track/candidate provenance, progress, failures,
  pending documents, and verified artifacts in the shared Shippy Room database.
- WorkManager/Hilt execution, foreground progress, pause/resume/retry/cancel/remove,
  exact-source resolution, bounded transfer, and content-length verification are
  implemented at code level.
- Duplicate in-process download requests reuse the existing job, and Downloads
  membership is published only from verified available artifacts.
- Download reconciliation now runs at process startup and from the destination
  Settings lifecycle. It validates exact artifact URI and length, repairs missing
  artifacts/relationships, and exposes unknown existing audio without adopting it
  by filename.
- The Auxio Library playlist surface now leads with truthful permanent Liked,
  Downloads, and Local projections, followed by persisted playlist names and the
  existing fully interactive on-device Auxio playlists.
- A native ordered lyrics-source boundary, live LRCLIB adapter, conservative
  recording matcher, synced-LRC parser path, and playback-bound cancellable lookup
  state are implemented. Musixmatch remains an optional future primary source
  requiring an official securely supplied API credential.
- Auxio's real full-player surface now exposes direct Save, capability-aware
  Download, and Queue actions without duplicate overflow commands. Completed
  downloads render as a non-destructive checked state.
- A second Save tap edits Liked and user-playlist destinations. Download removal
  lives behind the provider overflow and an explicit confirmation instead of a
  permanent trash control.
- Provider playback now retains a compact secondary menu for Queue, honest source
  information, and Android sharing rather than hiding the overflow entirely.
- Synced/plain lyrics render below the main portrait player. Active-line selection
  follows playback without rebuilding the full lyric body every tick, stays blank
  before the first timestamp, and avoids automatic TalkBack live-region spam.
- Download publication, removal, and destination reconciliation now share one
  process gate so stale reconciliation cannot overwrite a newly verified
  Downloads relationship.
- WorkManager network constraints are derived from the exact requested download
  candidate, not from unrelated candidates attached to the same canonical track.
- An entirely empty Library retains the permanent collection rows and adds an
  explicit folder-selection onboarding row.
- Permanent Library projections now open real routes: Local switches to Auxio's
  indexed Songs surface, while Liked, Downloads, and Shippy playlists show honest
  relationship-backed counts without inventing playable metadata.
- Persisted Shippy playlists expose rename, pin/unpin, and confirmed delete from
  their detail toolbar. Arbitrary system collection IDs and management mutations
  are rejected defensively.
- Lyrics now persist in the shared Shippy Room database with exact recording
  fingerprints, source identity, synchronized/plain/instrumental payloads, a
  seven-day freshness window, deterministic refresh, and stale offline fallback
  only when upstream lookup fails.
- The lyrics cache is bounded to 500 records, has an explicit v3-to-v4 migration,
  and invalidates corrupt or definitively obsolete entries.
- Musixmatch is documented as the preferred optional synchronized-lyrics source
  through a Shippy-controlled broker; no reusable credential will enter the APK.
- A credential-free Musixmatch broker client is implemented behind an opt-in
  validated HTTPS endpoint. It sends normalized recording identity only, runs
  before LRCLIB, validates returned identity, and applies bounded endpoint
  cooldown after rate limiting.
- Crew control state now includes ordered membership changes, shared
  shuffle/repeat, bounded replay IDs, versioned snapshots, and sequence-gap
  recovery.
- Durable Crew events retain the requesting member but can be installed only
  from the authenticated current coordinator. Snapshots require authenticated
  current-coordinator authority or a strict-majority next-term election
  certificate tied to one persisted checkpoint.
- Pure Crew synchronization now estimates monotonic clock offset from
  lowest-latency probes, schedules common starts/late joins, and emits bounded
  configurable drift corrections without hard-coded device promises.
- Pure Crew reactions are active-session/member scoped, sender-authenticated,
  rate-limited, bounded, and expired against receiver-local monotonic time.
- Crew availability now distinguishes exact local, temporary cache, download,
  preferred/fallback provider, peer-only, blocked, and unavailable without
  sharing file paths or provider URLs.
- Supplier selection is coordinator-independent and deterministic across source
  quality, completion/connection estimates, upload pressure, battery/network
  eligibility, and stable member identity.
- Pure readiness and prefetch planning now cover bounded common starts, late
  joins, current/upcoming priorities, reuse, reprioritization, and cancellation.
- Crew invites now have a strict versioned `shippy://` codec with short-lived
  opaque session identity, redacted bearer secret, optional validated HTTPS
  relay, payload bounds, issue/expiry validation, and corruption rejection.
- The focused transport spike selected Android NSD plus the stripped, shadowed
  WebRTC M144 Android runtime. A data-only peer adapter separates reliable
  control/media from lossy clock/reaction channels, requires a session/member
  binding from the future join authenticator before exposing ordinary traffic,
  queues generation-bound trickle ICE, and returns explicit sender/receiver
  backpressure signals.
- A bounded four-message Crew join handshake now authenticates the short-lived
  invitation secret without transmitting it, confirms both peers finished, and
  binds session/invite lifetime, member identities, roles, fresh nonces, and
  both WebRTC DTLS SHA-256 fingerprints before creating transport peer bindings.
- Android NSD advertisement/discovery now exposes only protocol and opaque
  invitation locators, filters resolved services to the scanned invitation,
  serializes/bounds legacy resolution, handles required multicast locks, and
  redacts endpoint addresses from diagnostics.
- The manifest now declares the Wi-Fi state/multicast permissions needed by the
  legacy Android NSD path. SDK 37 local-network runtime permission work remains
  correctly deferred until Shippy raises its current target from SDK 36.
- LAN rendezvous now opens a bounded QR-secret-authenticated TCP signaling
  channel. Mutual HMAC proof precedes directional AES-256-GCM SDP/trickle-ICE
  frames with ordered nonces, exact sequence checks, timeouts, peer limits, and
  redacted diagnostics.
- LAN signaling treats the presented member as a claim until the existing
  WebRTC join proof binds both members to the negotiated DTLS fingerprints.
  Bidirectional loopback and wrong-secret tests are authored but not run.
- LAN handshake v2 additionally exchanges bounded display-name claims and binds
  both names into the QR-secret HMAC transcript. Names are used only after
  WebRTC proves the paired member ID and remain absent from diagnostics.
- A direct peer-connection driver now serializes offer/answer and ICE restart
  generations, guarantees SDP precedes its gathered ICE, drives all four
  fingerprint-bound join-authentication messages, and exposes the normal Crew
  transport only after mutual authentication.
- WebRTC construction now has a narrow negotiation factory seam. A deterministic
  two-peer simulation covers offer/answer, early gathered ICE ordering,
  coalesced restart negotiation, mutual join proof, opposite-member binding,
  and authenticated transport publication.
- The coordinator now has one pure request sequencer for actions from any active
  authenticated member. It separates requester from publisher, assigns the
  canonical term/sequence, rejects forged issuers, avoids sequence gaps on
  invalid actions, and returns the original event for bounded duplicate retries.
- Client optimistic intent is now bounded and reconciles only against the exact
  accepted event ID, issuer, and action. Conflicts, coordinator rejection,
  timeout, and session replacement produce explicit rollback outcomes.
- Crew durable requests, events, rejection receipts, snapshot requests, and
  snapshot installs now use one versioned bounded binary codec. Complete
  canonical queue/track/candidate data is represented without provider secrets.
- Large control messages now split across digest-bound sub-64-KiB frames and
  reassemble with bounded concurrent state, bytes, chunks, expiry, duplicate
  checks, and final integrity verification.
- The active Crew snapshot now has a singleton Room v5 checkpoint with indexed
  session/term/sequence identity, SHA-256 integrity, race-safe corrupt cleanup,
  and exact-session clearing. Rejoin secrets remain outside generic Room storage.
- An authenticated Crew session engine now routes equal-member action requests
  to the coordinator, sequences and broadcasts accepted events in order,
  validates every payload identity against its peer transport, reconciles exact
  optimistic intent, requests snapshots on gaps, and persists accepted states.
- Each peer has one bounded durable-control actor. Temporary backpressure retries
  without reordering; exhaustion or protocol corruption detaches the peer
  explicitly instead of silently losing a state event.
- Crew admission now binds the proposed member to an attached authenticated
  peer, permits only the current coordinator to publish `MemberJoined`, and
  follows with the canonical snapshot. Membership actions prevent ordinary
  members from admitting or removing someone else.
- Graceful coordinator leave now transfers deterministically to the lowest-ID
  connected successor before its self-leave request. The departing member
  receives the accepted leave event and clears only its exact active checkpoint.
- Crew liveness now uses monotonic configurable reconnect grace and authenticated
  activity recovery. It exposes coordinator-only ordinary-member removal and
  strict-full-checkpoint-majority election eligibility with deterministic
  candidate choice; two-member split brain remains refused.
- The active session engine now reconciles liveness periodically: expired
  ordinary members leave through the ordered coordinator sequencer, while
  ungraceful coordinator loss emits standalone checkpoint-bound votes from each
  authenticated surviving peer.
- Join bootstrap no longer needs fabricated provisional state: a bounded
  pre-engine accumulator waits through valid admission traffic and accepts only
  the authenticated coordinator's normal snapshot containing the exact joining
  member. Wrong-session, forged, malformed, and election-bootstrap snapshots
  are rejected explicitly.
- The LAN host now has a bounded admission coordinator that turns each
  QR-authenticated signaling peer into one fingerprint-bound direct connection,
  verifies the claimed member again at the transport boundary, then attaches
  and admits that exact member with the transcript-bound display name.
  Duplicate attempts replace only their own member connection, while malformed
  later attempts cannot overwrite an already-active member state.
- A join attempt now owns one initiator connection through authenticated
  transport, bounded snapshot bootstrap, and exact session-engine handoff. It
  distinguishes connection setup, transport, bootstrap, timeout, and session
  failures, closes only its owned resources, and cannot activate twice.
- Election votes are retained once per authenticated voter, reject transport
  identity/candidate/checkpoint conflicts, and install a next-term snapshot only
  after a strict majority. A snapshot that arrives before all of its certificate
  votes is held once and retried as those independently authenticated votes
  arrive.
- Active Crew restart credentials now use a single Android-Keystore AES-GCM lease
  outside Room/preferences. A bounded codec/policy pairs it with the checkpoint,
  bounds corrupt local envelopes before allocation, expires or revokes it
  deterministically, and reconnects only through the authenticated session-engine
  seam without relaxing liveness/election rules.
- Relationship-backed Liked, Downloads, and user-playlist details now resolve
  ordered playable rows from a durable canonical Track catalog. Saving a provider
  track persists its metadata independently of download state; unknown legacy IDs
  remain explicitly unresolved and Local keeps the exact Auxio route.
- Tapping a relationship-backed collection row now replaces playback with the
  complete visible collection order, selects the exact duplicate-safe queue
  occurrence, and supports exact Local candidates through the same player
  authority. The current provider path prepares the whole queue before mutation;
  lazy expiry refresh for long provider queues remains future hardening.
- Push & Pull now has an active-Crew-only policy, path/secret-free bounded media
  manifest/chunk codec, per-chunk and whole-object integrity, media-channel adapter,
  exact QueueItem/candidate/target/supplier request identity, authenticated
  outbound request/cancel operations, reserved receiver windows, explicit-resume
  transfer control, and API-24-compatible temporary session cache cleanup.
  Verified completion can now publish a per-device `CREW_TEMPORARY` candidate
  overlay without contaminating canonical Crew state. Active runtime composition,
  supplier selection, prefetch integration, redistribution, and player preparation
  are still incomplete.
- Settings now exposes one default-off Push & Pull toggle with a typed live-change
  contract. Its copy and policy limit automatic temporary media exchange to
  authenticated members of the active Crew, never merely discovered devices.
- Crew now has a stable random per-installation member identity and a persisted,
  UTF-8-bounded listener display name. Neither is derived from an Android,
  hardware, or account identifier, and the stable member value is wrapped in
  the active protocol version rather than regenerated per session.
- Host startup now has a pure secure bootstrap factory for the canonical
  one-member initial state and a 15-minute Shippy invite/link. Session,
  invitation, and 256-bit secret tokens are independently generated; the
  opaque session token intentionally also keys LAN rendezvous discovery.
- The first production LAN host launcher now composes that bootstrap into the
  persisted session engine, one WebRTC runtime, authenticated signaling,
  responder admission, one signaling-peer collector, and NSD advertisement.
  It does not report success until advertisement is active and the exact
  Keystore rejoin lease is saved; failed partial starts clean only their own
  resources and persistence.
- The matching production LAN join launcher now decodes the exact invite before
  allocation, discovers only its advertised rendezvous, authenticates signaling
  and the fingerprint-bound initiator transport, bootstraps one engine from the
  coordinator snapshot, and saves the exact Keystore rejoin lease. Runtime
  `close()` preserves recovery state; explicit `leave()` releases the live join
  and clears only that session's checkpoint and lease. Ordered graceful leave
  remains an active-session integration seam.
- One application-wide `ActiveCrewRuntime` now owns LAN host/join exclusivity,
  survives fragment/ViewModel lifetimes, publishes only UI-safe session
  presentation, rejects overlapping operations, and performs exact explicit
  host end or joined leave. Stale completions cannot replace a newer generation,
  and transport/session objects never enter UI state.
- The Crew destination now binds that runtime through a thin Hilt ViewModel:
  listeners can start a real LAN Crew, join with a pasted Shippy invite, see
  live members, share the host invite, and explicitly end or leave. Starting,
  ending, and sanitized failure states are visible; the UI no longer simulates
  unavailable session actions or claims an unimplemented QR scanner.
- Anonymous YouTube Music is now a second provider adapter: it bootstraps public
  Innertube configuration at runtime, searches WEB_REMIX song results, retries
  playback through Android Music/iOS client contexts, and accepts direct HTTPS
  audio only. Cipher-only formats remain explicitly unsupported.
- Last.fm now has a signed HTTPS client, Android-Keystore encrypted credential
  boundary, Room v7 durable FIFO scrobble outbox, listen-threshold policy,
  oldest-first batch delivery including restart flush, playback-service observer,
  Settings web authentication, encrypted reconnect, sign-out, and visible
  invalid-session reauthorization state.
- Liked, Downloads, and user-playlist track rows now expose direct download,
  resume, and retry actions from the same durable state used by the full player.
  Local rows remain outside provider-download behavior.
- Every Shippy playback entry point now projects the latest exact verified SAF
  artifact into a deterministic `DOWNLOAD` candidate before resolution. The
  offline copy therefore wins ahead of providers while preserving the original
  queue-item identity; mismatched, empty, non-content, or non-final artifacts
  cannot become playable candidates, and repository failure falls back safely.
- The player now offers provider-safe original sharing and a bounded,
  versioned `shippy://track/v1` recording link. Shippy links contain metadata
  and provider provenance only, are handled before file intents, and never
  carry media locators, paths, headers, or credentials.
- The full player now exposes a secondary sleep-timer utility with Off, finish
  current track, and 15/30/45/60-minute monotonic policies. It pauses through
  the canonical playback authority rather than creating a second player state.
- The Crew session engine remains the sole transport collector and now routes
  MEDIA frames through an authenticated member/transport lifecycle seam.
  Rejected or failing media handlers detach only the offending peer explicitly.
- The authenticated Crew media router now binds request/manifest/chunk/
  acknowledgement/complete/retry/reject/cancel handling to the exact attached
  member, permits any authorized member to supply media, retains bounded cache
  assemblies across publication retry, and preserves control connectivity when
  Push & Pull is locally disabled or a supplier lacks the item.
- One active-session Crew media composition now owns the Push & Pull policy,
  temporary cache/index, receiver/router, live Settings toggle, and verified
  download view. Supplier authorization requires the exact active session,
  member, queue occurrence, and original candidate; it can expose only an exact
  private temporary file, exact Local content URI, or verified Shippy download
  as bytes. Provider URLs, file paths, stale candidates, and unrelated devices
  cannot cross the seam.
- Both production LAN launchers now create exactly one active media runtime
  before their session engine, pass it through the authenticated engine media
  lifecycle, and close engine peers before media/cache teardown. The global
  playback coordinator also applies the exact active temporary overlay before
  verified downloads and providers without changing queue-item identity.
  Request scheduling, rolling prefetch, and Crew-to-player commands remain.
- Live host/join sessions now expose one narrow local-action submission seam.
  `ActiveCrewRuntime` generation-checks that seam before and after submission,
  preserves exact local issuer identity, and never exposes an engine or
  transport to UI/player integration code.
- A service-lifecycle `CrewPlaybackBridge` now connects canonical Crew state to
  Auxio's one `PlaybackStateManager` and reconciles normal UI, MediaSession,
  headset, queue, repeat, shuffle, play/pause, and seek changes back through the
  ordered Crew action gateway. Remote queues resolve before replacement,
  duplicate-safe IDs are retained, host sessions seed an existing player once,
  and debounced final-state comparison prevents ordinary echo loops.
- Library's New playlist action now creates a collision-safe, unpinned Shippy
  Room playlist after native name validation. Auxio's existing Import action
  remains a separate device-playlist import.
- Last.fm web-auth protocol support now requests a signed token, creates the
  fixed browser authorization URL, and exchanges an authorized token for
  keystore-ready session credentials. No API key or secret is embedded.
- Last.fm Settings now supports runtime credential entry, one-shot browser
  authorization, explicit completion after return, reconnect, and confirmed
  disconnect. Pending key/secret/token state is memory-only; process death
  during authorization honestly requires starting again.
- Last.fm invalid-session delivery now raises a process-local visible
  reauthorization state. Reconnect reuses the securely stored application
  key/secret, successful authorization or delivery clears the signal, and no
  credential is duplicated into settings state.
- Auxio's existing persisted SAF source/excluded-folder selection, recursive
  local indexing, and forced rescan path satisfy selected Local folder
  management; this foundation is retained instead of duplicated.
- Crew hosts can now show a bounded high-contrast invitation QR or share the
  same short-lived link. Joiners can scan QR-only camera input or use the
  existing pasted-link fallback; hidden/dismissed UI releases the bearer link
  listener and generated bitmap.
- Crew reactions now cross the dedicated lossy channel with bounded decoding,
  active-member authentication, coordinator fan-out, deduplication, rate
  limiting, and receiver-local expiry. The active runtime exposes no transport,
  and Now Playing shows a compact picker plus short floating emoji animation.
- Live Push & Pull now binds to host/join engine state, stamps exact Local
  contributors, prepares the current item plus next two, routes contributor to
  coordinator to remaining members, cancels obsolete requests, retries without
  busy loops, and skips verified temporary objects. A missing current Local item
  offers one plain-language enable prompt when Push & Pull is off.
- Verified temporary-media completion now re-runs the exact Crew queue through
  the existing resolver and single Media3 player without publishing a permanent
  library record or mutating canonical Crew state.
- Selecting a SAF download destination now validates it as an Auxio Local source,
  tracks whether Shippy auto-added it, preserves manual sources/grants, removes
  only a replaced Shippy-owned source, and requests a local reindex.
- MediaStore-mode libraries now append configured SAF sources through the same
  filesystem factory used by indexing and change observation. MediaStore wins
  exact Musikr-path duplicates, so an existing file in the selected download
  folder remains discoverable without changing the user's Local location mode.
- Android playback polish reuses Auxio instead of rebuilding it: the existing
  system equalizer session/panel, ReplayGain processor, public MediaStyle
  notification/lock-screen controls, audio-focus/noisy handling, headset and
  Bluetooth commands, and responsive home-screen widgets remain connected to
  Shippy's canonical player state.
- User-playlist detail now supports long-press track reordering. One complete,
  duplicate-free order is persisted after the drag finishes, unresolved track
  slots remain intact, and permanent system collections cannot enter the path.
- Now Playing keeps provider/source diagnostics, original links, and technical
  metadata behind Song Information while direct Save and Download remain
  unduplicated primary actions.
- Playback persistence now stores the source-neutral canonical queue, exact
  duplicate-safe queue identities, shuffle order, selected heap item, position,
  and repeat mode in Room v8. Restore re-resolves every surviving item from
  durable intent using current provider settings, never persists expiring
  streams or temporary/download projections, and falls back to Auxio's legacy
  local-only checkpoint if canonical restore is unavailable or fails.
- Settings now exposes the actual enabled provider set as preferred and fallback
  choices, persists swap-safe priority, and runs explicit bounded metadata-only
  JioSaavn/YouTube Music health probes with scoped Available, Limited, and
  Unavailable presentation.
- A self-hostable Crew signaling relay foundation now provides a bounded binary
  WebSocket rendezvous, isolated opaque host/join routes, explicit route closure,
  rate/capacity/backpressure limits, health reporting, bounded shutdown, and a
  minimal non-root container. It retains/logs no identifiers or payloads and is
  explicitly not TURN, control fan-out, or media fan-out.
- Android now has a bounded hosted-relay signaling adapter matching that wire
  protocol. Each route performs invite-secret-derived directional AES-GCM,
  exchanges an encrypted member/display-name claim, and still relies on the
  existing WebRTC fingerprint-bound join proof as final membership authority.
  Host multiplexing and join lifecycle are implemented.
- Settings now stores only a validated optional HTTPS relay endpoint through the
  typed Crew contract and provides native edit/replace/clear UI. Invalid or
  corrupt persisted values are treated as unconfigured.
- A configured Crew host now registers hosted signaling before sharing its
  invite, routes LAN and hosted peers through the same admission coordinator,
  and falls back to a fresh LAN-only invite when registration fails.
- A relay-capable join now races scoped LAN rendezvous and hosted signaling,
  closes the losing route, and feeds only the winner into the existing single
  WebRTC/join/session/media runtime. Remote attempts use the official WebRTC
  sample STUN endpoint; no anonymous TURN service or NAT-fallback claim is
  invented.
- Explicit Download now retains verified active-Crew temporary media through the
  existing durable SAF pipeline. Exact temporary bytes are first snapshotted
  into bounded app-private staging so Crew teardown cannot remove them before
  WorkManager runs; terminal publication/cancel/final failure removes the stage.
- Canonical provider and Crew artwork now reaches MediaSession notification and
  home-screen widget bitmaps through Auxio's existing Coil loader. Only
  credential-free HTTPS artwork is accepted, and revision guards prevent a late
  request from replacing newer playback metadata.

## In Progress

- Adding an expandable lyrics surface for compact player configurations and the
  optional Musixmatch broker adapter.
- Completing provider/player action surfaces beyond the first Search-to-play
  vertical slice.
- Reconnection, transport migration, and TURN/relay fallback beyond hosted
  signaling.
- Transport migration after a reconnect.
- Extending the first exact-Local rolling window to adaptive provider-aware
  preparation and supplier failover.

## Not Started

- Full Gradle/Android build.
- Physical-device verification.

## Current Risks

- Auxio upstream `dev` has documented Windows build limitations that require
  exact verification before selecting the day-to-day development branch.
- Bloomee provider logic is Dart/Flutter and must be ported/reimplemented, not
  assumed reusable as native Kotlin.
- Crew transport, join authentication, NSD, encrypted LAN signaling, and direct
  connection orchestration are source/API-inspected only. Loopback/simulation
  tests are authored but not run; NAT traversal, TURN, reconnection, and
  multi-phone behavior remain compile/device-unverified.
- Crew control serialization, chunk reassembly/media transfer, Room checkpoint
  and Last.fm v7 generation, session-engine convergence, and persistence tests
  are authored/static-parsed only and remain uncompiled.
- Canonical playback Room v8 generation, Hilt wiring, fresh-resolution restore,
  and its focused queue-remapping tests are authored/static-checked only and
  remain uncompiled.
- The first top-down Crew/player bridge keeps an exact Local `content://`
  candidate in canonical queue state because current supplier authorization
  still depends on it. A private per-device source projection must remove that
  locator from transmitted canonical state before Crew media is release-ready.
- Safe ungraceful election needs a strict majority. A direct two-member Crew
  pauses after coordinator loss until reconnection; the future relay/witness
  path must restore availability without weakening split-brain safety.
- Room/Hilt generation, JioSaavn/YouTube Music JSON parsing, Last.fm lifecycle,
  and provider playback are compile/device-unverified until the owner build.
- Dynamic provider preferences, Hilt ViewModel generation, and live health
  probe rendering are XML/static-checked only and remain compile/device-unverified.
- WorkManager/Hilt worker generation, SAF storage, and foreground download
  execution are code/static-checked but compile/device-unverified.
- LRCLIB response shape was live-checked on 2026-07-25; Kotlin parsing, Hilt
  multibinding, and playback lookup remain compile/device-unverified.
- Canonical Media3 custom-cache-key/header routing is implemented and
  syntax-checked but remains runtime-unverified.
- Remote provider artwork loading for MediaSession and widgets is
  static-inspected with focused URL-policy tests authored, but remains
  compile/device-unverified.
- Final device behavior is deliberately unverified until the owner performs the
  build/test handoff.
- The hosted-relay Android adapter and launcher wiring are static-checked only.
  OkHttp 5.3.0/Hilt generation, LAN-vs-relay cancellation, route lifecycle,
  encrypted signaling interoperability, and NAT traversal still require a real
  Gradle/Android and relay integration pass.
- Crew temporary-download staging and its WorkManager/SAF handoff have focused
  tests authored but not run. Process death, Crew teardown during transfer,
  destination revocation, and cleanup require owner device verification.

## Next Concrete Actions

1. Finish the remaining player/audio/widget action surfaces.
2. Wire secure rejoin, transport migration, and practical TURN/relay fallback.
3. Extend Push & Pull from the exact-Local top-down path to adaptive supplier
   failover and hybrid download-folder indexing before relay hardening.

## Verification Ledger

| Area | Status | Evidence |
|---|---|---|
| Product requirements | Inspected and documented | `PRODUCT_SPEC.md` |
| UX requirements | Documented | `UX.md` |
| Crew behavior | Documented | `CREW.md` |
| Auxio foundation | Inspected | `AUXIO_FOUNDATION_AUDIT.md` |
| Bloomee donor | Inspected | `BLOOMEE_DONOR_AUDIT.md` |
| App code | Implemented, not compiled | Canonical player/UI/system consumers and direct actions, Room library/canonical-track/download/lyrics/Crew-checkpoint/Last.fm-outbox persistence, SAF/reconciliation, WorkManager transfer pipeline, Library projections/playable details/onboarding, Musixmatch-broker/LRCLIB playback lyrics lookup/cache/synced-line presentation, JioSaavn/YouTube provider and Search-to-play paths, Last.fm secure scrobble foundation, local adapter, Crew reducer/snapshots/clock/reactions/preparation/invites/sequencing/optimistic reconciliation/control codec/framing/session engine, encrypted rejoin lease/orchestration seam, bounded temporary-media transfer foundation, authenticated LAN signaling, direct peer orchestration, WebRTC boundary, and hosted-relay signaling adapter |
| New tests | Authored, not run | Resolver, collections/projections/playable details/canonical-track/Last.fm migration/onboarding, provider registry/YouTube fixtures, download reducer/transfer/persistence/reconciliation/publication gate, lyrics matcher/source chain/cache/broker/active-line timing, Last.fm signing/listen policy, player action presentation, Crew event/snapshot/clock/reaction/preparation/invite/sequencing/optimistic reconciliation/liveness/election/control codec/framing/checkpoint/rejoin/media-cache/transfer persistence/session convergence/signaling/direct-connection/transport/hosted-relay policies |
| External provider shape | Live-inspected | JioSaavn search and LRCLIB exact-lyrics responses on 2026-07-25 |
| Static structure | Parsed | Curated app Graphify AST refreshed after the Crew/player bridge; XML parsing and `git diff --check` used where applicable |
| APK/device | Not verified | Owner handoff stage |
