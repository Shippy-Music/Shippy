# Ownership and Parallelism Boundaries

## Shared choke points: Terra-only integration

The following files and surfaces should not be casually edited by parallel Luna workers:

- `settings.gradle`, root/app/module `build.gradle`, `gradle.properties`.
- `app/src/main/java/org/oxycblt/auxio/MainActivity.kt`.
- `app/src/main/java/org/oxycblt/auxio/AuxioService.kt`.
- main shell/navigation resources and application manifest.
- `shippy-data/.../ShippyR16Database.kt`.
- `shippy-data/.../ShippyR16DatabaseMigrations.kt`.
- `shippy-data/.../R16DataRuntime.kt`.
- `app/.../r16/authority/R16AuthoritySelector.kt`.
- `app/.../r16/playback/PlaybackCoordinator.kt`.
- `app/.../r16/playback/R16PlaybackSpine*.kt`.
- `app/.../r16/playback/service/R16PlaybackService*.kt`.
- cross-packet shared strings, navigation IDs, and common bottom-sheet styles.

A Luna worker can propose a change to one of these files, but Terra should apply or integrate it after checking all active packets.

## Packet ownership map

| Packet | Primary owned paths | Shared seams requiring Terra |
|---|---|---|
| WP00 | local source adapter, matching/ingestion queries, listening/history policy, Songs playback context | DB migrations if needed; browser command contract |
| WP01 | migration/recovery packages, authority selector, startup host | MainActivity, AuxioService, DB/runtime lifecycle |
| WP02 | `r16/offline`, offline repository/DAO, cache package, download UI/settings | R16DataRuntime, DB schema, PlaybackSourceRepository |
| WP03 | identity use cases, provenance/redirect repositories, identify/editor/cleanup UI | DB schema, ingestion, Library membership |
| WP04 | R16 Library/playlist/queue UI and read/mutation repositories | browser contract, DB views/FTS, shell navigation |
| WP05 | R16 Last.fm, history, recommendations, related settings/Home | listening-session contract, R16DataRuntime, Home composition |
| WP06 | R16 lyrics repository/coordinator/UI | playback snapshot/presentation, DB runtime |
| WP07 | Crew adapters/bridge/protocol migration | PlaybackCoordinator, service retention, portable identity |
| WP08 | R16 shell, UI components/resources, M3/accessibility | MainActivity/navigation/common styles |
| WP09 | macrobenchmark, baseline profile, diagnostics/performance fixes | production code only through owning packet/feedback loop |
| WP10 | activation, legacy deletion, naming/legal/release | all central choke points; Terra/Sol ownership |

## Safe parallel examples

- Lyrics repository/UI and offline cache can proceed concurrently after WP00 if they do not both edit playback central files.
- Last.fm product integration and download UI can proceed concurrently if Home and settings integration are serialized.
- Identify UI and Release/Genre Library read models can proceed concurrently after identity interfaces/schema are fixed.
- Accessibility audit and benchmark harness can proceed in parallel with feature work, but production fixes must return to the owning packet.

## Unsafe parallel examples

- Two agents independently changing Room schema versions/migrations.
- One agent changing `PlaybackCommand` while another changes MediaSession routing.
- Separate agents migrating Crew and system controls through `PlaybackCoordinator` without one owner.
- Multiple UI agents editing the same shell/nav graph or shared sheet style.
- One agent deleting legacy paths while another still uses them for migration/compatibility.

## Handoff discipline

Every child report must include:

- exact files changed;
- APIs/contracts changed;
- tests/commands run and result;
- shared-file edits requested from Terra;
- assumptions discovered to be false;
- remaining risk;
- whether its owned paths are safe for another agent.

Terra merges/integrates only after reviewing overlap. If ownership boundaries were violated, stop further parallel work in that area until the branch is reconciled.
