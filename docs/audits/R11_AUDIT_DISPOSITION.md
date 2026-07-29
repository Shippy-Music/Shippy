# R11 Audit Disposition

**Source:** `Shippy_Alpha_Audit_2026-07-29.md`  
**Release:** `0.1.0-alpha.1-r11` (75)  
**Rule:** R11 fixes correctness, data-safety, security, and measured hot-path
problems. Architectural UI rewrites are not smuggled into a hardening build
without device benchmarks.

## Fixed in R11

- **SH-001–004:** Crew empty queues, partial resolution, stable queue identity,
  and atomic pager command/state.
- **SH-006–009:** bounded playback-error recovery, no paused 10 Hz polling,
  immediate shuffle mutation, and correct genre routing.
- **SH-011–017:** background/debounced local search; private staged downloads;
  no recursive destination scan; durable-first removal; bounded secure
  redirects; synchronized Crew transfer state; streamed temporary-media
  verification.
- **SH-018–021:** per-client relay backpressure, bounded per-IP ICE requests,
  bounded filesystem channels, and visible Crew diagnostics.
- **SH-023, SH-025–033:** success-only provider history, bounded Last.fm backlog
  draining, targeted job observation, transactional library/reconciliation
  writes, correct intent consumption, guarded media browser/cover/media-button
  surfaces, and disabled alpha backup.
- **SH-036:** Room schema export is enabled and the current schema is checked in.

## Mitigated

- **SH-005:** the selected provider item resolves first; remaining items resolve
  with bounded parallelism and individual failures no longer abort playback.
  Fully lazy queue hydration remains future work.
- **SH-010:** paused-player polling and avoidable pager state races were removed.
  The deeper player-sheet architecture requires physical-device
  Macrobenchmark evidence before replacement.
- **SH-017:** verified chunks are streamed into the private cache rather than
  joined into a second full payload. Protocol-level chunk buffering remains
  bounded by the existing transfer limits.
- **SH-037:** CI already gates formatting, JVM tests, lint, assembly, and APK
  publication. Instrumented multi-device/Crew and release-signing tests remain
  manual alpha gates.

## Deliberately deferred

- **SH-022:** lazy standby ExoPlayer lifecycle, pending decoder/memory profiling.
- **SH-024:** sleep-timer process-death restoration. It needs a wall-clock and
  queue-replacement contract, not a fragile preference patch.
- **SH-034:** verified HTTPS App Links require a Shippy-owned production domain.
- **SH-035:** application-ID migration is a beta boundary because changing it
  breaks update continuity for current alpha installs.
- **SH-038:** dependency upgrades require a separate compatibility pass.
- **SH-040–041:** lyrics list virtualization and Home `ConcatAdapter` conversion
  require device frame/allocation baselines.
- **SH-042–045:** product hierarchy and visual-direction proposals, not R11
  correctness defects.

## Verification boundary

Passing local gates prove compilation, deterministic JVM behavior, lint,
formatting, relay behavior, APK assembly, and signature/package inspection.
They do not prove physical-device frame pacing, OEM media behavior, provider
longevity, or multi-phone Crew recovery. Those remain owner-device acceptance
items.
