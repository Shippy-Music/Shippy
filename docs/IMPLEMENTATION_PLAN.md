# Shippy Implementation Plan

This is the executable delivery map for `PRODUCT_SPEC.md`. It deliberately uses
vertical stages: each stage establishes a real end-to-end path and protects the
next stage from unstable foundations. The final target remains the complete app.

## Working Rules

- Read `PRODUCT_SPEC.md`, `UX.md`, `CREW.md`, and `STATUS.md` before resuming.
- Update `STATUS.md` after every meaningful implementation or discovered block.
- Reuse Auxio behavior before replacing it.
- Port Bloomee behavior behind Kotlin contracts; do not transplant Flutter UI.
- Keep one playback state and one queue authority.
- Add tests beside new domain/protocol behavior.
- Avoid repeated full Gradle/Android builds on this machine.
- Never label a placeholder as implemented.
- Do not expand scope beyond the canonical documents without updating them.
- Engineer exactly enough for the locked requirements.
- Use a top-down shipping sequence: complete the broad visible Shippy workflow
  across UI, player, library/providers, and Crew before deepening an already
  adequate subsystem. Refinement follows real integration and device evidence;
  it is neither skipped nor performed speculatively ahead of disconnected
  product paths.
- Complete functional behavior and product polish first. Accessibility is the
  dedicated release-hardening pass and does not block earlier stages.

## Stage 0 — Foundation And Evidence

- [x] Record exact Auxio remote, branch, and commit.
  - Validation: clean baseline and `docs/AUXIO_FOUNDATION_AUDIT.md`.
- [x] Record exact Bloomee remote, branch, and commit.
  - Validation: `docs/BLOOMEE_DONOR_AUDIT.md`.
- [x] Map Auxio playback, local library, queue, persistence, navigation, settings,
      MediaSession, and widgets.
- [x] Map Bloomee provider resolution, download, lyrics, Last.fm, equalizer,
      import/share, and failure behavior.
- [x] Establish Shippy branch/package/name migration policy without performing a
      broad mechanical rename prematurely.
- [x] Add canonical documents and continuation instructions.
- [x] Select low-cost syntax/static test commands.

**Exit:** The real codebase map supports a written architecture and every
subsequent task names an actual module.

## Stage 1 — Shippy Shell And Existing Local Playback

- [x] Introduce Shippy product identity while retaining Auxio's real native
      theme/style tokens as the locked visual foundation.
- [x] Implement four-destination shell: Home, Search, Library, Crew.
- [x] Preserve destination state across navigation.
- [x] Attach persistent mini-player above navigation.
- [x] Preserve Auxio's expand/collapse Now Playing and predictive/back hierarchy.
- [x] Rebuild player action hierarchy without duplicate save/download actions.
- [x] Place lyrics below the main player content.
- [x] Move technical/provider actions to Song Information.
- [ ] Establish reusable track rows, collection rows, controls, dialogs, sheets,
      and empty/error/loading states.
- [x] Preserve real Auxio local playback through the entire shell.

**Validation:**

- Focused navigation/presentation tests
- Static Kotlin/Android resource checks
- Later device checklist: local song -> mini-player -> full player -> Back

**Exit:** Shippy looks and behaves like Shippy while playing a real local file
through Auxio’s existing playback stack.

## Stage 2 — Library, Storage, And Queue

- [x] Model permanent Liked, Downloads, and Local collections.
- [x] Prevent rename/delete for permanent collections.
- [x] Preserve user playlist creation, edit, pin, sort, and deletion.
  - [x] Create Room-backed Shippy playlists from Library while retaining device
        playlist import as a separate action.
  - [x] Reorder resolved tracks in user playlists through long-press drag while
        retaining unresolved track slots and rejecting system collections.
  - [x] Reorder Shippy user playlists through long-press drag within pinned or
        unpinned groups, persisting only a complete current order.
- [x] Support Local tracks inside user playlists and queue.
  - Relationship-backed collection playback now creates a duplicate-safe
    canonical queue in visible playlist order, selects the exact tapped
    occurrence, resolves local candidates through the same player authority,
    and aborts without mutating playback if preparation fails.
- [x] Add selected Local source folder management and rescan behavior.
  - Retained Auxio's persisted SAF source/excluded-location selection,
    `LocationsDialog`, indexing listener, and forced rescan path.
