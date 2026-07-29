# Shippy Live Status

**Updated:** 2026-07-29
**Current stage:** R11.2 exact queue-order hotfix and owner-device handoff
**Overall state:** R11.2 removes a second playback-layer mutation that rotated
neighbouring tracks after every normal-queue drag. Normal and shuffled queues
now use separate exact move plans, covered in both directions. Formatting, 444
app JVM tests (443 passed; one opt-in live smoke skipped), 19 relay tests,
native compilation, lint, APK assembly, signature/package inspection, and
Drive publication pass.
Physical-device performance, provider longevity, and multi-phone Crew
acceptance remain owner-tested.

**Progress snapshot:** source implementation ~99% · functional product ~96% ·
UX intent ~98% · automated verification 100% · APK verification 100% ·
physical-device R11.2 verification 0%

**Current-pass boundary:** Complete and polish functional behavior first.
Accessibility is deferred to the dedicated pre-release hardening pass.

**R11.2 artifact:** `Shippy-Alpha-R11.2-20260729.apk`, version
`0.1.0-alpha.1-r11.2` (77), 61,048,721 bytes, SHA-256
`B7FB3DFDC830C938D1C2BD1279C376BA2DB851B0D9C69B45E661C013CCB0EFCA`,
APK Signature Scheme v2 verified. It is the only APK at
`gdrive:Shippy Builds`; 13 older builds are under `Shippy Builds/Archive`.

This is the first file to read after `PRODUCT_SPEC.md` whenever work resumes.
Keep it factual and short. Move durable decisions into the canonical documents.

## Completed

- R11.2 exact queue-order hotfix:
  - Removed the inherited second media move that rotated adjacent tracks after
    the requested queue move had already completed.
  - Normal queues now perform exactly one physical media-item move.
  - Shuffled queues update their logical shuffle order directly, preserving
    every unrelated track's relative position.
  - Five focused move-plan tests cover upward, downward, shuffled, unchanged,
    and invalid moves.
- R11.1 queue/player state hotfix:
  - Normal queue drag previews are reconciled against the canonical queue
    acknowledgment, so the same move is not replayed and visually reverted.
  - Drag commits derive their destination from the moved row's stable
    `QueueItemId` and final neighbours, not a transient RecyclerView index.
  - Failed drag commits restore the canonical visible queue.
  - Queue and player mapping jobs publish the index captured with their queue
    snapshot and reject canceled work before updating UI state.
  - Full-player cover swipes target the visible pager item's stable identity,
    removing the independent queue-sheet mapping race that could leave stale
    Now Playing metadata/artwork.
  - Local-player toolbar context and cleared mini-player state no longer retain
    stale metadata. Artist/genre fallback dialogs also route to the correct
    decision type.
  - Four focused order-reconciliation regression tests pass alongside the full
    app and music-library suites.
- r9 collection and player interaction refinement:
  - A dedicated full-player gesture observer sees deliberate downward swipes
    before ViewPager, seek, buttons, or lyrics children consume the touch
    stream. It does not consume child events. On the tall-phone lyrics surface,
    content scrolls to the top before the same gesture collapses the sheet.
  - Liked, Downloads, Local, and user playlists share one performant,
    artwork-led detail surface with search, sort, metadata, full-width rows,
    Play, Shuffle, collection download state, and restrained management.
  - Local requests audio permission when first opened, uses generated Shippy
    artwork, and maps large indexed libraries on a background dispatcher.
  - User-playlist drag is constrained to track rows and cannot cross the
    collection header. The consistent track action sheet can add or remove a
    track from any existing user playlist.
  - A subtle Lyrics affordance adds breathing room below the player controls so
    the lyrics card does not intrude into the initial player fold.
  - `:app:compileDebugKotlin`, all 430 normal JVM tests, `spotlessCheck`,
    `:app:lintDebug`, `:app:assembleDebug`, and APK Signature Scheme v2
    verification pass.
  - r9 artifact:
    `gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r9.apk`, size
    `66,884,684` bytes, SHA-256
    `F295778EDD937BBFA9B67591FBC592BEB6341F96895153AF0F65489541172FA1`,
    MD5 `01B4DA521B0F3104D7B0DCB6B63A639D`.
