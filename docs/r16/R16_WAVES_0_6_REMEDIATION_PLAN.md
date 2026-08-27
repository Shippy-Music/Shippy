# Shippy R16 Waves 0–6 Remediation Plan (v3)
## Evidence-Grounded, Bounded Task Execution

> This plan is based on actual source investigation, not assumptions.
> Every "Current State" section references real code paths found in the repository.
> Approved master remediation backlog for bounded task-by-task execution.

---

## Authority & Milestone

```
Owner Decisions → R16 Master Spec → Repository Reality → This Plan → Implementation Judgment
```

- **Waves 0–6** = Pre-cutover implementation complete / cutover-ready
- **Waves 7–8** (`LT10A`–`LT10C`) = Authority cutover, production retirement, final Hardened Beta

---

## Execution Model

```
Master remediation plan
        ↓
pick ONE LT task
        ↓
make a focused task-specific plan
        ↓
implement only that contract
        ↓
focused tests
        ↓
prove acceptance contract
        ↓
close task
        ↓
next LT task
```

**Rule:** A task is not complete until its acceptance contract is independently verifiable from the source code and test output. "I created a file with the right name" ≠ complete.

### Verification Cadence

```
Per bounded task:    focused tests + affected module compile/test
Per phase:           affected modules + app assembleDebug
Checkpoint:          full multi-module tests + :app:lintDebug + assembleDebug
```

---

## Task Execution Order

| Phase | Tasks | Why This Order |
|:---:|:---|:---|
| **0** | P0: Room Migration Fix | Blocks everything — existing users crash |
| **1** | LT05A Last.fm Wire Fix | Broken feature, straightforward wire fix |
| **2** | LT00B Identity Trust, LT00C Transient GC | Core invariants other features depend on |
| **3** | LT02A Downloads, LT02B Streaming Cache | Fix state machine + complete cache subsystem |
| **4** | LT04A–D Library, Save, Playlists, Queue | Largest feature group, mostly wiring |
| **5** | LT03A Identify Track, LT03B Metadata | Need real implementation, not scaffold |
| **6** | LT06A–B Lyrics | Wire existing coordinator + build UI |
| **7** | LT05B Discovery, LT08A Shell | Product surfaces |
| **8** | LT01A–B Migration | Safety + proof (needs test fixtures) |
| **9** | LT07A–B Crew | Integration with playback authority |
| **10** | LT08B–C, LT09A–B Polish & Hardening | Final pass |

**Checkpoint schedule:**
- After Phases 0–4: **GPT audit checkpoint #1**
- After Phases 5–7: **GPT audit checkpoint #2**
- After Phases 8–10: **GPT final audit → Waves 0–6 certification**

---

## Phase 0: P0 Room Migration Fix

### Current State (verified)

**R16 Database (`ShippyR16Database`):**
- Version = 4 (`ShippyR16Database.kt:154`)
- `MIGRATION_3_4` adds `requested_media_variant` and `destination_identity` to `download_job` (`ShippyR16DatabaseMigrations.kt:211-217`)
- But v4 schema export also expects `account_id` on `lastfm_scrobble_outbox` (with `defaultValue = "''"`)
- The `account_id` column was added to `LastFmScrobbleOutboxEntity` (`IntegrationEntities.kt:101`) but **MIGRATION_3_4 doesn't add it**
- Users upgrading from v3 → v4 will have `lastfm_scrobble_outbox` without `account_id` → Room schema validation crash

**Legacy Database (`ShippyDatabase`):**
- Version = 10 (`ShippyDatabase.kt:52`)
- `LastFmScrobbleEntity` has `accountId` field (`LastFmScrobbleOutbox.kt:31`)
- Only v10 schema export exists — need to verify if `accountId` was present in the previously shipped v10 or was added after

### Work

**R16 Database (`ShippyR16Database`):**

If v4 has been distributed/persisted on any real device:
- Bump `SCHEMA_VERSION = 5`
- Add `MIGRATION_4_5`: `ALTER TABLE lastfm_scrobble_outbox ADD COLUMN account_id TEXT NOT NULL DEFAULT ''`
- Export v5 schema, write migration test (v4 → v5)

If v4 has never existed outside this development line:
- Amend `MIGRATION_3_4` to include the `ALTER TABLE lastfm_scrobble_outbox ADD COLUMN account_id TEXT NOT NULL DEFAULT ''` alongside the existing download_job columns
- Re-export v4 schema to match
- Write migration test (v3 → v4 produces complete final schema)

