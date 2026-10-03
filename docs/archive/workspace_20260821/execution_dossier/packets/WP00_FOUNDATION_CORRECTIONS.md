# WP00 — Foundation Corrections

**Priority:** P0  
**Primary owner:** Terra: identity/playback-data integration  
**Dependencies:** None  
**Parallel safety:** May split into three Luna tasks only after Terra reserves shared DB/browser seams.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C02_IDENTITY_AND_METADATA.md`, `contracts/C03_PLAYBACK.md`, `contracts/C06_INTEGRATIONS.md`

## Mission

Repair known correctness gaps in the shared R16 foundation before later features depend on them. This packet is deliberately narrow: it corrects semantics without redesigning the established core.

## Current repository state

The current pure identity/matching/playback architecture is sound, but six source-level gaps can create wrong recording versions, missed provider equivalence, retroactive Last.fm work, one-item Songs queues, and non-expiring transient catalogue rows.

## Target outcome

Local and provider observations enter conservative matching with correct version/trust evidence; disconnected Last.fm creates no delivery intent; Songs row play preserves its current context; transient retention matches the master contract without losing history presentation.

## Locked packet invariants

- No metadata-only change may weaken version vetoes or auto-merge thresholds.
- Last.fm delivery intent is decided for the listening session, not inferred months later.
- Fixing history retention must not delete visible user history or durable Recording relationships.
- Songs context remains lightweight and paged; no full rich-model hydration.

## Non-goals

Do not implement the full Identify Track UI, release graph, cache, or final authority cutover here. Do not add a generic “identity framework” beyond the concrete corrections.

## Current code map

- `app/.../r16/source/MusikrLocalMediaEngine.kt`
- `shippy-core/.../identitymatch/MatchingPolicy.kt` and identity models
- `shippy-data/.../ingest/RoomR16IngestionRepository.kt`
- `shippy-data/.../db/dao/RecordingDao.kt`, FTS/search DAOs
- `shippy-data/.../listening/R16ListeningSessionRepository.kt`
- `app/.../r16/library/R16LibrarySongsFragment.kt` / ViewModel
- `app/.../r16/browser/R16BrowserQueueResolver.kt` and router/contract
- `shippy-data/.../maintenance/R16CatalogueMaintenance.kt` and DAO
- affected tests and schema migration only if truly required

## Implementation work

### A. Correct local recording-version extraction

Create one local-adapter helper that derives version evidence from explicit qualifiers/trusted tag fields rather than scanning the full title indiscriminately. Use it consistently in snapshot fingerprinting and observation creation. Default an unqualified title to original/unknown-normal, not `OTHER`. Preserve recognizable explicit qualifiers such as parenthetical/suffix “Live”, “Remix”, “Acoustic”, “Remaster”, “Instrumental”, and edition labels. Do not classify lexical title content such as “Live Forever” as a live recording.

Add tests covering plain titles, lexical false positives, parenthetical/suffix versions, multiple traits, and snapshot/observation consistency.

### B. Broaden bounded candidate retrieval

Keep exact source, external-ID, and fingerprint candidates first. Replace exact canonical-title-only metadata retrieval with a bounded normalized/FTS candidate query. Candidate retrieval may use normalized title tokens, artist tokens, and a broad duration band; the existing matching policy still decides link/review/reject. Preserve negative decisions and candidate caps. Do not add `%query%` full scans.

Add tests proving variants reach the matcher while unrelated or conflicting versions remain unlinked.

### C. Represent external-ID provenance/trust

Ensure matching can distinguish a provider-authoritative/conflict-free ID from an arbitrary local tag. Prefer adapting `MatchingFeatures`/observation evidence rather than proliferating tables if existing provenance can carry it. Conflicting strong IDs veto automatic link and create review/audit evidence.

### D. Separate local history from Last.fm intent

Persist play history for every finalized eligible local session, independent of Last.fm. Create a Last.fm outbox row only if the session captured connected/enabled scrobble intent and usable canonical metadata. Do not retroactively scrobble sessions completed while disconnected. Preserve idempotency and deterministic outbox identity.

The app/runtime layer should pass the session-level policy; the data module should not reach into credential storage.

### E. Give Songs row play a real context

Mirror the established playlist/artist/system-collection pattern: build a complete lightweight Songs `PlayContext` from the current canonical sort/filter and exact selected RecordingId. Carry generation-safe extras through the MediaBrowser/command route. A row tap must not turn the entire Songs page into a one-item queue. The context query must return IDs/origins only, not rich artwork rows.

### F. Make transient retention compatible with history

Refactor the retention condition so play history can survive after a transient Recording becomes collectable. Recommended shape: retain immutable display fallback/history metadata in the history row, allow the Recording foreign relation to become nullable or redirect-safe where necessary, and keep Recording rows only while history needs canonical/live linkage under the chosen retention policy. Do not delete durable relationships, manual decisions, assets, checkpoints, pending work, or current queue/cache protections.

If a schema migration is required, serialize it through Terra and add migration/backup tests.

## Edge cases and failure behavior

- Malformed/blank local tags default safely.
- Title qualifier parsing is Unicode/punctuation tolerant but bounded.
- FTS candidate retrieval handles featured-credit suffixes without matching remixes/live versions incorrectly.
- Connecting Last.fm after months does not upload old disconnected sessions.
- A stale Songs search result cannot start a different context.
- GC racing with a new durable relationship rechecks eligibility transactionally.

## Performance constraints

Candidate retrieval stays indexed and capped. Version extraction runs during observation creation without network or full-catalogue work. Songs context uses one lightweight ordered query. GC remains bounded and transactional.

## Verification

Focused host tests for parser, matcher candidate retrieval, external-ID conflict, Last.fm intent, Songs context, and GC/history migration. Run formatting, affected module tests, app compilation, and Room migration/backup tests if schema changes.

## Done means

All six defects are fixed; later packets can rely on correct version/trust/candidate semantics, disconnected Last.fm produces no work, Songs playback has context, and transient recordings can expire without erasing user-visible history.

## Escalate only when

A schema choice would irreversibly discard history; matching thresholds would need product-policy change; or the current provider/local metadata model cannot express safe version/provenance evidence without a broader core change.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