- [x] Add selected Download destination with persisted Android access.
- [x] Index pre-existing supported media in the Download destination in every
      Local location mode.
  - [x] Add the selected destination to Auxio's recursive SAF source query
        without filename adoption or removal of manual sources.
  - [x] In MediaStore mode, append configured SAF sources through one ordered
        filesystem view while exact Musikr paths remain duplicate-free.
- [x] Add download relationship/state without isolating tracks from normal views.
- [x] Make queue the canonical playback order for UI, MediaSession, and later
      Crew.
- [x] Implement reorder/remove/play-next/add/replace-context behavior.
- [x] Persist useful offline queue/context.
  - Room v8 stores canonical durable queue intent, duplicate-safe item IDs,
    shuffle order, selected occurrence, position, and repeat mode. Restore
    resolves fresh playable candidates, skips unavailable items deterministically,
    and keeps Auxio's local checkpoint as a compatibility fallback.

**Validation:**

- Repository/domain tests for system collections
- Folder grant/index/reconciliation unit tests with fakes
- Queue operation and persistence tests
- Later device checklist for SAF permissions and revoked folders

**Exit:** Library and queue match the product contract with Local kept distinct.

## Stage 3 — Provider And Download Platform

- [x] Define Shippy Kotlin provider interface and capability/health reporting.
- [x] Implement canonical track, provider candidate, playback candidate, and
      recording-version safeguards.
- [x] Implement preferred/fallback provider settings.
  - Settings exposes enabled providers, swap-safe preferred/fallback priority,
    and user-triggered bounded metadata-only health refresh.
- [x] Port or reimplement the first viable provider adapter from Bloomee evidence.
- [x] Add additional viable provider adapters.
  - Anonymous YouTube Music search/direct-stream adapter is implemented with
    runtime bootstrap and an explicit cipher-only limitation.
- [x] Implement provider playback URL resolution with expiry/cache behavior.
- [x] Implement Shippy permanent download jobs, progress, retry, cancel, remove,
      and destination reconciliation.
- [x] Implement resolution order:
      Crew cache -> download -> preferred -> fallback -> Crew peer.
- [x] Add partial provider failure UI without failing the entire search.
- [x] Add unified provider search and clearly labelled On this device results.
- [x] Add typed JioSaavn album, artist, and playlist results with internal
      detail, direct track playback, collection playback, and shuffle through
      the canonical player.

**Validation:**

- Provider contract tests using recorded/non-secret fixtures
- Candidate identity tests for remix/live/version mismatch
- Download state-machine tests
- Resolver-order tests
- No credentials or user secrets committed

**Exit:** Search -> play -> queue -> download -> disconnect -> play works at the
code/test level, ready for later device verification.

## Stage 4 — Player Integrations And Android Polish

- [x] Lyrics retrieval, synchronization, caching, and offline use.
- [x] Last.fm authentication boundary and scrobbling.
  - [x] Signed API client, encrypted credential repository, durable FIFO
        outbox, listen policy, and playback lifecycle observer.
  - [x] Signed token/browser/session web-auth protocol with runtime-supplied
        application credentials and no embedded reusable secret.
  - [x] Settings credential entry, browser authorization, manual reconnect, and
        sign-out with pending application credentials kept memory-only.
  - [x] Surface an invalid-session delivery result as a visible reauth state.
- [x] Retain Auxio's system equalizer session/panel and existing audio options.
- [ ] Complete gapless/crossfade/normalization behavior according to actual
      Media3 support.
  - [x] Retain Auxio's configurable ReplayGain processor and pre-amp.
  - [ ] Add and device-verify crossfade; verify gapless transitions on the final
        provider/local queue path.
- [x] Sleep timer utility.
- [x] Original-link and Shippy-deep-link sharing.
- [x] MediaSession commands remain consistent with Shippy queue/player.
- [x] Retain Auxio's MediaSession notification, lock-screen, headset, Bluetooth,
      audio-focus, and becoming-noisy paths on the canonical Shippy player.
- [x] Retain Auxio's responsive home-screen widgets on the same canonical
      playback state and command receiver.
- [x] Retain Auxio's Android Auto media-browser service and Local browse/search
      tree while routing playback and queue controls through Shippy's canonical
      MediaSession/player authority.

**Validation:**

- Focused integration and state tests
- Manifest/component inspection
- Later physical-device matrix

**Exit:** Core playback behaves as a premium Android media application.

## Stage 5 — Crew Domain And Control Plane

