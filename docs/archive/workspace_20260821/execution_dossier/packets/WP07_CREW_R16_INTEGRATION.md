# WP07 — Crew R16 Integration

**Priority:** P1/P2 depending beta exposure  
**Primary owner:** Terra: dedicated Crew owner  
**Dependencies:** WP00  
**Parallel safety:** One Terra owns this packet. Luna tasks may split protocol identity, local bridge, and tests only after interfaces are frozen.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C06_INTEGRATIONS.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Preserve the mature Crew transport/session/election/Push & Pull infrastructure while replacing legacy music/playback identity with portable R16 contracts and one local PlaybackCoordinator.

## Current repository state

Legacy Crew is extensive and tested. `PortableRecordingDescriptor` exists in pure core, and migration records legacy Crew checkpoint disposition rather than copying unsafe payloads. The production Crew bridge still speaks legacy queue/playback models and is not integrated into the R16 active runtime.

## Target outcome

Crew commands carry portable Recording/source/occurrence identity; each device resolves its own source; all local playback mutations flow through the R16 command router; Push & Pull remains temporary unless explicitly downloaded; no second local player authority exists.

## Locked packet invariants

- Crew shared state coordinates intent; PlaybackCoordinator remains sole local playback authority.
- Queue occurrence identity is stable and contributor/member attribution survives.
- Private provider locators/credentials never enter portable payload.
- Unresolved occurrences remain visible and do not collapse queue shape.
- Device-local failure does not author group-wide user intent.
- Existing transport/auth/election correctness is preserved.

## Non-goals

Do not rewrite WebRTC/LAN/relay/auth/election merely to rename it. Do not copy local RecordingId as a global ID without portable descriptor/evidence. Do not let Crew uncertainty block ordinary R16 beta if feature gating is honest.

## Current code map

- `app/.../shippy/crew/**` retained infrastructure
- `shippy-core/.../crew/PortableRecordingDescriptor.kt`
- R16 PlaybackCoordinator/router/system command bridge
- source selection/temporary media/managed asset boundaries
- service retention and active runtime composition
- Crew UI/persistence/checkpoints
- Crew tests and physical matrix docs

## Implementation work

### A. Portable identity/protocol mapping

Define versioned R16 Crew payloads for Recording descriptor, source availability, QueueEntry-compatible occurrence ID, origin/contributor, and shared playback intent. Include normalized metadata and safe external/source descriptors; omit private locator/header/credential data. Add compatibility rejection or explicit protocol upgrade behavior.

### B. Command bridge

Replace legacy playback mutation interception/inference with explicit conversion between Crew actions and R16 `PlaybackCommand`. Remote projection is generation-safe and marked so it cannot echo as new local user intent. System controls follow the same path and coordinator rules.

### C. Local source resolution

Each member overlays local/private sources on portable descriptors, resolves through the same source policy, and retains unresolved placeholders. Push & Pull produces a temporary verified asset/source tied to the same Recording/QueueEntry; explicit Download promotes through WP02 pipeline.

### D. Checkpoint/reconnect

Persist only portable session/checkpoint state, membership/term/sequence, queue intent, and safe availability. Reconnect/election/handoff cannot restore legacy DB IDs or private locators. Process death does not create another player.

### E. UI and feature gate

Update Crew queue/current/member attribution to R16 presentation. If the required multi-device/routes matrix is incomplete at beta, expose Crew under an explicit experimental flag and publish limitation; ordinary playback remains stable.

### F. Preserve proven infrastructure

Retain authenticated requests, ordered/idempotent events, coordinator election, relay/LAN/WebRTC transports, drift policy, and bounded Push & Pull where tests prove them. Remove legacy bridge paths only after R16 integration passes.

## Edge cases and failure behavior

- Coordinator disconnect/election during playback.
- Members with different source availability.
- Duplicate recording occurrences/contributors.
- expired provider URLs and temporary media.
- device failure/buffering without group skip.
- protocol version mismatch and stale term/sequence.
- reconnect after process death or route change.

## Performance constraints

Do not broadcast large metadata/assets repeatedly. Availability is summarized/bounded. Drift reconciliation remains low-frequency. Push & Pull ranges, leases, and prefetch are bounded. Local projection does not rebuild full queue unnecessarily.

## Verification

Protocol/core tests, bridge echo/stale-generation tests, source-resolution tests, existing Crew suite regression, multi-runtime integration tests, process-death/reconnect tests, and final physical Q.12 matrix or honest gating evidence.

## Done means

Crew uses portable R16 identity, one local PlaybackCoordinator, and retained proven networking; no legacy playback authority remains; stable or experimental exposure is truthful.

## Escalate only when

Protocol/legal/privacy behavior changes materially; existing transport cannot carry required portable identity safely; or owner must choose whether incomplete Crew physical coverage gates beta exposure.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