- r8 player and lyrics refinement:
  - The existing player sheet again intercepts downward drags while expanded.
    Tap or upward-drag on the mini-player opens the full player; a downward drag
    on the full player collapses to the mini-player using the same native sheet
    physics. Queue-open state still disables player dragging.
  - Tall-phone Now Playing adds a restrained square artwork thumbnail beside
    track identity. Sleep timer remains visible at the left of the utility row;
    Download appears only for downloadable provider/Crew media, with Queue at
    the right. Local tracks never show a misleading download action.
  - Full lyrics uses a centered track header, native edge fade, matte inactive
    lines, a substantially larger/bolder active line, conditional Download, and
    the same direct consolidated three-dot action sheet as Now Playing.
  - The timed Material motion scale and selection fades are another 10% faster,
    for an effective roughly 1.4x tempo, without replacing Material easing or
    bypassing Android's system animator scale.
  - `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `spotlessCheck`,
    `:app:lintDebug`, `:app:assembleDebug`, and APK Signature Scheme v2
    verification pass.
  - r8 artifact:
    `gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r8.apk`, size
    `66,875,762` bytes, SHA-256
    `CD90A2CC0157CE60E505DE350EF9D49B9C3399E102A2CF6F6CE1EA7241DAF234`,
    MD5 `DF8F1ED83B6D44B97EDD52EEACAD3133`.
- r7 owner-device refinement:
  - Now Playing follows the selected core layout: top-right consolidated
    overflow, save/playlist plus-to-tick beside track identity, seek and
    canonical shuffle/repeat controls, Download and Queue utility actions
    beneath playback, then the wider synced-lyrics card.
  - Player overflow opens the exact track action sheet directly from the
    player FragmentManager. It no longer emits a global menu event that waits
    for Search to become visible.
  - Player-only overflow actions retain Lyrics, sleep timer, and the system
    equalizer without duplicating them in the visible control row.
  - Collection, provider-detail, and unified Songs rows show one consistent
    three-dot action surface; downloaded tracks are first-class Songs rows and
    remain deduplicated from matching indexed Local media.
  - Library initially retains only the active tab and nearest neighbor instead
    of inflating all five artwork-heavy tabs. Songs keeps a small recycled-view
    cache and avoids unnecessary item animations.
  - Synced lyrics use explicit active/inactive colors, stronger current-line
    weight, wider spacing, a taller preview, and corrected full-sheet header
    alignment. Plain lyrics remain honestly unsynchronized.
  - Liked and Downloads use real square Shippy artwork rather than generic
    placeholder glyphs.
  - Material motion durations are uniformly reduced to 80% of their previous
    values (1.25x faster) while Android's system animator scale remains
    authoritative.
  - `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `spotlessCheck`,
    `:app:lintDebug`, `:app:assembleDebug`, and APK Signature Scheme v2
    verification pass.
  - r7 artifact:
    `gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r7.apk`, size
    `66,875,574` bytes, SHA-256
    `17A805C374F38A366E6201EE6AAF3391C2824AF564EDE06F4D8B40C9E39A84C5`,
    MD5 `D6827E57D71A1327C4FE4B6950A925F9`.