- [x] Implement protocol-versioned Crew domain module.
- [x] Implement canonical state, ordered/idempotent events, snapshots, and gaps.
- [x] Implement authenticated equal-member request sequencing with bounded
      duplicate replay and requester/publisher separation.
- [x] Implement equal member control and optimistic reconciliation.
- [x] Route authenticated requests/events/rejections/snapshots through one
      persistent active-session engine with ordered per-peer output.
- [x] Implement coordinator terms, election, handoff, and stale-message rejection.
- [x] Implement queue replacement from any member’s song/playlist selection.
- [x] Implement availability summaries per member and queue item.
  - Transient path-free announcements are checkpoint-bound, authenticated
    through coordinator fan-out, bounded to the active queue, and never
    persisted as durable Crew state.
- [x] Implement monotonic session clock probes and scheduled playback decisions.
- [x] Implement readiness, buffering, late join, and drift decision logic.
- [x] Complete membership lifecycle:
  - [x] Authenticated coordinator admission and canonical post-join snapshot.
  - [x] Self-leave and deterministic graceful coordinator handoff.
  - [x] Monotonic reconnect grace and strict-majority election eligibility.
  - [x] Sequence expired-member removal and independently authenticated
        ungraceful election votes.
  - [x] Publish an ordered terminal session event before explicit owner
        teardown and clear recovery state on every converged member.
- [x] Implement bounded persistent active-session checkpoint format, Room store,
      encrypted rejoin credential lease, and restore orchestration seam.
- [x] Implement ephemeral reactions.
  - Bounded lossy codec, authenticated coordinator fan-out, active-runtime
    gateway, compact Now Playing picker, and floating presentation are connected.

**Validation:**

- Pure deterministic JVM tests; no network required
- Simulated concurrent events, loss, reorder, duplicates, and coordinator failure

**Exit:** Several simulated members converge correctly under failure.

## Stage 6 — Crew LAN And Remote Connectivity

- [x] Implement QR/deep-link invitation encoding and validation.
- [x] Implement LAN advertisement/discovery compatible with Android constraints.
- [x] Select and code-spike encrypted direct transport.
- [x] Implement authenticated encrypted LAN signaling for SDP/trickle ICE.
  - Handshake v2 also carries a bounded display-name claim bound into the QR
    secret HMAC transcript; membership still trusts only the later
    fingerprint-bound member ID.
- [x] Orchestrate direct offer/answer, ICE generations, and fingerprint-bound
      peer authentication behind the signaling boundary.
- [x] Implement remote signaling and direct P2P candidate negotiation.
  - A relay-bearing invite races LAN and hosted WebSocket signaling, then uses
    the existing fingerprint-authenticated WebRTC driver with public STUN and
    optional short-lived coturn REST credentials from the configured relay.
- [x] Implement reconnection and network-change handling.
  - A joined member retains one session engine, re-races LAN/hosted signaling
    only after coordinator transport detachment, reauthenticates a replacement
    WebRTC peer, requests a fresh snapshot, and wakes bounded backoff on Android
    default-network changes. The current short-lived invite remains the honest
    retry lifetime; renewable session credentials stay later hardening.
- [x] Separate durable control, transient reaction, clock, and media channels.
- [x] Implement bounded durable-control serialization and multi-frame
      reassembly for requests, events, rejections, and snapshots.
- [x] Connect authenticated peer transports to the control/session engine with
      bounded backpressure and snapshot-gap recovery.
- [x] Add a bounded pre-engine join bootstrap that waits through the admission
      event and accepts only the authenticated coordinator's normal snapshot
      containing the exact local member.
- [x] Expose LAN start/join, QR render/scan, pasted-link fallback, and clear
      connection states in UI.
- [x] Expose equivalent remote/relay connection states when those paths exist.

**Validation:**

- Protocol/serialization compatibility tests
- Local loopback and multi-process tests where cheap
- Later two-phone LAN and remote test matrix

**Exit:** Two real clients have an implementation path for LAN and remote direct
Crew with relay fallback hooks.

## Stage 7 — Push & Pull Media Plane

- [x] Implement one active-Crew-scoped setting.
  - The persisted default-off preference exposes live changes to the future
    active runtime; media policy still scopes acceptance to its exact session.
