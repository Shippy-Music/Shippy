# Shippy Live Status

**Updated:** 2026-07-25  
**Current stage:** Stages 2–3 — library persistence and first provider vertical slice  
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

## In Progress

- Migrating Auxio's Song-only playback boundary to canonical Shippy queue items
  without creating a second player or queue.
- Wiring provider results into Search only after provider playback has a real
  canonical path.
- Extending initial relationship persistence into complete user playlists,
  permanent collection projections, and download artifacts.
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
- Final device behavior is deliberately unverified until the owner performs the
  build/test handoff.

## Next Concrete Actions

1. Generalize the existing Auxio player/queue authority for canonical Shippy
   queue items while retaining exact local compatibility.
2. Connect unified provider search to the real Search UI and canonical playback.
3. Complete user-playlist metadata and permanent collection projections.
4. Implement download storage/jobs against the selected SAF destination.

## Verification Ledger

| Area | Status | Evidence |
|---|---|---|
| Product requirements | Inspected and documented | `PRODUCT_SPEC.md` |
| UX requirements | Documented | `UX.md` |
| Crew behavior | Documented | `CREW.md` |
| Auxio foundation | Inspected | `AUXIO_FOUNDATION_AUDIT.md` |
| Bloomee donor | Inspected | `BLOOMEE_DONOR_AUDIT.md` |
| App code | Implemented, not compiled | Domain, Room relationships, live provider/search repository, download reducer, local adapter, Crew reducer |
| New tests | Authored, not run | Resolver, collections, provider registry, download, Crew |
| External provider shape | Live-inspected | JioSaavn search response on 2026-07-25 |
| Static structure | Parsed | Graphify AST extraction and XML parsing |
| APK/device | Not verified | Owner handoff stage |