- r6 owner-device stabilization:
  - Library Songs uses Auxio's proven native `SongAdapter` path again. The
    unstable r5 Downloads/Songs adapter merge is removed; Downloads remains a
    permanent collection while a safe unified implementation is designed.
  - Malformed persisted canonical metadata is skipped and logged instead of
    terminating every Library observer.
  - Playlist artwork now comes from one bounded Room join projection instead
    of rebuilding a full canonical-track map on every Home/Library update.
  - Home recent local rows bind actual Auxio `Song` covers, preserving embedded
    local artwork; provider rows retain normalized remote artwork.
  - Provider track actions use a bounded transient registry so Search, Home,
    Now Playing, and Crew can open the same action sheet without requiring a
    share-link-compatible track.
  - Home, Crew, collection detail, and provider detail reserve the complete
    persistent mini-player/navigation inset. Their last content can scroll
    fully above app chrome.
  - The plain black lyrics block is replaced by an Auxio/Material surface with
    current/upcoming synced lines and a direct full-lyrics action.
  - Quality controls move into Audio settings instead of cluttering the root.
  - Crew QR joining uses permissionless Google Code Scanner with QR-only
    detection and auto-zoom; the custom scanner activity and app camera
    permission are removed.
  - `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `spotlessCheck`,
    `:app:lintDebug`, `:app:assembleDebug`, and APK Signature Scheme v2
    verification pass.
  - r6 artifact:
    `gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r6.apk`, SHA-256
    `6922E0AB940D4389BE5BC7779BF99C73F1434789EB90FB52DFC34B54EB964280`.
- Core UX refinement pass:
  - Primary navigation delegates to `NavigationUI`, preserving each root
    destination's state instead of reconstructing the selected tab.
  - Queue dragging updates only the visible adapter during motion and commits
    one canonical queue mutation on release.
  - Unified Search renders on-device results before provider results and no
    longer jumps to the top when an async provider page arrives.
  - Provider tracks use one full-width Auxio/Shippy bottom-sheet action surface
    across Search and Now Playing. Failed menu navigation is consumed so an old
    sheet cannot appear later on another screen.
  - Library's default order is Songs, Playlists, Albums, Artists, Genres.
    Local remains a distinct permanent realm. Playlists use available track
    artwork.
  - Now Playing scrolls into a synced lyrics preview card; tapping it opens a
    large synced lyrics sheet on the same playback timeline.
  - Streaming and download quality are separate persisted settings and flow
    into provider resolution for normal playback, Crew playback, and downloads.
  - Shippy-visible About identity and download iconography replace remaining
    user-facing Auxio/chevron artifacts while upstream legal attribution stays
    intact.
  - `:app:compileDebugKotlin` and `:app:testDebugUnitTest` pass after these
    changes.
  - r5 was built and uploaded, then invalidated by the owner-device regression
    report. Use r6 for the next acceptance pass.
- Owner-device regression pass:
  - Primary navigation owns the exact child `NavHostFragment` controller rather
    than asking the container view to resolve a controller from nested graphs.
    FAB destination checks use that same retained controller.
  - Local requests the correct Android media permission when first opened and
    resumes indexing after grant.
  - Provider queue resolution and expensive playback/queue display mapping run
    off the main thread to remove the visible tap freeze.
  - Queue content uses physical system/gesture insets without compounding the
    nested player-sheet offset.
  - JioSaavn and YouTube artwork mapping rejects blanks and preserves normalized
    remote artwork URLs.
  - Search has a compact selector for JioSaavn, YouTube, and YouTube Music
    without mutating global provider priority.
  - YouTube and YouTube Music use a shared NewPipe Extractor gateway with
    distinct filters and progressive-stream fallback.
  - Primary tabs use one explicit inner-graph root stack; destination
    transitions no longer invoke a reflected private FAB API that could
    terminate the app.
  - A closed Queue is fully off-screen and opens only through the explicit
    player Queue action, so it cannot cover playback controls.
  - Home and Crew app bars consume system insets. Home exposes an always-visible
    Settings action wired to the existing settings flow.
  - Primary navigation now uses one deterministic root back stack without
    restoring stale tab state. Its controller is retained across the view
    lifecycle instead of being rediscovered while Settings destroys the inner
    host.
  - Download-destination inspection treats revoked or malformed persisted SAF
    access as unavailable instead of allowing an uncaught Settings/startup
    coroutine failure.
  - Relationship-backed collection details now install a real linear layout
    manager, so submitted Liked, Downloads, and playlist rows are rendered.
- Regression verification:
  - 431 app JVM tests discovered: 430 executed successfully and the opt-in
    live-provider smoke skipped during the normal suite.
  - The opt-in real-network YouTube Music search and stream-resolution smoke
    passed separately.
  - Formatting, Android lint, debug compilation, assembly, APK Signature
    Scheme v2 verification, and four-ABI packaging passed.
  - The current r6 APK is v2-signed and uploaded at
    `gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r6.apk`; its SHA-256 is
    `6922E0AB940D4389BE5BC7779BF99C73F1434789EB90FB52DFC34B54EB964280`.
- Native Windows build support is verified for the custom FFmpeg and TagLib
  toolchains across arm64-v8a, armeabi-v7a, x86, and x86_64. App JVM tests,
  109 Musikr JVM tests, 19 relay tests, Android lint, and `app:assembleDebug`
  pass.
- Home now completes the product continuation hierarchy with bounded
  metadata-only recent listening, verified recent Downloads, pinned Library
  projections, active Crew state, and an account-scoped cache-first Last.fm
  play-count/top-track section. Home rows reuse unified Search or the canonical
  playback controller and create no second player state.
- Android now registers a playback Quick Settings tile that observes the
  canonical player only while active and dispatches play/pause through the
  existing MediaSession receiver.
- The app code graph now parses 476/476 curated app files into 9,594 nodes and
  955,547 edges without clustering. Final Shippy placeholder/TODO scanning found
  no source placeholder; the remaining two matches are inherited layout view
  IDs. The owner build and physical acceptance matrix is documented.
- Crew renewable rejoin is source-complete: a versioned, Keystore-only v2 lease
  never persists the public invite secret; authenticated post-admission delivery,
  exact member/session validation, bounded LAN/hosted-relay private-candidate
  selection, reissue revocation, and QR-expiry redial are wired. Focused JVM
  tests are authored; compile and multi-device recovery remain unverified.
- Playback transitions now retain native gapless as the default and add an
  opt-in bounded 1–12 second two-player equal-power crossfade. The standby
  player has its own ReplayGain processor, cannot own MediaSession/audio focus,
  and is cancelled for Crew, repeat-one, mutations, seeks, errors, or settings
  changes. This is source/static-verified only.
- The active Crew surface now renders authoritative current-track metadata and
  artwork, playback mode, contributor, shared-queue preview, the canonical
  queue entry action, real reaction sending/presentation, and the Push & Pull
  fallback prompt. It does not create a second queue or playback authority.

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
- Home now reflects canonical Local/provider playback, real local-library
  statistics, a truthful active-Crew card, permanent Liked/Downloads/Local
  shortcuts, and pinned Shippy playlists without duplicating primary navigation.
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
- JioSaavn Search now also returns bounded album, artist, and playlist records.
  Their opaque provider tokens open an internal Auxio-native detail surface,
  respect the existing Search filters, and play, shuffle, play next, or append
  the returned tracks through the same canonical queue authority. The detail
  rows reuse the permanent-download presentation and WorkManager coordinator,
  including progress, retry, resume, and confirmed removal. Artist top tracks
  use explicit, bounded continuation with an honest Load more action. Recorded
  fixtures cover category isolation, browse shapes, and continuation; the live
  category and detail endpoints were inspected on 2026-07-26.
- Provider albums, artists, and playlists now use one direct plus/check action,
  persist through a non-destructive Room v9 migration, support pinning from the
  restrained detail overflow, render artwork-first in Library, and reopen
  through their exact provider identity. Pinned provider collections join Home's
  existing permanent and playlist shortcuts. Metadata refresh preserves
  existing pin and save-time state.
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
- Explicit Crew end now publishes a versioned ordered terminal action before
  teardown. Every converged member clears its recovery checkpoint, pauses the
  canonical player, and lets the application owner release live transport and
  temporary-media resources without cancelling queued terminal delivery.
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
  transfer control, and API-24-compatible temporary session cache cleanup. Each
  transferable object is capped at 8 MiB with 44 KiB chunks; one supplier has at
  most two active uploads and one per target, with no hidden pending upload queue.
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
  `close()` preserves recovery state; explicit `leave()` first requests ordered
  self-departure (or terminal end when it is the sole member), then releases the
  live join and clears only that session's checkpoint and lease.
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
- Active members can now publish bounded, path-free availability for exact
  queue occurrences at one canonical Crew checkpoint. Joiners send only to the
  coordinator; authenticated coordinator fan-out preserves the original member,
  and queue/member/term/sequence changes prune transient summaries rather than
  persisting stale capability into checkpoints.
- Library now uses the existing persisted playlist-position authority for
  long-press Shippy playlist ordering. Dragging is limited to user playlists
  within the same pinned group, and concurrent create/delete/pin changes reject
  stale partial orders instead of corrupting the collection list.
- Crew media preparation now evaluates the current item plus the next two
  against active temporary media, exact Local ownership, verified downloads,
  enabled provider priority, and authenticated peer summaries. The existing
  prefetch planner selects reachable suppliers, joiners wait for coordinator
  redistribution when another joiner is unreachable, failed suppliers enter a
  bounded cooldown, and obsolete transfers are cancelled.
- Canonical Crew queue state is now path- and locator-free. One active-session
  private source registry retains exact Local `content://` access only on the
  contributing device, overlays it only for local resolution/supply, prunes it
  with the accepted queue, and clears it at session end. The control codec
  defensively removes every playable locator plus Download/Crew-temporary
  candidates before any queue item reaches a transport.