**Legacy Database (`ShippyDatabase`):**

R15.3 is a real existing app state with real user data. Do not gamble the library on arguments about whether a version was "really shipped."
- Bump `version = 11`
- Add `MIGRATION_10_11`: `ALTER TABLE lastfm_scrobble_outbox ADD COLUMN accountId TEXT NOT NULL DEFAULT ''`
- Write migration test using old v10 schema fixture

### Acceptance Contract
- [ ] Room opens without crash on a database created by the previously shipped schema version
- [ ] R16 migration test: v3 (or v4) → current version produces complete schema including `account_id`
- [ ] Legacy migration test: v10 → v11 produces schema including `accountId`
- [ ] Fresh install works (Room creates table with column)

---

## Phase 1: LT05A — Last.fm Wire Fix

### Current State (verified)

The architecture is conceptually right but the wire is disconnected:

1. `R16ListeningSessionRecord` has `accountId: String? = null` (`R16ListeningSessionRepository.kt:46`)
2. When persisting, it does `accountId = session.accountId ?: ""` (line 107)
3. But `FinalizedListeningSession.toRecord()` **never sets accountId** (`R16ListeningSessionDelivery.kt:148-161`)
4. So all outbox entries get `accountId = ""`
5. Delivery coordinator hashes the current username and queries `oldest(hashedAccountId, limit)` — finds nothing
6. The `FakeOutbox` in tests has `filter { it.accountId == accountId || it.accountId.isEmpty() }` which masks this by matching empty strings
7. SHA-256 hash is computed independently in 3 separate places (`LastFmScrobbleTracker.kt:240,253`, `R16LastFmOutboxDeliveryCoordinator.kt:51`) — no canonical function, risking case/normalization bugs (`Rudra` vs `rudra`)

### Work
- **Create one canonical account-ID function** with defined normalization (e.g. lowercase trim before hashing). All three hash sites must use it. No independent SHA-256 logic anywhere else.
- Capture `scrobbleAuthorized` and non-secret `accountId` hash at **listening session start** (not finalization) — these become immutable properties of that session
- Rationale: if the user logs out of Account A and into Account B mid-track, finalization-time inspection would incorrectly attribute the listen to B
- `R16ListeningSessionDelivery.toRecord()` must pass the session's captured `accountId` through
- Remove the `|| it.accountId.isEmpty()` fallback from `FakeOutbox` in tests — it masks real bugs

### Acceptance Contract
- [ ] Single canonical `LastFmAccountId.hash(username)` function used everywhere
- [ ] Outbox entries carry the account hash captured at session **start**
- [ ] Test: session starts with account A → user switches to B mid-track → outbox entry has hash(A), not hash(B)
- [ ] Test: delivery with account A finds A's entries → delivery with account B does NOT find A's entries
- [ ] Disconnected listening → no outbox entries created (scrobbleAuthorized = false)

---

## Phase 2: LT00B — Identity Trust + LT00C — Transient GC

### LT00B Current State (verified)

- `MatchingFeatures` carries external IDs as flat `Set<String>` — **no verification flag** (`IdentityModels.kt:74-75`)
- `toMatchingFeatures()` collects all ISRC/MBID identifiers from `external_identifier` table without distinguishing provenance (`RoomR16IngestionRepository.kt:443-444`)
- `externalIdVeto()` vetoes when ISRC or MBID sets are non-empty and disjoint (`MatchingPolicy.kt:221-243`)
- So a wrong local ISRC tag (`verified=false`) **will** veto a correct provider match

### LT00B Work
- Add verification status to external IDs: either a `verified: Boolean` per ID in `MatchingFeatures`, or split into `verifiedIsrcs` / `unverifiedIsrcs` sets
- `toMatchingFeatures()` must consult `external_identifier` provenance to determine verification
- `externalIdVeto()`: **contradiction veto requires sufficiently trusted conflicting evidence on both sides.** An unverified identifier may contribute positive evidence when it matches, but may not create a contradiction veto against verified evidence. Both sides must be verified (or user-confirmed) for a veto to fire.
- Only authoritative provider IDs, user-confirmed IDs, and provider-cross-referenced IDs are `verified = true`

