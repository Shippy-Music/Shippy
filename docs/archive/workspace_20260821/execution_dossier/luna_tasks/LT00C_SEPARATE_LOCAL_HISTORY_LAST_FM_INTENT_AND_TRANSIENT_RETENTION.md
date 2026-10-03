# LT00C — Separate local history, Last.fm intent, and transient retention

**Parent packet:** `WP00`  
**Execution wave:** 0  
**Dependencies:** None  
**Luna ownership:** Listening/history repositories, catalogue maintenance, and their tests.  
**Terra-owned/shared seams:** Terra owns any Room schema migration, runtime credential-policy wiring, and Home integration.

## Assignment

Every finalized eligible session writes local history; Last.fm work is created only for the captured account/scrobble intent; history remains displayable after eligible transient Recording GC.

## Why this task exists in the current worktree

R16 correctly finalizes canonical listening sessions and persists history/outbox state, but the data repository can enqueue Last.fm work without session-level connected/enabled intent. This can create retroactive scrobbles after a user connects. Separately, catalogue GC excludes every Recording referenced by play history, making a one-off streamed track effectively immortal despite the 60-day transient policy.

## Locked decisions

- Last.fm is invisible and produces no delivery intent while disconnected.
- Local history never depends on Last.fm.
- A session keeps the Recording/QueueEntry/source identity captured at start.
- No retroactive scrobbling of disconnected sessions.
- History display survives catalogue GC without preserving every transient Recording forever.
- Durable relationships/assets/checkpoints/current queue/pending work remain GC vetoes.

## Explicit non-goals

- Do not build Last.fm recommendations here.
- Do not change the 365-day/60-day defaults.
- Do not delete or rewrite user history to simplify foreign keys.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/R16ListeningSessionDelivery.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/PlaybackListening.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/listening/R16ListeningSessionRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/lastfm/R16LastFmOutboxRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/HistoryDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/CatalogueMaintenanceDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/entity/PlaybackHistoryEntities.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/maintenance/R16CatalogueMaintenance.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/listening/R16ListeningSessionRepositoryTest.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/maintenance/R16CatalogueMaintenanceTest.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define session delivery intent

Add a small app/runtime-owned Last.fm delivery policy captured with the session/account generation. Pass it into the data sink; do not let `:shippy-data` read credentials/preferences directly.

### 2. Split persistence actions

Finalize local history independently. Create deterministic outbox identity only when the captured policy authorizes it and canonical metadata is usable. Preserve idempotency across repeated completion delivery.

### 3. Make history self-presenting

Persist an immutable display snapshot sufficient for title/artist/release/artwork/duration fallback. If schema already carries these fields, use them; otherwise Terra performs an additive migration and backup codec update.

### 4. Refine GC eligibility

Allow expired transient Recording rows to be collectable when history is the sole reference and history has a safe snapshot. Recheck in a transaction and preserve redirect/audit/durable reference rules.

### 5. Clarify account transitions

Pending rows already authorized for an account remain account-bound; new sessions after disconnect produce no rows. Account change cannot deliver another account’s outbox.

## Edge cases that must be handled

- connect after months disconnected
- disconnect mid-session
- account switch
- duplicate finalization
- missing canonical artist/title
- GC racing with like/playlist/download
- history row pointing through redirect
- clear history

## Verification contract

- Listening repository tests cover disconnected/connected/account generation/idempotency.
- Maintenance tests show history-only transient GC while history display survives.
- Migration/backup round trip if entity changes.
- Focused app listening-delivery tests compile with the new policy boundary.

## Completion contract

Local history and Last.fm intent are independent, account-safe, and idempotent; transient one-off catalogue rows can expire without destroying history presentation.

## Return to Terra instead of improvising when

- Owner semantics for already-authorized pending scrobbles on sign-out must change.
- History snapshot migration would require discarding information.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