- Relay Settings now performs one bounded cancellable `GET /healthz` against the
  configured HTTPS origin and reports Checking, Reachable, or Unreachable
  without resolving media or exposing session identity.
- A joined Crew now keeps one single-owner reconnect loop beside the existing
  session engine. After the current coordinator transport is actually detached,
  it re-races LAN/hosted signaling, repeats the invitation- and
  fingerprint-bound WebRTC authentication, attaches the replacement transport
  to the same engine, and requests a canonical snapshot. Backoff is bounded,
  Android default-network changes wake a pending retry, stale/wrong-coordinator
  results are closed, and the UI shows reconnecting or expired without exposing
  addresses or secrets.
- Android Auto remains connected through Auxio's exported
  `MediaBrowserServiceCompat`, automotive descriptor, Local browse/search tree,
  and the same canonical Shippy MediaSession queue and controls used by the app,
  notification, headset, and widgets. Provider browsing is not invented as a
  second catalogue surface; device/head-unit verification remains deferred.
- Compact player configurations now expose a restrained Lyrics dialog backed by
  the same cancellable cached LRCLIB/Musixmatch-broker state as the full portrait
  player. It renders loading, synchronized current line, full/plain lyrics,
  instrumental, unavailable, retryable failure, and Retry without creating a
  second lyrics authority.
