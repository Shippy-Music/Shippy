# Minimal Context Routing Table

| Packet | Give Luna these contracts | Primary paths |
|---|---|---|
| WP00 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C02_IDENTITY_AND_METADATA.md, contracts/C03_PLAYBACK.md, contracts/C06_INTEGRATIONS.md | local source; ingestion/matching; listening; Songs/browser; catalogue GC |
| WP01 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C04_DATA_MIGRATION.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | migration; authority; startup; backup |
| WP02 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C05_OFFLINE_CACHE.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | offline; cache; playback source; download UI/settings |
| WP03 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C02_IDENTITY_AND_METADATA.md, contracts/C04_DATA_MIGRATION.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | identity/provenance; ingestion; identify/editor/cleanup UI |
| WP04 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C04_DATA_MIGRATION.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | Library/read models/playlists/queue/save actions |
| WP05 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C02_IDENTITY_AND_METADATA.md, contracts/C06_INTEGRATIONS.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | listening/Last.fm/Home/history/settings |
| WP06 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C06_INTEGRATIONS.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | lyrics data/coordinator/Now Playing |
| WP07 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C06_INTEGRATIONS.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | Crew + R16 playback/source bridge |
| WP08 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | shell/navigation/UI/resources/accessibility |
| WP09 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C03_PLAYBACK.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | benchmarks/traces/hotspot fixes/cleanup |
| WP10 | contracts/C01_LOCKED_INVARIANTS.md, contracts/C02_IDENTITY_AND_METADATA.md, contracts/C03_PLAYBACK.md, contracts/C04_DATA_MIGRATION.md, contracts/C05_OFFLINE_CACHE.md, contracts/C06_INTEGRATIONS.md, contracts/C07_PERFORMANCE_UX_RELEASE.md | authority/cutover/naming/release/evidence |

Do not send every contract to every worker. Terra may add a direct code file or one neighboring packet excerpt when a concrete dependency appears. The master specification is escalation/reference material, not routine Luna context.
