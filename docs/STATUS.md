# Shippy Live Status

**Updated:** 2026-07-25  
**Current stage:** Stages 2–4 — Library completion and player integrations
**Overall state:** In progress

This is the first file to read after `PRODUCT_SPEC.md` whenever work resumes.
Keep it factual and short. Move durable decisions into the canonical documents.

## Completed

- Product owner confirmed Android-only direction.
- Product owner confirmed actual Auxio/Kotlin foundation.
- Clean Auxio source cloned into `X:\piko\shippy` on upstream `dev`.
- Clean Bloomee donor source exists at `X:\piko\bloomee`.
- Canonical product contract created in `docs/PRODUCT_SPEC.md`.
- Detailed UX contract created in `docs/UX.md`.
- Detailed Crew contract created in `docs/CREW.md`.
- Executable staged plan created in `docs/IMPLEMENTATION_PLAN.md`.
- Goal constraint recorded: no repeated heavy Android/Gradle builds on this PC.
- Auxio foundation audit completed with exact source references.
- Bloomee donor audit completed with exact source references.
- Architecture and keep/extend/add/replace boundary documented.
- Working branch frozen from Auxio `dev` as `shippy/main`.
- Graphify code maps created for Auxio `:app` and `:musikr` and merged locally.
- First Shippy code seam implemented:
  - Canonical identifiers, Track, TrackCandidate, QueueItem, and availability.
  - Deterministic playback resolver with Local separation and Crew policy.
  - Eight focused resolver/domain tests authored.
- Exact `musikr.Song` adapter and compatibility resolver implemented without
  changing Auxio's runtime playback path.
- Permanent Liked, Downloads, and Local collection rules modelled.
- Provider capability/health/priority contracts implemented.
- Download lifecycle reducer implemented through verified permanent artifact.
- Deterministic Crew state/event reducer implemented with equal-member queue and
  playback actions, sequence/term checks, snapshot gaps, and coordinator transfer.
- Shippy app label and required network permissions introduced.
- Four primary destinations implemented in the real Android navigation graph:
  Home, Search, Library, and Crew.
- Auxio mini-player/full-player/queue sheets retained and offset above the
  primary navigation; full-player expansion fades navigation away.
- Top-level destination state restoration and Library-only Auxio FAB behavior
  implemented.
- Home now reflects real current playback and real local-library statistics.
- Search is a top-level destination; the mature Auxio local library is retained
  under Library.
- JioSaavn media URL/response utilities and LRC parser ported with focused tests.
- Shippy-owned Room persistence now stores Liked, Downloaded, and user-playlist
  relationships while keeping Local derived.
- Live JioSaavn search and quality-aware stream resolution are implemented
  behind the Shippy provider contract and registered through Hilt.
- Provider HTTP transport enforces HTTPS, bounded responses, and timeouts
  without adding another networking stack.
- Unified provider search isolates partial provider failures and preserves
  cancellation.
- The live JioSaavn search response shape was checked on 2026-07-25; no secret
  credential was required or stored.
- Auxio's single playback manager and ExoPlayer holder now store canonical
  duplicate-safe Shippy queue items while deriving local Song compatibility.
- Local and provider media use one Media3 path with content/file/HTTPS routing,
  per-queue-item request headers, canonical metadata, and no second player.
- Unified Search now renders independent provider sections, scoped failures,
  and a labelled On this device section.
- Provider Search results resolve asynchronously before entering the synchronized
  player authority and can start real JioSaavn playback.
- Mini/full player identity, artwork, queue, MediaSession, notification metadata,
  headset/media-button presence checks, and widgets consume canonical playback
  items; exact local artwork behavior is retained.
- Legacy playback persistence remains local-only and never stores expiring
  provider URLs or headers.
- User-playlist metadata and ordered membership are persisted with explicit Room
  migrations; create, rename, pin, reorder, replace-tracks, and delete operations
  enforce the permanent-system-collection boundary.
- A user-selected SAF download destination now persists Android read/write access,
  reports revoked access, scans supported existing audio, and releases a replaced
  destination grant.
- Durable download jobs now persist track/candidate provenance, progress, failures,
  pending documents, and verified artifacts in the shared Shippy Room database.
- WorkManager/Hilt execution, foreground progress, pause/resume/retry/cancel/remove,
  exact-source resolution, bounded transfer, and content-length verification are
  implemented at code level.
- Duplicate in-process download requests reuse the existing job, and Downloads
  membership is published only from verified available artifacts.
- Download reconciliation now runs at process startup and from the destination
  Settings lifecycle. It validates exact artifact URI and length, repairs missing
  artifacts/relationships, and exposes unknown existing audio without adopting it
  by filename.