- The self-hosted relay can now optionally mint short-lived coturn REST
  credentials only for an exact active opaque host registration. Android
  requests them without the invitation secret, bounds and validates the
  response, keeps public STUN, and refreshes TURN credentials on joined-session
  reconnect attempts. TURN remains a transport service operated beside the
  signaling relay; the relay itself still does not retain or proxy media.
- Hosted relay registration now emits a 32-byte opaque in-memory host-resume
  credential. Unexpected host loss closes routes but holds rendezvous presence
  for a bounded default 30 seconds; valid resume rotates the token/verifier and
  restores the same host registration. Hostless sessions reject new joins and
  relay restart still loses all state. This is not relay control/media fan-out.
- Queue expansion now anchors the current duplicate-safe playback item near the
  upper third of the sheet after both action-button and drag-open paths. If the
  queue projection is still loading, the request is retained and fulfilled as
  soon as the adapter receives canonical state.
- Shuffle keeps the same expressive adjacent-button spring used by Repeat, but
  its heavier queue-order mutation is coalesced and committed after the first
  animation frame window. The icon/check state changes immediately, rapid taps
  collapse to the final requested value, and the queue remains authoritative.

## In Progress

- No remaining functional first-pass source work is knowingly open.
- Owner physical-device regression acceptance of the 2026-07-27 APK is the
  next gate.
- Accessibility remains deliberately deferred until that gate passes.

## Not Started

- Physical-device verification of the current regression-fixed APK.
- Real multi-device Crew acceptance.

## Current Risks

- The current APK cannot be exercised locally because no physical device is
  connected and this PC lacks Android emulator hardware acceleration.
- YouTube extraction can change upstream; an opt-in real-network smoke test is
  retained for refresh checks.
- JioSaavn relies on an undocumented external API and can drift.
- Crew still needs a real multi-phone LAN/remote acceptance matrix.

## Archived Pre-build Risks

- Auxio upstream `dev` has documented Windows build limitations that require
  exact verification before selecting the day-to-day development branch.
- Bloomee provider logic is Dart/Flutter and must be ported/reimplemented, not
  assumed reusable as native Kotlin.
- Crew transport, join authentication, NSD, encrypted LAN signaling, direct
  connection orchestration, Android TURN retrieval, and reconnection are
  source/API-inspected only. Loopback/simulation/parser tests are authored but
  not run; NAT traversal and multi-phone behavior remain compile/device-unverified.
- Crew control serialization, chunk reassembly/media transfer, Room checkpoint
  and Last.fm v7 generation, session-engine convergence, and persistence tests
  are authored/static-parsed only and remain uncompiled.
- Canonical playback Room v8 generation, Hilt wiring, fresh-resolution restore,
  and its focused queue-remapping tests are authored/static-checked only and
  remain uncompiled.
- The private Crew source projection, protocol no-leak boundary, and relay
  health rendering are static-inspected with focused tests authored, but remain
  compile/device-unverified.
