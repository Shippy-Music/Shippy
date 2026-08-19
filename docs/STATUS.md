# Shippy Live Status

**Updated:** 2026-08-19
**Stage:** R16 architecture overhaul in progress; R15.3 remains the active runtime
**Canonical audit:** `Shippy_Beta_Readiness_Audit_2026-08-02.md`  
**Traceability:** `BETA_READINESS_IMPLEMENTATION.md`
**Current stabilization spec:** `STABILIZATION_SPEC_2026-08-18.md`
**R16 directional authority:** `Shippy_R16_Master_Architecture_and_Implementation_Spec.md`

Read this file after `PRODUCT_SPEC.md` whenever work resumes. This status is
evidence-based: source presence is not counted as verified device behavior.

## R16 architecture reset

R16 is being built locally on `r16/architecture-reset`. The master R16 document
defines the invariants, data-safety requirements, authority boundaries, and
target product direction; concrete names and slice boundaries adapt to verified
repository constraints. R15.3 is still the production playback/database/UI
authority. The R16 database, source stack, and playback coordinator are not yet
wired into the shipped service, so there is no dual-authority runtime.

Implemented R16 foundations now include:

- pure identity, metadata, source-selection, queue, redirect, listening, and
  generation-safe playback models in `:shippy-core`;
- the separate Room R16 schema, transactional repositories, FTS/read models,
  verified backup/import audit path, and resumable read-only R15 importer;
- conservative local/provider ingestion, exact source identity, persistent
  source authority, and bounded transient-catalogue lifecycle;
- an inactive serialized playback coordinator with QueueEntryId occurrence
  semantics, deterministic shuffle/repeat, stale callback rejection, bounded
  current/previous/look-ahead source preparation, automatic engine transitions,
  source-neutral checkpoint/restore, and one bounded retry for retryable current
  source failure;
- monotonic audible-time tracking driven by continuous low-frequency ticks,
  plus a bounded generation/queue/entry playback trace.

Focused verification covers duplicate occurrences, rapid generation replacement,
distant window rebuild, source retry, source-neutral restore, automatic advance,
continuous audible time, a 300-command seeded invariant trace, and a 10,000-entry
queue that prepares only four sources. The coordinator still uses a fake engine
contract in tests; Media3 service integration and production cutover remain open.
The full local gate passes: 25 `:shippy-core`, 10 `:shippy-sources`, 37
`:shippy-data`, and 529 app JVM tests (0 failures, 1 existing skip), plus
`spotlessCheck`, `:app:lintDebug`, and `:app:assembleDebug`.

## Reopened from current device feedback

The 2026-08-18 device report reopened playback/metadata identity under playlist
Play and Shuffle and identified concrete gaps in Continue Listening, Last.fm
accepted-scrobble handling, Library/playlist workflows, Home density, save
destinations, lyrics stability, and light-theme icon tint. The bounded
implementation is now present and the automated gate is green; physical
acceptance remains open. The acceptance plan is in
`STABILIZATION_SPEC_2026-08-18.md`.

## Implemented in the current pass

- Typed playback mutations separate user intent, Crew projection, and local
  player acknowledgement. Crew no longer infers group commands from arbitrary
  asynchronous player callbacks.
- Protocol v3 carries ordered control, snapshots, availability, readiness, and
  NTP-style clock probes. Future starts, bounded catch-up, and drift correction
  use the coordinator clock estimate.
- The Crew queue shown in UI is the canonical queue. Unresolved occurrences stay
  visible, granular edits retain stable occurrence IDs and anchors, and member
  attribution is attached to each shared mutation.
- Provider playback resolves only the current item plus two look-ahead items,
  refreshes expired URLs, and retains unresolved placeholders without changing
  queue shape.
- Provider playback uses a 512 MiB Media3 cache keyed by stable media-object
  identity. Complete cache entries can be promoted into permanent downloads.
- Push & Pull uses bounded, resumable, disk-backed chunk transfer with verified
  ranges, cumulative acknowledgements, progressive playback, active-reader
  leases, current-plus-two prefetch, and any-member supply through the session
  topology. Peer media stays temporary unless explicitly downloaded.
- Active and reconnecting Crew sessions retain the playback service and can
  restore membership from a valid checkpoint/lease after process or route loss.
- Local deletion is distinct from download removal and cache clearing, using
  MediaStore/Android consent where required. Crew profile name/avatar, live
  member updates, recent activity, grouped settings, and generated private
  avatars are present.
