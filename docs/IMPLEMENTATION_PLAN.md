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

- [ ] Introduce Shippy product identity and design tokens.
- [x] Implement four-destination shell: Home, Search, Library, Crew.
- [x] Preserve destination state across navigation.
- [x] Attach persistent mini-player above navigation.
- [x] Preserve Auxio's expand/collapse Now Playing and predictive/back hierarchy.
- [ ] Rebuild player action hierarchy without duplicate save/download actions.
- [ ] Place lyrics below the main player content.
- [ ] Move technical/provider actions to Song Information.
- [ ] Establish accessible reusable track rows, collection rows, controls,
      dialogs, sheets, and empty/error/loading states.
- [x] Preserve real Auxio local playback through the entire shell.

**Validation:**

- Focused navigation/presentation tests
- Accessibility semantics checks where available
- Static Kotlin/Android resource checks
- Later device checklist: local song -> mini-player -> full player -> Back

**Exit:** Shippy looks and behaves like Shippy while playing a real local file
through Auxio’s existing playback stack.

## Stage 2 — Library, Storage, And Queue

- [x] Model permanent Liked, Downloads, and Local collections.
- [x] Prevent rename/delete for permanent collections.
- [ ] Preserve user playlist creation, edit, pin, sort, and deletion.
- [ ] Support Local tracks inside user playlists and queue.
- [ ] Add selected Local source folder management and rescan behavior.
- [x] Add selected Download destination with persisted Android access.
- [ ] Index pre-existing supported media in the Download destination.
- [ ] Add download relationship/state without isolating tracks from normal views.
- [x] Make queue the canonical playback order for UI, MediaSession, and later
      Crew.
- [x] Implement reorder/remove/play-next/add/replace-context behavior.
- [ ] Persist useful offline queue/context.

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
- [x] Port or reimplement the first viable provider adapter from Bloomee evidence.
- [ ] Add additional viable provider adapters.
- [x] Implement provider playback URL resolution with expiry/cache behavior.
- [ ] Implement Shippy permanent download jobs, progress, retry, cancel, remove,
      and destination reconciliation.
- [x] Implement resolution order:
      Crew cache -> download -> preferred -> fallback -> Crew peer.
- [x] Add partial provider failure UI without failing the entire search.
- [x] Add unified provider search and clearly labelled On this device results.

**Validation:**

- Provider contract tests using recorded/non-secret fixtures
- Candidate identity tests for remix/live/version mismatch
- Download state-machine tests
- Resolver-order tests
- No credentials or user secrets committed

**Exit:** Search -> play -> queue -> download -> disconnect -> play works at the
code/test level, ready for later device verification.

## Stage 4 — Player Integrations And Android Polish

- [ ] Lyrics retrieval, synchronization, caching, and offline use.
- [ ] Last.fm authentication boundary and scrobbling.
- [ ] Equalizer and audio options.
- [ ] Gapless/crossfade/normalization settings according to actual Media3 support.
- [ ] Sleep timer utility.
- [ ] Original-link and Shippy-deep-link sharing.
- [x] MediaSession commands remain consistent with Shippy queue/player.
- [ ] Notification, lock screen, headset, Bluetooth, and interruptions.
- [ ] Home-screen widgets using the same state/commands.
- [ ] Android Auto after MediaSession stability.

**Validation:**

- Focused integration and state tests
- Manifest/component inspection
- Later physical-device matrix

**Exit:** Core playback behaves as a premium Android media application.

## Stage 5 — Crew Domain And Control Plane

- [ ] Implement protocol-versioned Crew domain module.
- [ ] Implement canonical state, ordered/idempotent events, snapshots, and gaps.
- [ ] Implement equal member control and optimistic reconciliation.
- [ ] Implement coordinator terms, election, handoff, and stale-message rejection.
- [ ] Implement queue replacement from any member’s song/playlist selection.
- [ ] Implement availability summaries per member and queue item.
- [ ] Implement monotonic session clock probes and scheduled playback decisions.
- [ ] Implement readiness, buffering, late join, and drift decision logic.
- [ ] Implement membership lifecycle and persistent active-session checkpoint.
- [ ] Implement ephemeral reactions.

**Validation:**

- Pure deterministic JVM tests; no network required
- Simulated concurrent events, loss, reorder, duplicates, and coordinator failure

**Exit:** Several simulated members converge correctly under failure.

## Stage 6 — Crew LAN And Remote Connectivity

- [ ] Implement QR/deep-link invitation encoding and validation.
- [ ] Implement LAN advertisement/discovery compatible with Android constraints.
- [ ] Select and prove encrypted direct transport.
- [ ] Implement remote signaling and direct P2P candidate negotiation.
- [ ] Implement reconnection and network-change handling.
- [ ] Separate durable control, transient reaction, clock, and media channels.
- [ ] Expose only clear join/connection states in UI.

**Validation:**

- Protocol/serialization compatibility tests
- Local loopback and multi-process tests where cheap
- Later two-phone LAN and remote test matrix

**Exit:** Two real clients have an implementation path for LAN and remote direct
Crew with relay fallback hooks.

## Stage 7 — Push & Pull Media Plane

- [ ] Implement one active-Crew-scoped setting.
- [ ] Implement supplier availability without exposing library contents.
- [ ] Implement media manifest, chunking, integrity, encryption, and backpressure.
- [ ] Implement adaptive supplier selection independent of coordinator.
- [ ] Implement bounded temporary Crew cache and crash/session cleanup.
- [ ] Allow temporary chunks to redistribute within the same active Crew.
- [ ] Implement rolling prefetch, prioritization, cancellation, and reprioritizing.
- [ ] Bridge peer media into Media3 playback without a permanent library record.
- [ ] Implement explicit Download from temporary media.
- [ ] Implement prompt when required peer media is blocked by the toggle.

**Validation:**

- Chunk/integrity/encryption tests
- Cache retention/cleanup tests
- Supplier loss/failover tests
- Slow network and backpressure simulations
- Non-coordinator local supplier scenario

**Exit:** A unique local track from any member can be prefetched and played
temporarily by the active Crew.

## Stage 8 — Hosted Relay

- [ ] Define the smallest self-hostable relay responsibilities.
- [ ] Implement authenticated signaling/control fan-out.
- [ ] Implement optional media fan-out with bounded memory and no permanent media.
- [ ] Add configuration and health check in Shippy settings.
- [ ] Support relay-assisted reconnection and larger groups.
- [ ] Package documented local/container deployment without unrelated services.

**Validation:**

- Server unit/integration tests
- Multi-client simulated session
- Media expiry/no-retention checks
- Later remote device test through relay

**Exit:** Configured relay reliably supports sessions that cannot or should not
use a direct full mesh.

## Stage 9 — Quality Completion

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