- Joined-session reconnect/controller tests are authored and Graphify parses the
  production wiring, but network callbacks, redial, authenticated replacement,
  and snapshot recovery remain compile/multi-phone unverified.
- Safe ungraceful election needs a strict majority. A direct two-member Crew
  pauses after coordinator loss until reconnection; the future relay/witness
  path must restore availability without weakening split-brain safety.
- Room/Hilt generation, JioSaavn/YouTube Music JSON parsing, Last.fm lifecycle,
  and provider playback are compile/device-unverified until the owner build.
- JioSaavn multi-type Search and provider-detail parsing use undocumented web
  endpoints. Recorded fixtures and a 2026-07-26 live shape inspection reduce
  drift risk, but Kotlin/Hilt/navigation wiring remains compile/device-unverified.
- Dynamic provider preferences, Hilt ViewModel generation, and live health
  probe rendering are XML/static-checked only and remain compile/device-unverified.
- Room v9 saved-provider migration, Hilt binding, generated Safe Args, and
  Library/detail rendering are Graphify/XML/static-checked only and remain
  compile/device-unverified.
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
- The optional coturn credential endpoint has passing Node unit/integration
  coverage, but Android parsing/wiring remains uncompiled and real TURN
  allocation needs owner-operated relay/device verification.
- Crew temporary-download staging and its WorkManager/SAF handoff have focused
  tests authored but not run. Process death, Crew teardown during transfer,
  destination revocation, and cleanup require owner device verification.

## Next Concrete Actions

1. Install `Shippy-debug-4.1.3-20260727-r10.apk` on the owner phone.
2. First open Library repeatedly, test track actions from Search/Home/player/
   Crew, and scroll Home and Crew to their final content with the mini-player
   visible.
3. Verify local Home artwork, the synced lyrics card/full view, and native Crew
   QR scanning. Then retest playback latency and queue interactions.
4. Return the first reproducible runtime failure, then run the real multi-device
   Crew LAN/remote matrix.
5. Start accessibility and release polish only after functional acceptance.

## Verification Ledger

| Area | Status | Evidence |
|---|---|---|
| Product requirements | Inspected and documented | `PRODUCT_SPEC.md` |
| UX requirements | Documented | `UX.md` |
| Crew behavior | Documented | `CREW.md` |
| Auxio foundation | Inspected | `AUXIO_FOUNDATION_AUDIT.md` |
| Bloomee donor | Inspected | `BLOOMEE_DONOR_AUDIT.md` |
| App code | Compiled, linted, and JVM-tested; device acceptance pending | Canonical player/UI/system consumers and direct actions, Home continuation/Last.fm projections, Quick Settings tile, native-gapless/bounded-crossfade transitions, Room library/canonical-track/download/lyrics/Crew-checkpoint/Last.fm-outbox/saved-provider persistence, SAF/reconciliation, WorkManager transfer pipeline, Library projections/playable details/onboarding/saved-provider routes, Musixmatch-broker/LRCLIB playback lyrics lookup/cache/synced-line presentation, JioSaavn/YouTube/YouTube Music provider Search-to-play plus JioSaavn album/artist/playlist browse/save/download/queue paths, Last.fm secure scrobble foundation, local adapter, Crew active UI/reducer/snapshots/clock/reactions/preparation/invites/sequencing/optimistic reconciliation/control codec/framing/session engine, renewable encrypted rejoin transport, bounded temporary-media transfer foundation, authenticated LAN signaling, direct peer orchestration, WebRTC boundary, and hosted-relay signaling adapter |
| App tests | 433 executed: 432 passed and 1 opt-in live smoke skipped | Resolver, provider, Search, downloads, lyrics, Last.fm, playback, persistence, and Crew policy/runtime coverage |
| External provider shape | Live-inspected | JioSaavn and LRCLIB responses plus real YouTube Music search and playable HTTPS stream resolution, latest 2026-07-27 |
| Relay service | Unit/integration tested | `npm test`: 19/19 passed, including host resume, opaque routing, bounded backpressure, health, and coturn credential minting |
| Static structure | Parsed | Curated app Graphify AST refreshed at 484/484 files, 9,845 nodes, and 966,644 edges; XML parsing and `git diff --check` used where applicable |
| APK/device | APK assembled, signed, hashed, and Drive-uploaded; physical device pending | Owner handoff stage |
