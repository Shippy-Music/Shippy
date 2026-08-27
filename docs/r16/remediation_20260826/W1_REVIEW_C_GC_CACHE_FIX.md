# Wave 1 review correction — GC and streaming-cache policy

Read master specification sections 13 and 25, the final performance contract, and the existing
W1_C packet before editing.

## Outcome

Finish the existing cache implementation and ensure catalogue GC cannot delete a recording still
referenced by the live playback queue. Use the existing Auxio preference UI; do not build a new
settings subsystem.

## Required corrections

- Replace persisted-checkpoint-only queue protection with a small process-local live snapshot seam
  updated by the active R16 playback owner/spine. The worker may fall back to the durable checkpoint
  when no live snapshot exists, but must prefer live queue recording IDs.
- Keep durable-reference checks in the data layer authoritative. Populate additional runtime
  protections only where current live/cache/pending state is actually available; do not fabricate
  empty abstractions.
- Keep cache keys source-identity + media-variant based and preserve active playback/copy leases.
- Make maximum cache size configurable using practical listed values (256 MB, 512 MB, 1 GB, 2 GB,
  5 GB) through the existing preference infrastructure. A custom-value UI is optional unless it is
  already cheap and consistent.
- Make unused-cache age configurable (7, 30, 90 days, or size-only) and feed the selected value into
  bounded maintenance.
- Keep Clear cache and current-size display; update summaries after changes/clear.
- Do not imply exact age eviction beyond the actual span timestamps used by the implementation.
- Fail WorkManager work truthfully: retry transient maintenance/database failures rather than
  swallowing every exception as success.
- Add focused tests for policy mapping and live-queue protection seam only where inexpensive.

## Boundaries

- Own cache manager/settings resources, GC worker, and the smallest playback-owner/runtime seam.
- Preserve original preference styling and app UI feel.
- No new framework, database/schema bump, Gradle invocation, clean, commit, push, or publish.
- Do not touch Last.fm, playlist/unmerge/identity, navigation, or native/taglib paths.