### LT00B Acceptance Contract
- [ ] Test: local file with wrong ISRC (unverified) + correct provider candidate → NO veto, match proceeds
- [ ] Test: verified provider ISRC contradicts another verified provider ISRC → veto fires
- [ ] Test: user-confirmed ID is treated as verified and can veto
- [ ] Test: unverified IDs still contribute positive evidence when they intersect

---

### LT00C Current State (verified)

1. `PlayHistoryEntity` has only `recording_id` FK — **no self-presenting snapshot fields** (`PlaybackHistoryEntities.kt:50-61`)
2. The FK has **no `onDelete` action** (defaults to `NO ACTION`) (`PlaybackHistoryEntities.kt:30-34`). With FK enforcement, deleting a recording that has history rows will **fail with a constraint violation**. Without FK enforcement, history becomes an orphan pointing at nothing.
3. `RecordingEntity` has `retention_kind` and `retained_until_epoch_ms` — transient markers exist (`RecordingEntities.kt:40,56`)
4. GC query in `CatalogueMaintenanceDao` correctly checks `retention_kind = 'TRANSIENT'` and `retained_until_epoch_ms <= :expired` (`CatalogueMaintenanceDao.kt:27-30`)
5. **BUT** GC also has `AND NOT EXISTS (SELECT 1 FROM play_history WHERE ...)` (lines 51-54) — meaning ANY track with history is never GC'd
6. This defeats the purpose: one-off streamed tracks that you listened to once become permanent catalogue baggage

### LT00C Work
- Add self-presenting snapshot columns to `PlayHistoryEntity`: `snapshot_title`, `snapshot_artist_display`, `snapshot_artwork_location` (nullable)
- Populate snapshots at listening session persist time from the recording's current metadata
- **Change the FK** on `play_history.recording_id` to `onDelete = SET NULL` (making `recording_id` nullable) — so GC can delete the recording while history survives with its snapshot. Note: changing FK semantics in SQLite likely requires rebuilding the table, not a simple ALTER — migration must be tested against actual old schema.
- **Remove** the `play_history` NOT EXISTS check from the GC query — history is now self-sufficient
- **Protect actively-used transient recordings from GC.** The GC eligibility query must also exclude recordings that are:
  - Currently playing (active playback session)
  - In the current queue (any `QueueEntryId` reference)
  - Subject to active download or cache promotion
  - Pending manual identity work (open Identify Track sheet)
- Room schema migration: add snapshot columns, rebuild `play_history` with new FK behavior
- Backfill existing history rows from current recording metadata in the migration
- **Critical test:** verify that deleting a transient recording does NOT delete or cascade-destroy its history row

> [!IMPORTANT]
> **Schema migration rule (applies to all phases after Phase 0):** Any database schema mutation introduced after Phase 0 must advance the appropriate schema version and include a tested Room migration from the immediately preceding schema. Never mutate a versioned schema in place again. We have already stepped on this rake once.

### LT00C Acceptance Contract
- [ ] History rows contain snapshot fields after migration
- [ ] GC purges an expired transient recording even if it has play history
- [ ] History row **survives** recording deletion (FK = SET NULL, not CASCADE)
- [ ] History UI displays the snapshot title/artist, not a broken FK reference
- [ ] Durable recordings (library/playlist/download/etc.) are never GC'd regardless
- [ ] Actively-used transient recordings (currently playing, in queue, active download) survive GC even when expired

---

## Phase 3: LT02A — Download State Machine + LT02B — Streaming Cache

### LT02A Current State
- `pauseDownload` works correctly — transitions to PAUSED via `updateProgress` (`R16DownloadActionCoordinator.kt:112-134`)
- `resumeDownload` from PAUSED goes through `updateProgress` with QUEUED state — fixed in last session
- Scheduler failure creates controlled FAILED_RETRYABLE state — working
- Test was fixed to match real state machine

### LT02A Work
- Verify the fixed test assertions are correct (done in last session)
- Add edge case tests: resume from FAILED_RETRYABLE, retry from non-retryable states (should fail gracefully)

### LT02B Current State (verified)

**Cache key mismatch:**
- Playback creates cache keys via `MediaObjectKey.from(TrackCandidate)` using mime/container/bitrate to construct the hash (`MediaObjectKey.kt:36-60`)
- Download promotion uses `MediaObjectKey.fromSourceReference(sourceId, mediaVariant="DEFAULT")` — completely different hash input (`MediaObjectKey.kt:77-87`)
- So playback caches under `SHA256(schema + kind + providerId + sourceId + mime/bitrate)` but promotion looks for `SHA256(schema + "source" + sourceRefId + "DEFAULT")` — these never match