- [x] Implement supplier availability without exposing library contents.
- [x] Implement media manifest, chunking, integrity, encryption, and backpressure.
  - [x] Bounded encrypted-channel wire frames, exact transfer identity,
        SHA-256 manifest/chunks, receiver reservation, and explicit-resume
        controller foundation.
  - [x] Route MEDIA frames from the session engine through an authenticated
        peer lifecycle boundary without adding another transport collector.
  - [x] Add the authenticated per-peer router for bounded request, manifest,
        chunk, acknowledgement, retry, reject, cancel, and completion callbacks.
  - [x] Bind every transfer to the exact duplicate-safe QueueItem ID and expose
        authenticated outbound request/cancel operations.
  - [x] Publish verified completed media into an active-session-only local
        candidate overlay without mutating canonical Crew state.
  - [x] Connect the controller to authenticated peer lifecycle and end-to-end
        runtime request, supplier, index, and playback callbacks.
- [x] Implement adaptive supplier selection independent of coordinator.
- [x] Implement bounded temporary Crew cache and crash/session cleanup.
- [x] Allow exact Local temporary chunks to redistribute through the coordinator
      within the same active Crew.
- [x] Implement adaptive rolling prefetch, prioritization, and supplier failover.
  - [x] Bind the exact Local current item plus next two items to live queue
        changes with cancellation and one bounded retry timer.
- [x] Bridge verified peer media into Media3 playback without a permanent
      library record.
- [x] Implement explicit Download from temporary media.
  - Exact verified Crew media is atomically staged in app-private storage before
    the existing durable WorkManager/SAF pipeline owns it.
- [x] Implement prompt when required peer media is blocked by the toggle.

**Validation:**

- Chunk/integrity/encryption tests
- Cache retention/cleanup tests
- Supplier loss/failover tests
- Slow network and backpressure simulations
- Non-coordinator local supplier scenario

**Exit:** A unique local track from any member can be prefetched and played
temporarily by the active Crew.

## Stage 8 — Hosted Relay

- [x] Define the smallest self-hostable relay responsibilities.
  - The first deployment is a signaling-only, opaque, in-memory WebSocket
    rendezvous. TURN, ordered control fan-out, media fan-out, and witness leases
    stay separate required layers.
- [ ] Implement authenticated signaling/control fan-out.
  - [x] Implement bounded opaque host/join signaling routes with endpoint-owned
        end-to-end encryption/authentication and explicit route closure.
- [ ] Implement optional media fan-out with bounded memory and no permanent media.
- [x] Add configuration and health check in Shippy settings.
  - [x] Add validated native edit/replace/clear configuration for the exact HTTPS
        relay endpoint.
  - [x] Add bounded live health/registration feedback.
    - [x] Surface per-host registration success/fallback truth in the active
          Crew presentation.
- [ ] Support relay-assisted reconnection and larger groups.
  - [x] Retrieve and refresh bounded short-lived coturn REST credentials for
        direct WebRTC attempts without sending the invitation bearer secret.
- [x] Package documented local/container deployment without unrelated services.

**Validation:**

- Server unit/integration tests
- Multi-client simulated session
- Media expiry/no-retention checks
- Later remote device test through relay

**Exit:** Configured relay reliably supports sessions that cannot or should not
use a direct full mesh.

## Stage 9 — Accessibility And Release Completion

Begin only after Stages 1–8 are functionally complete and polished.

- [ ] TalkBack and focus-order pass on every surface.
- [ ] Large-text, contrast, dynamic-color, and reduced-motion checks.
- [ ] Empty/loading/partial/error/retry states.
- [ ] Queue and Crew accessibility announcements.
- [ ] Performance inspection for startup, lists, artwork, player transition, and
      Crew event churn.
- [ ] Remove placeholders, dead donor experiments, duplicated actions, and stale
      documentation.
- [ ] Add exact user device build/test instructions.
- [ ] Create full physical-device acceptance checklist.
- [ ] Record all unverified behavior honestly.

**Exit:** Code and non-build verification are complete; owner can perform the
final build and physical-phone acceptance run.

## Final Handoff Definition

The implementation handoff includes:

- Exact branch/commit and clean change summary
- Canonical docs matching code
- Commands actually run and their outcomes
- Tests grouped as syntax, unit, integration, simulation, and not run
- No claim of a successful APK/device run unless the owner performs it
- Setup/build/install instructions
- Physical test matrix covering local, providers, downloads, background player,
  two/three-member LAN Crew, remote P2P, relay, Push & Pull, and accessibility
