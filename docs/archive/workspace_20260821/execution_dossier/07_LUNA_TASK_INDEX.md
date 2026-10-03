# Luna Task Index

These are direct execution units. Terra selects only tasks whose dependencies and shared-file reservations are satisfied. A task may be split further only along the explicit ownership boundary inside the file; do not combine unrelated tasks merely to keep workers busy.

| Task | Outcome | Parent | Wave | Dependencies | File |
|---|---|---|---:|---|---|
| LT00A | Correct local recording-version evidence | WP00 | 0 | None | `luna_tasks/LT00A_CORRECT_LOCAL_RECORDING_VERSION_EVIDENCE.md` |
| LT00B | Broaden identity candidate retrieval and model identifier trust | WP00 | 0 | None | `luna_tasks/LT00B_BROADEN_IDENTITY_CANDIDATE_RETRIEVAL_AND_MODEL_IDENTIFIER_TRUST.md` |
| LT00C | Separate local history, Last.fm intent, and transient retention | WP00 | 0 | None | `luna_tasks/LT00C_SEPARATE_LOCAL_HISTORY_LAST_FM_INTENT_AND_TRANSIENT_RETENTION.md` |
| LT00D | Preserve full Songs playback context | WP00 | 0 | None | `luna_tasks/LT00D_PRESERVE_FULL_SONGS_PLAYBACK_CONTEXT.md` |
| LT01A | Implement recoverable migration/bootstrap state | WP01 | 1 | LT00C | `luna_tasks/LT01A_IMPLEMENT_RECOVERABLE_MIGRATION_BOOTSTRAP_STATE.md` |
| LT01B | Prove importer and prepare controlled M14 cutover | WP01 | 2 | LT01A | `luna_tasks/LT01B_PROVE_IMPORTER_AND_PREPARE_CONTROLLED_M14_CUTOVER.md` |
| LT02A | Complete download actions and durable user-facing state | WP02 | 1 | LT00B | `luna_tasks/LT02A_COMPLETE_DOWNLOAD_ACTIONS_AND_DURABLE_USER_FACING_STATE.md` |
| LT02B | Add bounded R16 streaming cache and promotion | WP02 | 2 | LT02A | `luna_tasks/LT02B_ADD_BOUNDED_R16_STREAMING_CACHE_AND_PROMOTION.md` |
| LT03A | Implement Identify Track candidate search and confirmation use case | WP03 | 2 | LT00A, LT00B | `luna_tasks/LT03A_IMPLEMENT_IDENTIFY_TRACK_CANDIDATE_SEARCH_AND_CONFIRMATION_USE_CASE.md` |
| LT03B | Implement metadata editor, merge/unmerge, and bulk cleanup | WP03 | 3 | LT03A | `luna_tasks/LT03B_IMPLEMENT_METADATA_EDITOR_MERGE_UNMERGE_AND_BULK_CLEANUP.md` |
| LT04A | Complete Releases/Albums and Genres read models | WP04 | 2 | LT00B | `luna_tasks/LT04A_COMPLETE_RELEASES_ALBUMS_AND_GENRES_READ_MODELS.md` |
| LT04B | Implement complete Save Destinations and Like/Unlike | WP04 | 2 | LT00C | `luna_tasks/LT04B_IMPLEMENT_COMPLETE_SAVE_DESTINATIONS_AND_LIKE_UNLIKE.md` |
| LT04C | Complete playlist management and batch operations | WP04 | 3 | LT04B | `luna_tasks/LT04C_COMPLETE_PLAYLIST_MANAGEMENT_AND_BATCH_OPERATIONS.md` |
| LT04D | Complete QueueEntryId-based queue manipulation | WP04 | 3 | LT00D | `luna_tasks/LT04D_COMPLETE_QUEUEENTRYID_BASED_QUEUE_MANIPULATION.md` |
| LT05A | Finish connection-scoped Last.fm delivery and status | WP05 | 2 | LT00C | `luna_tasks/LT05A_FINISH_CONNECTION_SCOPED_LAST_FM_DELIVERY_AND_STATUS.md` |
| LT05B | Add restrained Last.fm discovery and complete local history | WP05 | 3 | LT05A, LT03A | `luna_tasks/LT05B_ADD_RESTRAINED_LAST_FM_DISCOVERY_AND_COMPLETE_LOCAL_HISTORY.md` |
| LT06A | Build canonical R16 lyrics repository and coordinator | WP06 | 2 | LT00C | `luna_tasks/LT06A_BUILD_CANONICAL_R16_LYRICS_REPOSITORY_AND_COORDINATOR.md` |
| LT06B | Integrate stable lyrics preview and full lyrics UI | WP06 | 3 | LT06A | `luna_tasks/LT06B_INTEGRATE_STABLE_LYRICS_PREVIEW_AND_FULL_LYRICS_UI.md` |
| LT07A | Migrate Crew protocol identity to portable R16 descriptors | WP07 | 3 | LT00B, LT03A | `luna_tasks/LT07A_MIGRATE_CREW_PROTOCOL_IDENTITY_TO_PORTABLE_R16_DESCRIPTORS.md` |
| LT07B | Route Crew through the single R16 playback authority | WP07 | 4 | LT07A, LT04D | `luna_tasks/LT07B_ROUTE_CREW_THROUGH_THE_SINGLE_R16_PLAYBACK_AUTHORITY.md` |
| LT08A | Replace the isolated ACTIVE scaffold with the real R16 shell | WP08 | 4 | LT04A, LT04B, LT04C, LT05B, LT06B | `luna_tasks/LT08A_REPLACE_THE_ISOLATED_ACTIVE_SCAFFOLD_WITH_THE_REAL_R16_SHELL.md` |
| LT08B | Apply restrained M3 Expressive player, sheets, and QoL polish | WP08 | 5 | LT08A, LT06B | `luna_tasks/LT08B_APPLY_RESTRAINED_M3_EXPRESSIVE_PLAYER_SHEETS_AND_QOL_POLISH.md` |
| LT08C | Complete accessibility and adaptive layout beta gates | WP08 | 5 | LT08A | `luna_tasks/LT08C_COMPLETE_ACCESSIBILITY_AND_ADAPTIVE_LAYOUT_BETA_GATES.md` |
| LT09A | Measure and fix R16 performance/backpressure hotspots | WP09 | 5 | LT02B, LT04D, LT08A | `luna_tasks/LT09A_MEASURE_AND_FIX_R16_PERFORMANCE_BACKPRESSURE_HOTSPOTS.md` |
| LT09B | Consolidate diagnostics and remove unjustified complexity after integration | WP09 | 6 | LT09A | `luna_tasks/LT09B_CONSOLIDATE_DIAGNOSTICS_AND_REMOVE_UNJUSTIFIED_COMPLEXITY_AFTER_INTEGRATION.md` |
| LT10A | Activate R16 safely and retire legacy authority | WP10 | 7 | LT01B, LT02B, LT03B, LT04D, LT05B, LT06B, LT07B, LT08C, LT09B | `luna_tasks/LT10A_ACTIVATE_R16_SAFELY_AND_RETIRE_LEGACY_AUTHORITY.md` |
| LT10B | Complete Shippy naming, legal attribution, and release engineering | WP10 | 7 | LT10A | `luna_tasks/LT10B_COMPLETE_SHIPPY_NAMING_LEGAL_ATTRIBUTION_AND_RELEASE_ENGINEERING.md` |
| LT10C | Run final Appendix V evidence and independent audit loop | WP10 | 8 | LT10A, LT10B | `luna_tasks/LT10C_RUN_FINAL_APPENDIX_V_EVIDENCE_AND_INDEPENDENT_AUDIT_LOOP.md` |

## Wave interpretation

- **Wave 0:** correctness corrections before further feature dependency.
- **Wave 1–2:** authority/offline/identity foundations that can proceed with bounded parallelism.
- **Wave 3–4:** product completion and integration after contracts stabilize.
- **Wave 5–6:** UX, accessibility, performance, diagnostics and evidence-based cleanup.
- **Wave 7–8:** cutover, naming/release and final proof; broad parallel editing is frozen.

Wave is a dependency hint, not a mandate to launch every task in that wave simultaneously. Terra should normally run two or three non-overlapping tasks, integrate, then continue.
