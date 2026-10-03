# Appendix V Coverage Map

This is a routing matrix, not proof. Terra updates status only when evidence exists.

| Contract area | Requirement cluster | Owning packet(s) |
|---|---|---|
| V.1 Identity | Canonical Recording/version; sources/assets; managed-download dedupe; idempotent ingestion | WP00, WP02, WP03 |
| V.1 Identity | Conservative/reversible matching; user overrides; occurrence IDs; acyclic redirects | WP00, WP03, WP04 |
| V.2 Playback | Single coordinator; Media3 projection; all surfaces agree; deterministic shuffle | existing core + WP04, WP07, WP08, WP10 |
| V.2 Playback | stale-result safety; bounded queues; fallback; restore; system controls | existing core + WP00, WP02, WP09, WP10 |
| V.3 Library/offline | distinct catalogue/Library/cache/download; scoped search/Paging | WP02, WP04 |
| V.3 Library/offline | playlist order/duplicates; verified downloads; distinct deletion actions | WP02, WP04 |
| V.3 Library/offline | real R15 data migration with zero unexplained loss | WP01, WP10 |
| V.4 Integrations | Last.fm optional/canonical/audible/offline; lyrics stale-safe; Crew one authority | WP05, WP06, WP07 |
| V.4 Integrations | provider failure isolation | existing provider boundary + WP09/WP10 validation |
| V.5 Performance | main-thread/large-list/10k/50k/cache/background/baseline/ANR | WP09, WP10 |
| V.6 Experience | layout regressions; Home; sheets/back/spacing; color/M3; accessibility/fidelity | WP08, WP10 |
| V.7 Evidence | install/migration/backup/device/offline/Last.fm/Crew/signing/perf/owner acceptance | WP10 |

## Existing foundation evidence to preserve

- Typed Recording/Source/Asset/PlaylistEntry/QueueEntry IDs.
- Pure queue/playback reducers and deterministic shuffle.
- Generation-safe PlaybackCoordinator and bounded Media3 projection.
- R16 Room schema, FTS/Paging/scoped repositories, backup/import foundation.
- Exact-source ingestion and managed asset registry.
- R16 Library/Search/Home/player/system-surface implementations.
- Durable listening/history/outbox and substantial download pipeline.
- Focused host tests and reported debug build/lint evidence.

## Evidence rule

A coverage row changes from “represented” to “passed” only when the exact evidence level required by Appendix V exists. Source presence or a test double does not satisfy physical migration/device/performance requirements.
