# Shippy cloud handoff

**Updated:** 2026-10-05
**Repository:** https://github.com/Shippy-Music/Shippy
**Continuation branch:** `main`

## Baseline

R17 Beta 2 (`0.1.0-beta.2-r17`, version code 88) is the current publication
target. The owner authorized source synchronization and GitHub release
publication on 2026-10-05. See the top of `STATUS.md` for R17 verification and
`R17_DEVICE_CHECK.md` for pending physical acceptance. The October 3 baseline
and August history below remain context for the earlier synchronization.

The local application repository was `X:\shippy\shippy`, inside a larger
workspace folder. GitHub already contained the R16 Beta 1 release baseline
(`bf6e17487`) and a later contribution-guide update (`3640f67b7`). This handoff
preserves that remote update and publishes the pending August patch changes.

The current README describes R16 Beta 1 and the application ID
`com.rtx09x.shippy`. Older `STATUS.md`, `r16/STATUS.md`, and architecture
addenda contain pre-cutover snapshots; inspect the actual authority selector,
MainActivity, service, and R16 runtime before deciding the next implementation.
Do not reactivate an old cutover task merely because an archived plan lists it.
The inspected selector maps a validated durable `R16_ACTIVE` marker to `ACTIVE`;
the older claim that this always selects an unavailable host is superseded.

## Published local work

- LIFE-59/LIFE-60: keep raw Last.fm delivery diagnostics out of listener-facing
  Now Playing text; stabilize the tall-layout lyrics preview action row.
- LIFE-61 through LIFE-65: shuffle starts from seeded traversal; system metadata
  prefers the selected transition item; normalize persisted Date Added sorting;
  scope playlist/system-collection filtering; align playlist rows.
- Preserve focused browser, playback bridge, and playlist-sort regressions and
  the prior August verification ledger in `STATUS.md`.

For this synchronization, verification is source inspection and static checks.
The August build/test results are historical evidence, not tests rerun today.
Physical acceptance of the pending fixes remains open.

## Resume order

1. Read `AGENTS.md`, `PRODUCT_SPEC.md`, this handoff, and the current README.
2. Read `r16/KNOWN_ISSUES.md`, the relevant feature contract, and the real code.
3. Set up the worker using `CLOUD_SETUP.md` and run the checks relevant to the
   next change. Existing cloud checks previously failed from compiler heap
   exhaustion; workflows now request a larger compiler heap.
4. First validate the pending patches and record new evidence. Continue beta
   device feedback, migration fixtures, and performance work from verified
   gaps rather than stale plan completion percentages.

## Preserved workspace context

`archive/workspace_20260821/` preserves the external R16 execution dossier,
the external master-spec snapshot, the earlier product draft, and historical
Flutter/foundation notes. Its dated state, machine paths, model-routing advice,
snapshot hashes, and task lists describe that historical workspace. Current
in-repository product/architecture contracts take precedence.

The remaining outer folders contain backups or the separate GitHub organization
profile, not application build inputs. Ignored caches/native outputs and APKs
are reproducible or release artifacts; see `CLOUD_SETUP.md`.
