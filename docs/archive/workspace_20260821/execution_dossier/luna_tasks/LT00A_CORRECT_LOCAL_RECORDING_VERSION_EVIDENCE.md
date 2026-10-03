# LT00A — Correct local recording-version evidence

**Parent packet:** `WP00`  
**Execution wave:** 0  
**Dependencies:** None  
**Luna ownership:** Musikr local observation/version extraction and its focused tests.  
**Terra-owned/shared seams:** Do not edit shared DB schema, MatchingPolicy thresholds, or playback files without Terra.

## Assignment

Local observations emit safe, consistent RecordingVersion evidence from explicit qualifiers/trusted tags, while plain lexical title content remains an original/unknown-normal recording.

## Why this task exists in the current worktree

`MusikrLocalMediaEngine` currently passes the complete visible song title to `RecordingVersionParser`. The parser is intended for explicit version labels/hints, so lexical titles such as “Live Forever” can become LIVE and ordinary unqualified titles can become OTHER. The same mistake appears in both snapshot fingerprinting and observation creation, making the error durable and identity-relevant.

## Locked decisions

- Do not change provider version semantics.
- An unqualified normal local track must not become OTHER.
- Explicit Live/Remix/Acoustic/Remaster/etc. qualifiers remain detectable.
- Snapshot fingerprints and persisted observations must derive the same version evidence.
- No fuzzy title surgery may erase the user-visible title.

## Explicit non-goals

- Do not implement Identify Track.
- Do not change auto-link thresholds.
- Do not add a generic metadata framework.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/source/MusikrLocalMediaEngine.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/r16/source/MusikrLocalMediaEngineTest.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/music/MusicModels.kt`
- `shippy-core/src/main/kotlin/app/shippy/core/identitymatch/IdentityModels.kt`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define the adapter boundary

Add one local-only helper that extracts version hints from explicit parenthetical/suffix qualifiers and trusted tag fields available from Musikr. It may call `RecordingVersionParser`, but must not feed the whole title as an unconstrained label.

### 2. Apply consistently

Use the helper in every local snapshot/observation/fingerprint path. Keep the canonical display title unchanged; only version evidence changes.

### 3. Define safe defaults

For no explicit evidence, use ORIGINAL or UNKNOWN according to existing core semantics, never OTHER merely because a title is nonblank. Keep multiple recognized traits if explicitly present.

### 4. Protect false positives

Token/qualifier parsing must distinguish a title word from a version qualifier. “Live Forever”, “Acoustic Love”, and an artist named “Remix” are not version evidence unless tag/qualifier structure says so.

## Edge cases that must be handled

- blank/malformed tags
- parenthetical and bracket qualifiers
- suffixes after dash
- multiple traits
- Unicode punctuation
- snapshot and observation paths receiving different tag availability

## Verification contract

- Extend `MusikrLocalMediaEngineTest` with plain-title, lexical-false-positive, explicit-version, multi-trait, and consistency cases.
- Run focused app unit tests plus `:shippy-core:check` if core parser code changes.
- No schema migration is expected.

## Completion contract

Every local observation/fingerprint has deterministic, semantically safe version evidence; the known lexical false positives are locked by tests.

## Return to Terra instead of improvising when

- Musikr exposes no trustworthy qualifier/tag boundary and solving it requires a core product decision.
- A proposed change would alter provider observations or matching thresholds.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
