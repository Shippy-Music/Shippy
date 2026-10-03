# LT05A — Finish connection-scoped Last.fm delivery and status

**Parent packet:** `WP05`  
**Execution wave:** 2  
**Dependencies:** `LT00C`  
**Luna ownership:** Last.fm credentials/account policy, outbox worker/coordinator, status model/settings and tests.  
**Terra-owned/shared seams:** Terra owns Home/Now Playing shared UI integration and R16 runtime composition.

## Assignment

Connected sessions send now-playing and durable canonical scrobbles exactly once; disconnected sessions create no work; account transitions are safe; status is visible only when relevant.

## Why this task exists in the current worktree

The repository has canonical listening sessions, durable outbox DAO/repository, client/worker/scheduler foundations and previous R15 tracker code. It needs a complete R16 account-scoped pipeline, truthful accepted/ignored/reauth state, disconnected invisibility and small scrobble status.

## Locked decisions

- Scrobble identity is canonical Recording/session, not provider.
- Eligibility uses monotonic audible time, never position jumps.
- Outbox is FIFO, bounded, idempotent and account-bound.
- Now Playing does not imply completed scrobble.
- Disconnect removes Last.fm UI/network/new work.

## Explicit non-goals

- Do not make Last.fm a provider.
- Do not add recommendations in this task.
- Do not display a full live feed.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/AndroidKeystoreLastFmCredentialRepository.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/LastFmClient.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/R16LastFmOutboxDeliveryCoordinator.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/R16LastFmOutboxWorker.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/R16LastFmOutboxWorkScheduler.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/lastfm/R16LastFmOutboxRepository.kt`
- `app/src/main/java/org/oxycblt/auxio/settings/LastFmSettingsViewModel.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/R16ListeningSessionDelivery.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define account/session policy

Use LT00C captured account generation/intent. Store or route rows so a worker can never deliver across accounts.

### 2. Now Playing

Send once when audible playback starts with usable canonical title/artist; handle retry/reauth without blocking playback.

### 3. Scrobble delivery

Batch up to API limit, parse accepted/ignored and indexed errors, delete/drop/retry/reauth correctly, keep deterministic IDs and backoff.

### 4. Status model

Disconnected, disabled, pending, now-playing sent, eligible/pending, scrobbled, ignored, retrying, reauth. Expose only a subtle icon/status in Now Playing and useful settings diagnostics.

### 5. Account lifecycle

Sign-in/reauth/sign-out/account change schedule/cancel unique work correctly and keep authorized pending semantics documented.

## Edge cases that must be handled

- offline completion
- partial batch acceptance
- invalid session
- account switch with pending rows
- clock change
- duplicate worker
- unknown metadata
- local/download/provider source
- shuffle/reorder duplicates

## Verification contract

- Client parser fixtures.
- Outbox account/idempotency/FIFO tests.
- Worker scheduling/backoff tests.
- Listening shuffle/reorder/seek/pause tests.
- Real account physical validation near beta.

## Completion contract

Last.fm delivery is optional, canonical, account-safe, durable and truthful; disconnected users incur no Last.fm behavior.

## Return to Terra instead of improvising when

- API credentials/terms or pending-row-on-signout semantics require owner/legal decision.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