**Missing cache lifecycle:**
- `PlaybackCacheManager` already has LRU evictor with device-aware sizing (`PlaybackCacheManager.kt:57-61`): `min(2GB, usableSpace/10)` clamped to `≥100MB`
- Has `clear()` method that removes all cache entries (line 134)
- Has `sizeBytes()` (line 136)
- `ProviderCacheDataSource` already filters: only caches objects with `MediaObjectKey.CACHE_KEY_PREFIX` — local/download/crew URIs bypass cache (line 175)
- Safe `copyCompleteTo()` verifies complete byte coverage before promotion (lines 85-128)
- **Missing:** configurable size budget (currently hardcoded), age-based eviction policy, settings UI for clear-cache, free-space safety monitoring during runtime

### LT02B Work

**Cache key unification:**
- Promotion must construct its `MediaObjectKey` using the same identity that playback used when caching the bytes. Either:
  - a) Promotion passes `TrackCandidate` (or its key fields) instead of just `sourceReferenceId + "DEFAULT"`, or
  - b) Playback also stores the key it used alongside the download job record, so promotion can look it up

**Complete cache lifecycle:**
- Make cache size budget configurable via user settings (with the current device-aware default as fallback)
- Add user-facing age-based cache policy:
  ```
  Remove unused cached music after: 7 days / 30 days / 90 days / Never
  ```
  Size-based LRU eviction remains the hard safety mechanism; age policy is an optional additional hygiene pass
- Add free-space safety monitoring: if device storage drops below a safety floor, aggressively evict oldest cache entries
- **Eviction inhibition:** LRU and age eviction must never remove cache spans currently being consumed by active playback or an in-progress cache-to-download promotion
- Wire `PlaybackCacheManager.clear()` to a "Clear Cache" action in R16 settings (confirm dialog, shows current cache size via `sizeBytes()`)
- Ensure `clear()` never touches permanent downloads or local files (already guaranteed by separate storage directories and `ProviderCacheDataSource` filtering)

### LT02B Acceptance Contract
- [ ] Test: play a track (caches it) → request download → promotion finds the cached bytes → no re-download
- [ ] Test: incomplete cache → promotion falls back to standard download
- [ ] Test: cache size exceeds budget → LRU eviction removes oldest entries
- [ ] Test: age policy set to 30 days → entry older than 30 days evicted on maintenance pass
- [ ] Test: eviction inhibition — entry currently being played is NOT evicted even when over budget
- [ ] Test: clear-cache removes all streaming cache entries, permanent downloads untouched
- [ ] Test: free-space below safety floor → cache eviction triggers
- [ ] Test: pause/resume lifecycle works end-to-end

---

## Phase 4: LT04A–D — Library Features

### LT04A Current State (verified)
- Albums and Genres fragments exist with paging adapters
- **`playSong()` is a no-op** (`R16LibraryAlbumDetailFragment.kt:85-87`): `// Handled via media session or playback spine`

### LT04A Work
- Wire `playSong` to `PlaybackCoordinator` with album/genre context (play from position in album track order)
- Verify navigation to detail fragments works without FragmentManager issues

### LT04B Current State
- Destination picker exists but only appends one song to one playlist
- No Liked state toggle, no duplicate counts, no unlike, no membership removal

### LT04B Work
- Show current Liked state (filled heart) in the Save Destinations sheet
- Show playlist membership with occurrence counts
- Toggle Liked on/off
- Remove from playlist from the sheet

### LT04C Current State
- `addPlaylistEntries`, `removePlaylistEntries`, `movePlaylistEntry` exist in `R16LibraryMutationRepository`
- Single-entry operations work, but no multi-select UI

### LT04C Work
- Wire multi-select to playlist detail for batch delete/move
- Ensure all operations use `PlaylistEntryId`, not `RecordingId`

### LT04D Current State
- Queue screen exists but no reorder/remove/multi-select
- `QueueEntryId` type exists and is used in playback coordinator

### LT04D Work
- Drag-and-drop reorder on `QueueEntryId`
- Non-drag accessibility alternatives (Move Up/Down/Top/Bottom)
- Multi-select removal preserving active playback index

