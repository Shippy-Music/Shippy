# WP03 — Identify, Edit, and Deduplicate

**Priority:** P1  
**Primary owner:** Terra: identity product owner  
**Dependencies:** WP00  
**Parallel safety:** Split repository/use-case, UI, and background cleanup only after shared contracts/schema are fixed.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C02_IDENTITY_AND_METADATA.md`, `contracts/C04_DATA_MIGRATION.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Complete the central R16 differentiator: identify poor local/provider metadata, attach multiple exact sources to one Recording, preserve user edits, and reverse mistakes safely.

## Current repository state

Core MatchingPolicy, redirects/unmerge plan, exact-source index, identity decision/rejection/audit tables, metadata observations, user overrides, provenance tables, ingestion, managed assets, and enrichment contracts exist. No complete Identify Track use case, candidate search, editor, merge/unmerge transaction, cleanup log, or user-facing bulk workflow exists.

## Target outcome

A user can select an ugly local file, search existing catalogue/providers/Last.fm metadata, confirm the correct recording, immediately receive canonical metadata/artwork across the app, undo the link, edit canonical presentation, review safe duplicate candidates, merge/unmerge, and run bounded library cleanup.

## Locked packet invariants

- The physical local asset remains exact and recoverable.
- Manual confirmation is durable and reversible.
- User overrides outrank refresh.
- Provider IDs remain SourceReference identity, not Recording ID.
- Metadata-only ambiguity never silently merges.
- Redirects are acyclic and every relationship/asset/history reference resolves safely.

## Non-goals

Do not attempt acoustic fingerprinting on every Library item synchronously. Do not overwrite file tags as the canonical authority. Do not merge live/remix/acoustic/remaster variants from title similarity. Do not create a monolithic identity service if existing core/data boundaries suffice.

## Current code map

- `shippy-core/.../identitymatch/**`, metadata/music models
- `shippy-data/.../ingest`, identity DAO/entities, canonical transactions, recording/source/asset DAOs
- `shippy-sources/.../enrichment`, ingestion, source discovery
- provider observation repository and local media engine/tag boundary
- new R16 identify/editor/cleanup UI and action-sheet entry points
- Library/Now Playing/track actions, settings cleanup entry
- tests for matching, redirects, provenance, migration/backup

## Implementation work

### A. Identity use-case layer

Add explicit use cases/repositories for candidate discovery, preview, confirm link, confirm merge, reject candidate, unlink, unmerge, metadata override set/clear, and identity audit retrieval. Resolve redirects at every read/write boundary. Transactions must migrate all relevant relationships and leave an undo audit.

### B. Progressive candidate discovery

Start with existing canonical/strong-ID/fingerprint candidates. Then query enabled metadata/provider sources progressively using the current evidence (filename/title/artist/release/duration/fingerprint/IDs). Group exact sources already attached. Present candidates with confidence reasons and version/duration warnings. Last.fm contributes metadata/discovery identity but never a playable URL.

### C. Identify Track UI

Entry points: local/track action sheet, Now Playing info, Library row, and Settings cleanup. Header clearly shows the selected asset/current metadata. Candidate list includes artwork, title, artist, release, duration, version, providers, and confidence explanation. Confirmation is explicit; unsafe ambiguity requires user choice. Show success and Undo.

### D. Metadata editor/provenance

Allow field-specific user overrides for title, artist credit, release, artwork, year, explicitness/version where supported. Show source/provenance on demand. Clearing an override recomputes canonical data. Optional tag-writing to local/download containers is a separate best-effort action; DB remains authoritative.

### E. Merge/unmerge

Implement redirect-safe merge transaction: choose survivor, move sources/assets/Library/playlist/history/checkpoint/outbox/lyrics/provenance as specified, retain duplicate playlist occurrences, record audit, rebuild FTS, and prevent cycles. Unmerge uses audit snapshots/decisions and never fabricates lost state.

### F. Bulk cleanup and event-driven enrichment

Classify exact/safe/review/ambiguous. Silent automatic linking only for approved exact evidence. Metadata-only candidates enter review. Schedule unique `enrich-recording:<RecordingId>` work on durable intent; idle maintenance is capped/constrained. Provide cleanup log, bulk approve/reject, progress, cancellation, and resumability.

### G. Optional fingerprinting

Use Chromaprint/AcoustID-compatible fingerprints only where available and resource-safe. Fingerprinting is background, deduplicated, cancellable, battery/storage aware, and not required to play or display a track.

## Edge cases and failure behavior

- Wrong local tags/strong-ID conflicts.
- Same title but live/remix/acoustic/clean/explicit difference.
- Multiple files with same bytes and same Recording.
- One asset moved/renamed during identification.
- Undo after playlist/history/download changes.
- Merge candidates concurrently enriched.
- Provider candidate disappears.
- User override conflicts with refreshed provider metadata.

## Performance constraints

Candidate retrieval is bounded/indexed. Provider calls use limited concurrency and cancellation. Fingerprinting never blocks UI/playback. Bulk cleanup pages work and persists checkpoints. Merge transactions touch only affected rows and rebuild derived indexes narrowly.

## Verification

Pure matching/version tests, Room transaction/redirect/unmerge tests, provenance/override tests, candidate search cancellation tests, UI state tests, backup/migration round trip, and manual journeys IDN-001–IDN-012. Verify wrong-version non-merge explicitly.

## Done means

The ugly local-file journey works end to end; exact multi-provider sources point to one Recording; user decisions/overrides survive restart and backup; mistakes are undoable; bulk cleanup is conservative and resumable.

## Escalate only when

A proposed automatic threshold would silently merge ambiguous recordings, fingerprint service/legal policy changes, or unmerge cannot preserve a durable relationship without an owner decision.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
