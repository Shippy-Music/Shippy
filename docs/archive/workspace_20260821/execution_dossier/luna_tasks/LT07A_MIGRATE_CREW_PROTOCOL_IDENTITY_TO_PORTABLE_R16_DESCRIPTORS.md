# LT07A — Migrate Crew protocol identity to portable R16 descriptors

**Parent packet:** `WP07`  
**Execution wave:** 3  
**Dependencies:** `LT00B`, `LT03A`  
**Luna ownership:** Crew wire/domain identity adapters and protocol tests, not playback bridge.  
**Terra-owned/shared seams:** Terra owns protocol version/compatibility decision and shared core model changes.

## Assignment

Crew control/wire payloads carry portable recording/version/source hints and QueueEntry occurrence identity without private paths/tokens; compatibility is versioned and tested.

## Why this task exists in the current worktree

Crew has a large deterministic/network foundation, and `PortableRecordingDescriptor` now exists in pure core. Much legacy Crew state/wire code still carries old QueueItem/Track/source assumptions and private locators that must not become canonical cross-device identity.

## Locked decisions

- Crew shares public identity intent, not private locators/credentials.
- Queue occurrence identity remains explicit.
- Different versions remain distinct.
- Protocol decode is bounded/validated/versioned.
- Legacy peers either interoperate through an explicit adapter or fail honestly.

## Explicit non-goals

- Do not change networking topology.
- Do not rewrite deterministic reducer/session/election code.
- Do not integrate playback yet.

## Start with these repository surfaces

- `shippy-core/src/main/kotlin/app/shippy/core/crew/PortableRecordingDescriptor.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/core/`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/protocol/CrewControlProtocol.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/media/CrewMediaModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/media/CrewMediaWireCodec.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/invite/CrewInviteCodec.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/crew/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Map portable identity

Define exact public fields from master: title/artist/duration/version/external IDs/source hints/original provider item where safe plus queue occurrence. Exclude local paths, stream URLs, auth headers/tokens.

### 2. Version protocol

Add explicit schema/protocol version and bounded decode/validation. Decide via Terra whether one-way legacy compatibility is retained during beta.

### 3. Adapt reducer/events

Crew canonical state should carry portable descriptors and occurrence IDs while preserving deterministic sequencing/election/rejoin semantics.

### 4. Update codecs/tests

Round-trip, malformed, size limits, privacy assertions, old/new compatibility behavior, duplicate occurrences and version distinction.

## Edge cases that must be handled

- local-only recording
- provider ID unknown on peer
- same Recording multiple occurrences
- malformed/oversized payload
- mixed-version peers
- private URL accidentally present

## Verification contract

- Protocol codec/reducer deterministic tests.
- Privacy field allowlist test.
- Compatibility fixture tests.
- No playback/network behavior change in this task.

## Completion contract

Crew public state and wire format can represent R16 canonical intent safely and deterministically without private source leakage.

## Return to Terra instead of improvising when

- Protocol compatibility policy changes product exposure or requires owner choice.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