### Phase 4 Acceptance Contract
- [ ] Album detail: tap song → plays from that position in album order
- [ ] Genre detail: tap song → plays from that position in genre
- [ ] Save Destinations: shows Liked state, playlist memberships; can add to multiple playlists and unlike in one session
- [ ] Save Destinations: removing membership from within the sheet takes effect immediately
- [ ] Playlist detail: multi-select delete acts on `PlaylistEntryId` (duplicates preserved)
- [ ] Playlist detail: multi-select move acts on exact `PlaylistEntryId`s (not `RecordingId`)
- [ ] Queue: drag-reorder on `[A₁, B, A₂, C]` — moving `A₂` leaves `A₁` at index 0
- [ ] Queue: Move Up/Down/Top/Bottom operates on exact `QueueEntryId`
- [ ] Queue: multi-select removal removes exact occurrences, not all copies of a recording
- [ ] Queue: currently-playing `QueueEntryId` remains correct after mutations around it

---

## Phase 5: LT03A — Identify Track + LT03B — Metadata Editor

### LT03A Current State (verified)
- `searchIdentifyCandidates` loads first N library songs then **filters in memory** (`R16LibraryMutationRepository.kt:477-495`)
- No FTS search, no provider search, no fingerprint evidence
- `isExact` is just title equality
- **No production call site opens the bottom sheet**
- `SourceDiscoveryRepository.search(query, providerId)` **already exists** for external provider search (`SourceDiscovery.kt:120`) — returns `SourceDiscoverySnapshot` with track results per provider

### LT03A Work

**Progressive candidate search (two stages):**
1. **Local catalogue FTS** — replace in-memory filter with query against `recording_fts`. This handles the common case where the canonical recording already exists locally.
2. **External provider/metadata discovery** — when local FTS returns no strong match (or user requests it), call `SourceDiscoveryRepository.search()` with normalized title/artist query against enabled providers. The terrible `Wiz_Khalifa_See_You_Again_28173.mp3` should find the canonical recording even if it's not locally known.

**Candidate presentation:**
- Show candidates with provenance badges (Local, Provider Name), confidence level (Strong ID match / Title+Artist+Duration / Title only)
- Normalize title/artist/duration evidence for scoring
- Use existing version matching from `MatchingPolicy` to avoid cross-version false positives
- No automatic ambiguous merge — ambiguous candidates require user confirmation

**Confirmation & Undo:**
- Confirmation persists `IdentityDecision` (already implemented at `R16LibraryMutationRepository.kt:497-510`)
- Undo = delete the `IdentityDecision` + verify the source link and projection actually revert to their previous state. Undo acceptance test must prove the full relationship returns to the pre-confirmation state after restart.

**Call site:**
- Wire Identify Track to long-press/menu on song rows (at minimum in library and queue contexts)

**Fingerprinting:** may be deferred only if the governing R16 completion contract does not require it for pre-cutover completion; otherwise implement it according to the master specification. This plan cannot independently defer a locked master requirement.

### LT03B Current State (verified)
- `unmergeRecording` just deletes all metadata overrides (`R16LibraryMutationRepository.kt:569-574`): `database.libraryDao().deleteAllOverrides(recordingId)`
- This is NOT unmerge — it doesn't restore source relationships, merge audit state, or redirected IDs

