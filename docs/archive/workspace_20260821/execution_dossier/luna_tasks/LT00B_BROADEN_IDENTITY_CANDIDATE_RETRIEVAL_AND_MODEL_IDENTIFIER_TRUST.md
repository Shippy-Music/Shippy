# LT00B — Broaden identity candidate retrieval and model identifier trust

**Parent packet:** `WP00`  
**Execution wave:** 0  
**Dependencies:** None  
**Luna ownership:** Identity candidate queries, ingestion evidence mapping, and focused data/core tests.  
**Terra-owned/shared seams:** Terra owns schema version/migrations and shared core threshold changes.

## Assignment

Candidate discovery becomes indexed, bounded, and broad enough for the existing conservative matcher; external-ID evidence distinguishes authoritative/user-confirmed/provider/local-untrusted provenance without weakening vetoes.

## Why this task exists in the current worktree

The conservative matcher already understands normalized title/artist/duration/version/external-ID evidence, but `RoomR16IngestionRepository.identityCandidates()` begins metadata discovery with an exact case-insensitive canonical-title DAO query. Valid equivalents such as “See You Again” and “See You Again (feat. Charlie Puth)” may never reach the matcher. External identifiers also store a crude `verified` flag that currently treats local tags as verified and provider IDs as unverified, which is not an adequate provenance/trust model.

## Locked decisions

- Retrieval can be permissive; linking remains conservative.
- Conflicting strong identifiers veto automatic linking.
- Negative identity decisions remain respected.
- Candidate count is capped and query-plan friendly.
- No `%query%` table scan.
- Provider/source identity is never treated as Recording identity.

## Explicit non-goals

- Do not lower match thresholds.
- Do not auto-merge metadata-only ambiguous versions.
- Do not build the full Identify UI.

## Start with these repository surfaces

- `shippy-data/src/main/kotlin/app/shippy/data/ingest/RoomR16IngestionRepository.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/dao/RecordingDao.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/fts/RecordingFts.kt`
- `shippy-data/src/main/kotlin/app/shippy/data/db/entity/RecordingEntities.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/identitymatch/MatchingPolicy.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/identitymatch/IdentityModels.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/ingest/RoomR16IngestionRepositoryTest.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/performance/R16PerformanceQueryPlanTest.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Add a candidate-retrieval query

Prefer a bounded FTS/normalized-token query using title tokens and optionally artist tokens, followed by a broad duration band. Keep exact source, exact external ID, and fingerprint candidates first. Return lightweight matching rows only.

### 2. Preserve the matcher as decision authority

Feed the expanded candidates into the existing MatchingPolicy. Do not duplicate scoring in SQL. Preserve explicit rejection filtering and deterministic candidate order/cap.

### 3. Represent ID trust

Use existing observation/source provenance where possible. If a model/API addition is needed, express trust as evidence provenance (authoritative provider/user-confirmed/embedded-untrusted/etc.), not a blanket local/provider boolean. Terra must own a schema migration if persistence changes.

### 4. Audit conflict behavior

Strong agreeing trusted IDs can dominate; trusted conflicts veto; untrusted local tags contribute evidence but cannot alone force an automatic link. Record review/audit outcomes instead of silently dropping conflicts.

## Edge cases that must be handled

- featured-credit suffixes
- punctuation/case/Unicode variants
- same title different artist
- studio vs live/remix
- mistagged local MBID/ISRC
- multiple provider observations
- negative decision already stored
- large catalogue query cap

## Verification contract

- Data tests prove title variants reach MatchingPolicy.
- Tests prove live/remix/version conflicts remain unlinked.
- Tests prove trusted ID agreement/conflict and untrusted local tag behavior.
- Query-plan test confirms index/FTS use and bounded result count on 50k fixture.
- Run `:shippy-data:testDebugUnitTest`, focused core tests if evidence model changes, and schema migration/backup tests if persisted shape changes.

## Completion contract

Equivalent metadata variants can be considered without full scans, while conservative auto-link/review/reject semantics and provenance are explicit and tested.

## Return to Terra instead of improvising when

- Safe trust semantics require a new durable field/table beyond Terra’s reserved schema change.
- Existing master thresholds cannot satisfy a discovered provider conflict without product-policy change.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