- The Auxio Library playlist surface now leads with truthful permanent Liked,
  Downloads, and Local projections, followed by persisted playlist names and the
  existing fully interactive on-device Auxio playlists.
- A native ordered lyrics-source boundary, live LRCLIB adapter, conservative
  recording matcher, synced-LRC parser path, and playback-bound cancellable lookup
  state are implemented. Musixmatch remains an optional future primary source
  requiring an official securely supplied API credential.
- Auxio's real full-player surface now exposes direct Save, capability-aware
  Download, and Queue actions without duplicate overflow commands. Completed
  downloads render as a non-destructive checked state.
- A second Save tap edits Liked and user-playlist destinations. Download removal
  lives behind the provider overflow and an explicit confirmation instead of a
  permanent trash control.
- Provider playback now retains a compact secondary menu for Queue, honest source
  information, and Android sharing rather than hiding the overflow entirely.
- Synced/plain lyrics render below the main portrait player. Active-line selection
  follows playback without rebuilding the full lyric body every tick, stays blank
  before the first timestamp, and avoids automatic TalkBack live-region spam.
- Download publication, removal, and destination reconciliation now share one
  process gate so stale reconciliation cannot overwrite a newly verified
  Downloads relationship.
- WorkManager network constraints are derived from the exact requested download
  candidate, not from unrelated candidates attached to the same canonical track.
- An entirely empty Library retains the permanent collection rows and adds an
  explicit folder-selection onboarding row.

## In Progress

- Adding real detail/playback/edit flows for permanent collections and mixed
  Shippy playlists; current Shippy rows are truthful projections only.
- Passing unmanaged existing download-folder audio into the canonical/local
  indexing path without filename-based adoption.
- Wiring direct download state/actions into track and collection rows.
- Adding durable offline lyrics cache and an expandable lyrics surface for compact
  player configurations.
- Completing provider/player action surfaces beyond the first Search-to-play
  vertical slice.
- Crew transport/session engine behind the new Crew destination.

## Not Started

- Full Gradle/Android build.
- Physical-device verification.

## Current Risks

- Auxio upstream `dev` has documented Windows build limitations that require
  exact verification before selecting the day-to-day development branch.
- Bloomee provider logic is Dart/Flutter and must be ported/reimplemented, not
  assumed reusable as native Kotlin.
- Crew transport must be proven with a focused spike before committing to a
  networking library.
- Room/Hilt generation, JioSaavn JSON parsing, and provider playback are
  compile/device-unverified until the owner build.
- WorkManager/Hilt worker generation, SAF storage, and foreground download
  execution are code/static-checked but compile/device-unverified.
- LRCLIB response shape was live-checked on 2026-07-25; Kotlin parsing, Hilt
  multibinding, and playback lookup remain compile/device-unverified.
- Canonical Media3 custom-cache-key/header routing is implemented and
  syntax-checked but remains runtime-unverified.
- Remote provider artwork currently reaches in-app Coil surfaces and metadata
  URIs; notification/widget bitmap loading still needs the dedicated remote
  artwork path.
- Final device behavior is deliberately unverified until the owner performs the
  build/test handoff.

## Next Concrete Actions

1. Add permanent collection and mixed-playlist detail/edit flows.
2. Add durable offline lyrics cache and unmanaged-folder indexing.
3. Add the next viable provider/YouTube adapter.
4. Continue Crew domain, transport, and active-session UI.

## Verification Ledger

| Area | Status | Evidence |
|---|---|---|
| Product requirements | Inspected and documented | `PRODUCT_SPEC.md` |
| UX requirements | Documented | `UX.md` |
| Crew behavior | Documented | `CREW.md` |
| Auxio foundation | Inspected | `AUXIO_FOUNDATION_AUDIT.md` |
| Bloomee donor | Inspected | `BLOOMEE_DONOR_AUDIT.md` |
| App code | Implemented, not compiled | Canonical player/UI/system consumers and direct actions, Room library/download persistence, SAF/reconciliation, WorkManager transfer pipeline, Library projections/onboarding, LRCLIB/playback lyrics lookup and synced-line presentation, live provider/Search-to-play, local adapter, Crew reducer |
| New tests | Authored, not run | Resolver, collections/projections/onboarding, provider registry, download reducer/transfer/persistence/reconciliation/publication gate, lyrics matcher/source chain/active-line timing, player action presentation, Crew |
| External provider shape | Live-inspected | JioSaavn search and LRCLIB exact-lyrics responses on 2026-07-25 |
| Static structure | Parsed | Graphify 276-file AST extraction, XML parsing, `git diff --check` |
| APK/device | Not verified | Owner handoff stage |