- Alpha application ID remains `org.oxycblt.auxio`; debug builds use
  `org.oxycblt.auxio.debug` so R14 updates retain data. Package-scoped provider
  authorities are derived from the active ID.
- CI now checks formatting, app JVM tests, Musikr JVM tests, lint, release
  assembly, focused persistence/authentication tests, and API 35 instrumentation.
- R15.3 fixes the severe shuffled-playback identity split. Next, Previous, GoTo,
  automatic playback, and Media3 playlist transitions now publish state only
  after Media3 commits the actual audio item. Queue order and current index are
  projected atomically, and provider look-ahead follows shuffled traversal rather
  than physical storage order.
- The stabilization pass makes `QueueItemId` the playback/UI identity and waits
  for the targeted Media3 item before publishing a new-playback acknowledgement.
  Debug builds log the committed media ID, queue occurrence, track ID, index,
  and shuffle state for physical verification.
- Continue Listening is current-item-first, hides completely without a current
  item, uses compact horizontal recent/recommendation rows, deduplicates recent
  tracks, and removes the Home Recent Downloads block.
- Last.fm scrobbling now parses accepted versus ignored entries, persists a
  deterministic bounded outbox, retries on network recovery, exposes delivery
  status, and provides one daily cached/refreshable similar-track row capped at
  eight items with artwork fallback.
- Library collection and track workflows now use DiffUtil, explicit playlist
  order editing, collection action sheets, sort sheets, selection-aware batch
  actions, and transactional liked/playlist mutations. System collections expose
  only pin/unpin actions.
- Save destinations use a guarded bottom sheet keyed by `QueueItemId`. Lyrics
  preview height is fixed at 304dp in the tall layout, and artwork tone styling
  uses a bounded LRU cache with a contrast fallback.
- Home/library strings, light-theme icon tint, and the new playback/library
  surfaces are formatter-checked and lint-clean for errors.
- Final review made bulk local deletion iterative and performs one rescan after
  the batch, avoiding deep recursion and repeated full-library rescans. The
  existing Crew reconnect test now waits for its asynchronous terminal state,
  removing a suite-order timing race without changing Crew runtime behavior.

## Verification state

- Kotlin source and Android/JVM test-source compilation: passed.
- Focused playback, library, and Last.fm tests: passed.
- Final non-device gate passed: `spotlessCheck`, `:app:testDebugUnitTest`
  (529 tests, 0 failures, 1 skipped), and `:app:lintDebug` (0 errors).
- `:app:assembleDebug` passed. Current debug artifact:
  `artifacts/Shippy-Alpha-R15.3-Stabilized-20260819.apk` (61,707,279 bytes;
  SHA-256
  `768B3DE16C8E17A2CBE580B9DD5CF80BB87A31CFD334F858902DB321C038A0D8`).
  APK Signature Scheme v2 verification passed, and its debug certificate digest
  matches the prior R15.3 artifact, so it updates that installed test app in
  place.
- `adb devices` currently reports no attached device, so playback sequence,
  Last.fm delivery against a real account, light/dark theme inspection, 20
  transition `gfxinfo` sampling, and the 1,000-track performance check remain
  unverified.
- Physical two/three-phone, Android 7-16, route-transition, real provider,
  large-file, process-death, and release-install behavior: not yet verified.
- Accessibility remains explicitly deferred to the dedicated release-hardening
  pass, per product decision; it is not counted as beta-ready evidence.

## Release truth

R15.3 retains the established R14/R15.2 package identity so it is an in-place update
that retains existing app data. It also derives every content-provider authority
from that application ID. R15 changed the package identity and R15.1 therefore
installed as a second app instead of updating R14.

The R15.3 artifact is a debug-signed alpha package
(`org.oxycblt.auxio.debug`, version code 86), not a production-signed beta. Do
not label it beta-complete until the device matrix in
`DEVICE_TEST_HANDOFF.md` is recorded and passes. Artifact:
`Shippy-Alpha-R15.3-Stabilized-20260819.apk` (61,707,279 bytes; SHA-256
`768B3DE16C8E17A2CBE580B9DD5CF80BB87A31CFD334F858902DB321C038A0D8`).

## Next gate

Install the current debug artifact and first reproduce the exact Liked/playlist
Play, Shuffle, Next, Previous, and shuffle-toggle matrix. Then run the wider
physical acceptance matrix, including Last.fm, themes, lyrics, and performance.
Any device failure reopens
the relevant gap; percentages are intentionally omitted because they previously
obscured missing vertical evidence.
