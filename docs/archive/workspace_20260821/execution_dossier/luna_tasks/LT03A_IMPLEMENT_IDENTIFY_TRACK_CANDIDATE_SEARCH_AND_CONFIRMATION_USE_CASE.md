# LT03A — Implement Identify Track candidate search and confirmation use case

**Parent packet:** `WP03`  
**Execution wave:** 2  
**Dependencies:** `LT00A`, `LT00B`  
**Luna ownership:** Identity application use case/repository and Identify candidate UI/state, excluding merge/bulk cleanup.  
**Terra-owned/shared seams:** Terra owns shared schema/ingestion APIs and navigation/sheet integration.

## Assignment

A user can open Identify Track for a captured Recording/Asset, progressively search bounded sources, inspect evidence/versions, confirm the correct Recording/source relationship, see canonical metadata/artwork update everywhere, and Undo safely.

## Why this task exists in the current worktree

The core and database already contain matching, observations, decisions, redirects and provenance foundations, but users cannot select an ugly local/provider Recording and explicitly identify it against existing catalogue, Last.fm metadata, YouTube Music, JioSaavn or YouTube candidates.

## Locked decisions

- Confirmation links the exact source/asset to canonical Recording; it does not replace/delete audio.
- Manual confirmation outranks automatic matching and is audited/reversible.
- Different versions remain separate.
- Provider lookup does not block existing playback.
- Candidate rows show provenance and uncertainty honestly.

## Explicit non-goals

- Do not implement bulk cleanup.
- Do not auto-confirm probable metadata matches.
- Do not rewrite physical local tags in this task.

## Start with these repository surfaces

- `shippy-core/src/main/kotlin/app/shippy/core/identitymatch/`
- `shippy-core/src/main/kotlin/app/shippy/core/music/MetadataModels.kt`
- `shippy-sources/src/main/kotlin/app/shippy/sources/enrichment/EnrichmentCoordinator.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/ingest/`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/IdentityDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/transaction/CanonicalWriteTransactions.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/search/`
- `app/src/main/java/org/oxycblt/auxio/shippy/provider/`
- `app/src/main/java/org/oxycblt/auxio/shippy/lastfm/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Create Identify request/state

Capture target RecordingId and exact source/asset context. State includes current canonical/raw metadata, query/evidence, progressive local/provider/Last.fm candidate sections, loading/failure, selected candidate, and commit/undo effect.

### 2. Progressive candidate search

Search existing canonical catalogue first, then enabled metadata providers with bounded concurrency/cancellation. Normalize into observations; dedupe candidate Recording/version/source rows. Last.fm contributes metadata/IDs only.

### 3. Confirmation transaction

Persist source link/manual identity decision, negative decisions for explicitly rejected candidates if applicable, metadata observation/provenance recompute, redirect only when the user confirms a true same-version merge, and audit/undo token. Preserve playlists/history/assets through Recording identity rules.

### 4. Immediate projection update

Room invalidation should update Library, player presentation, search, and Last.fm metadata without restarting playback or changing QueueEntryId.

### 5. Undo

Revert the exact decision/redirect/link/provenance changes if still safe; preserve later unrelated edits and emit conflict if Undo is no longer lossless.

## Edge cases that must be handled

- unknown artist/title
- multiple same-title versions
- local file already linked
- provider candidate unavailable
- network partial failure
- user confirms existing Recording vs new candidate
- playback active during confirmation
- undo after later edits

## Verification contract

- Use-case/repository transaction tests.
- Candidate cancellation/generation tests.
- Version conflict and negative-decision tests.
- UI state/effect tests.
- Manual journey IDN-001–IDN-005 later.

## Completion contract

Identify Track is a complete, reversible, canonical operation that cleans presentation everywhere without creating/deleting the audio file.

## Return to Terra instead of improvising when

- A candidate requires an unsafe merge with ambiguous version identity.
- Provider/Last.fm terms prohibit required lookup behavior.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
