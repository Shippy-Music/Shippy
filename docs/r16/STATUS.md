# Shippy R16 Status

**Updated:** 2026-08-19  
**Active phase:** Phase 2 — Pure Core Model  
**Authority:** `../Shippy_R16_Master_Architecture_and_Implementation_Spec.md`

## Execution mandate

The product owner permanently authorized execution of the complete R16 program.
Do not ask for another general "go" after context compaction. Stop only for an
owner decision that the master specification explicitly marks as blocking.

Build and Gradle caches must be retained between builds. Clear them only after a
specific cache-corruption diagnosis.

Treat the master specification as a compass, not a literal transcription task:
its invariants, data-safety rules, authority boundaries, and required outcomes
are binding, while suggested class names, exact commit counts, and implementation
details may adapt to current evidence. Prefer practical coherent slices and
avoid checklist theatre.

## Current authority state

- R15.3 legacy database, playback manager, Library, and UI remain active.
- No R16 runtime authority is active yet.
- R16 feature flags have not been introduced.
- The master specification supersedes conflicting pre-R16 product,
  architecture, implementation, UX, stabilization, and completion claims.

## Last passing gate

- The Aug-19 source archive matches all 1,235 files in the imported worktree.
- The imported snapshot is committed at `491a1abc0` and tagged
  `r15.3-stabilized-20260819` on `r16/architecture-reset`.
- Source ZIP and APK checksums match the documented stabilization evidence.
- The master specification was copied byte-for-byte into the repository and its
  checksum was verified.
- The tall-player seek bar again uses content height while the 304dp preview
  remains owned by the lyrics container.
- Playlist order controls now live in a playlist-only layout and no longer
  inflate on Songs, Albums, Artists, or Genres.
- The focused layout regression suite passes both ownership invariants.
- New playback now validates its target and records the expected queue-item ID
  before any Media3 mutation can synchronously publish a transition callback.
- The service-owned Last.fm observer samples the monotonic playback progression
  each second, counts only advancing position deltas, and refreshes its canonical
  queue snapshot after reorder or shuffle changes.
- Collection detail now observes only its referenced track metadata and download
  jobs, with duplicate-safe ID queries chunked below SQLite's bind limit.
- Now Playing projects at most the current queue item and its two adjacent
  items into artwork display models, then recenters that bounded window after a
  transition; the full canonical queue remains owned by playback and the queue
  screen.
- Local indexing now snapshots exact Shippy-managed artifact and in-progress
  document identities before Musikr ingestion. Exact URI matches are filtered;
  verified artifacts also match the same stable Musikr path and length across
  SAF/MediaStore aliases. Unrelated files in the selected download folder pass
  through normally.
- `:shippy-core` now exists as an independent Kotlin/JVM module with a forbidden
  dependency gate. Its first model slice defines canonical UUID identities,
  Recording/Artist/Release, source, media-asset, Library, playlist-entry, and
  queue-entry types without Android, Room, Media3, Musikr, provider DTOs, or UI.
- Core metadata now retains observations/provenance separately, resolves fields
  by stable trust precedence with user overrides first, parses material version
  traits, and returns evidence-backed conservative identity decisions with
  exact-key/strong-ID paths and version/duration vetoes.

## Current blockers

- The final public application ID string requires owner selection before the
  dependent namespace/package migration is locked.
- A redacted real owner-device v10 database fixture is required before importer
  cutover can be proven.
- Physical-device verification remains unavailable until a device is attached;
  it does not block pure core or deterministic migration work.

## Next exact slice

1. Complete the remaining pure-core policies: metadata/version matching,
   source selection, queue/shuffle reduction, merge redirects, listening
   thresholds, and portable Crew descriptors.

## Verification level

Baseline preservation is checksum-verified. Changed app Kotlin and Android
resources compile. `R16LayoutRegressionTest` passes (2 tests), and the focused
playback identity/transition suites pass (11 tests). The Last.fm suite passes
(23 tests). Scoped collection composition passes its focused repository and
presentation suites (10 tests). The bounded pager projection suite passes (4
tests). Managed-download reconciliation/filtering passes its focused suite (7
tests). All eight named urgent R15.3 regression repairs are implemented and
focused-unit-tested. `:shippy-core:check` passes independently (6 tests plus its
forbidden-import gate). No R16 code has been instrumented,
device-tested, or performance-tested.