### LT03B Work
- Real unmerge: read `MergeAudit` for the recording, restore original entity relationships
- Keep metadata override editor as-is (it's reasonable for field-level edits)
- "Reset to Canonical" = delete override for one field, not all

### Acceptance Contract
- [ ] Local FTS: search returns relevant candidates from full catalogue, not just first N
- [ ] External discovery: `Wiz_Khalifa_See_You_Again...` finds canonical recording via provider search
- [ ] Confirm candidate → `IdentityDecision` persists across restart
- [ ] Undo → source link + projection fully revert to pre-confirmation state (verified after restart)
- [ ] Ambiguous candidates (no strong match) are NEVER auto-confirmed
- [ ] Metadata edit: field-level override wins presentation, field-level revert restores canonical
- [ ] Unmerge: restores original recording entities from merge audit snapshot

---

## Phase 6: LT06A–B — Lyrics

### Current State (verified)
- `R16LyricsCoordinator` exists with generation-scoped cancellation — decent implementation
- But `R16NowPlayingFragment` still opens old `LyricsDialog()` (`R16NowPlayingFragment.kt:345`)
- No production consumer of the new coordinator
- No synchronized lyrics sheet

### Work
- Wire `R16LyricsCoordinator` into `R16NowPlayingFragment`
- Replace old `LyricsDialog` with new coordinator-backed lyrics display
- Add preview card in Now Playing (bounded height, no layout jumps)
- Add expandable full-lyrics sheet with auto-scroll for synchronized lyrics

### Acceptance Contract
- [ ] Now Playing shows lyrics preview from new coordinator
- [ ] Skip track rapidly → stale lyrics from Track A don't overwrite Track B
- [ ] Full lyrics sheet supports manual scroll + auto-follow toggle
- [ ] Plain-text fallback when no synchronized lyrics available

---

## Phase 7: LT05B — Discovery + LT08A — Shell

### LT05B Work
- R16 Home: show Last.fm recommendations/top tracks when connected
- Hide Last.fm surfaces when disconnected
- History screen with scrobble status badges (Sent, Pending, Retryable Failure, Not Authorized)

### LT08A Current State
- `activity_r16_active.xml` has proper M3 `BottomNavigationView` + mini-player — good foundation
- **Problem:** root fragments are recreated on tab switch instead of being preserved
- Process recreation doesn't restore tab/detail state (deliberate `super.onCreate()` pattern prevents fragment restoration)

### LT08A Work
- Use FragmentManager's `show()/hide()` or `setMaxLifecycle()` for root tab fragments instead of replace
- Allow normal fragment restoration on process recreation
- Back navigation: collapse player → pop detail → exit at root

### Acceptance Contract
- [ ] Tab switching preserves scroll position and list state
- [ ] Process kill → reopen → correct tab and detail state restored
- [ ] Connected Last.fm → discovery visible; disconnected → hidden
- [ ] Back press hierarchy: player → detail → root → exit

---

## Phase 8: LT01A–B — Migration Safety & Proof

### Current State (verified)
- `LegacyImportDao` handles: recordings, releases, artists, credits, observations, provenance, library relationships, playlists, playlist entries (`LegacyImportDao.kt`)
- **Missing from import DAO:** downloads, play history, lyrics cache — these are user data that must also be migrated

### Work
- Wire interactive recovery UI: `forceRepair`, `resetToNotStarted`, `reconstructFromAudit`
- **Critical invariant test:** `resetToNotStarted()` clears R16 tables but R15 database + backup file remain byte-identical
- Migration test against representative v10/R15 fixture covering **all user data types**:
  - Songs, Playlists (including duplicate occurrences & custom order), Like states
  - **Download records** (state, progress, destination)
  - **Play history** (including snapshot fields if migrating post-LT00C)
  - **Lyrics cache** entries
- **FK cascade safety test:** After LT00C changes `play_history` FK to `SET NULL`, verify that deleting a recording during migration cleanup does NOT cascade-delete history rows
- Interrupted migration resume test (kill at phase boundary → resume → same result)
- Backup export/restore parity with zero unexplained delta

### Acceptance Contract
- [ ] `resetToNotStarted()` preserves legacy R15 database (byte-for-byte)
- [ ] Migration reconciles all entity counts from fixture (songs, playlists, downloads, history, lyrics)
- [ ] Duplicate playlist entries survive with correct order
- [ ] Download records migrate with correct state
- [ ] Play history rows survive recording deletion (FK is SET NULL, not CASCADE)
- [ ] Interrupted migration resumes idempotently
- [ ] Backup restore reproduces identical state

---

## Phase 9: LT07A–B — Crew

### Work
- Complete `PortableRecordingDescriptor` wire codecs (round-trip test)
- Zero private locators/tokens in wire payloads (privacy test)
- Route Crew playback commands through `PlaybackCoordinator` without echo loops
- Legacy peer fallback — graceful degradation when peer doesn't support latest protocol

### Acceptance Contract
- [ ] Codec round-trips `PortableRecordingDescriptor` between peers
- [ ] Privacy: grep wire payload for filesystem paths/tokens → zero matches
- [ ] Remote Crew play command → `PlaybackCoordinator` receives it exactly once
- [ ] Legacy peer with older protocol → fallback works without crash

---

## Phase 10: LT08B–C, LT09A–B — Polish & Hardening

### LT08B M3 Polish
- Consistent bottom sheets, smooth player transitions
- Album-derived tinting via `ArtworkToneExtractor` (restrained, readable contrast in both themes)
- Non-jumping loading/empty/error states
- Consistent design tokens across all R16 surfaces
- Tactile touch feedback on primary actions

### LT08C Accessibility (full matrix)
- **Touch targets:** minimum 48dp on all interactive elements
- **Content descriptions:** on all interactive elements (buttons, toggles, artwork)
- **TalkBack:** collection semantics (position/count) for lists, state descriptions for playback/like controls
- **Non-drag reorder:** Move Up/Down/Top/Bottom alternatives for queue and playlists
- **DPAD/keyboard navigation:** logical focus traversal order, enter/space activation
- **Focus restoration:** after closing sheets, dialogs, or returning from detail screens
- **Font scaling:** 2.0x reflow without text clipping or control overlap
- **Landscape/split-screen:** no control clipping, usable layout
- **Reduced motion:** respect Android system animation scale settings and accessibility preferences; Material motion behavior scales accordingly
- **No color-only state:** all state communication uses shape/icon/text in addition to color

### LT09A Performance & Backpressure Hardening (verified)

**Channel classification** — three `Channel.UNLIMITED` usages found:
- `PlaybackCoordinator.kt:91` — coordinator events
- `Media3PlayerAdapter.kt:121` — player observations
- `CrewPlaybackBridge.kt:121` — crew commands

Replace with semantically appropriate channels:
- **Lossless commands/sessions:** `Channel(BUFFERED)` with bounded capacity + suspending backpressure
- **High-frequency position/progress:** `Channel.CONFLATED` — latest value wins
- **Lifecycle-critical events:** dedicated bounded handlers

**Host/instrumented performance verification (required for Waves 0–6):**
- 10k queue fixture: shuffle, reorder, mutation, search — verify no O(n²) blowups
- 50k library fixture: Paging load, FTS search, Flow recombination — verify bounded latency
- Room query plans: EXPLAIN QUERY PLAN on critical queries (GC eligible, library songs, search, playlist entries) — standard is no unexpected full table scans on large or hot-path tables where an index is expected. (SQLite legitimately performs table scans on tiny tables where index traversal overhead exceeds sequential scan cost).
- Bounded source preparation: verify preparation pipeline doesn't hold unbounded state
- Zero main-thread disk/network/DB I/O: StrictMode or equivalent assertion in test harness
- Artwork/list binding: verify RecyclerView holders don't trigger synchronous IO

**Not required for Waves 0–6 (deferred to LT10):**
- Physical device profiling with real hardware
- Final p50/p95/p99 latency numbers
- Production memory budgets

### LT09B Diagnostics
- User-triggered diagnostic export covering playback + migration + downloads + crew state
- Strip PII, credentials, filesystem paths — bounded and redacted
- Prune dead scaffolding while preserving cutover compatibility paths for LT10A

### Phase 10 Acceptance Contract
- [ ] Elimination of unjustified unbounded queues in production code (including the 3 identified `Channel.UNLIMITED` instances, replaced with bounded/conflated buffers)
- [ ] All interactive elements ≥ 48dp touch target
- [ ] TalkBack announces collection position for lists
- [ ] DPAD/keyboard: logical focus traversal, enter activation works
- [ ] Focus restores correctly after closing sheets/dialogs
- [ ] 2.0x font scaling: no text clipping
- [ ] Landscape/split-screen: no control clipping
- [ ] Reduced motion respected (Android system animation scale / accessibility preferences)
- [ ] No color-only state communication
- [ ] 10k queue fixture: shuffle + reorder completes without O(n²) blowup
- [ ] 50k library fixture: Paging + FTS search returns in bounded time
- [ ] Room EXPLAIN QUERY PLAN: no unexpected full table scans on large/hot-path tables (tiny tables excluded from scan failures)
- [ ] Zero main-thread disk/network/DB IO (StrictMode or assertion)
- [ ] Diagnostic export contains no credentials/PII (grep test)
- [ ] Dead code removed, cutover compatibility paths preserved

---

## Checkpoint Verification Gates

```bash
# Per-phase compile check
.\gradlew.bat :app:assembleDebug

# Checkpoint full suite (after Phases 0–4, 5–7, 8–10)
.\gradlew.bat :shippy-core:test
.\gradlew.bat :shippy-sources:test
.\gradlew.bat :shippy-data:testDebugUnitTest
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :musikr:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
```

---

## What This Plan Does NOT Cover

- **Waves 7–8** (`LT10A`–`LT10C`): authority cutover, production retirement, release engineering
- **Physical device profiling**: real-hardware performance numbers (deferred to LT10)
- **APK signing / Play Store**: release engineering concerns
