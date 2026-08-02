# Shippy Beta-Readiness Audit Traceability

This document maps every actionable gap in
`Shippy_Beta_Readiness_Audit_2026-08-02.md` to implementation and evidence. A
row marked **source complete** is not a claim of physical-device acceptance.

## Gap matrix

| Gap | Result | Implementation evidence | Remaining acceptance |
|---|---|---|---|
| G-01 | Source complete | `PlaybackMutation`, playback-state interceptors, and `CrewPlaybackBridge` separate intent, projection, and acknowledgement. | Delayed Android callback matrix. |
| G-02 | Source complete | `CrewClockProtocol`, session CLOCK routing, periodic probes, and coordinator-clock estimates. | Real asymmetric-network measurements. |
| G-03 | Source complete | Accepted commands are ordered; projection no longer uses cancellable callback inference/debounce. | Rapid controls on 2/3 devices. |
| G-04 | Source complete | `QueueViewModel` reads active Crew canonical queue, including unavailable rows. | Visual/device acceptance. |
| G-05 | Source complete | Stable anchored insert/move/remove operations; coordinator rebase and stale-base handling. | Concurrent 3-device queue matrix. |
| G-06 | Source complete | Resolution preserves placeholders and canonical queue shape. | Provider outage/recovery on devices. |
| G-07 | Source complete | Availability and fan-out support any active member as original supplier, including coordinator redistribution. | Three-device non-host supplier test. |
| G-08 | Source complete | Per-object transfer limit raised to a normal media-size bound (~176 MiB). | MP3/AAC/FLAC 7/9/30/100 MiB matrix. |
| G-09 | Source complete | Session temporary budget is separate and 512 MiB; current plus two upcoming are prefetched. | Storage-pressure device test. |
| G-10 | Source complete | Random-access disk assembly, 44 KiB chunks, four-chunk window, cumulative ACKs; no whole-object heap assembly. | Profiler evidence on large transfers. |
| G-11 | Source complete | `CrewTemporaryMediaDataSource` reads verified contiguous ranges and wakes as data arrives. | Playback-under-throttling test. |
| G-12 | Source complete | Temporary index reader leases defer cleanup until Media3 releases the source. | Crew-end-during-playback device test. |
| G-13 | Source complete | `PlaybackCacheManager` provides bounded Media3 `SimpleCache` for provider media. | Offline replay after real provider stream. |
| G-14 | Source complete | `MediaObjectKey` replaces queue-occurrence cache keys. | Duplicate occurrence cache-hit evidence. |
| G-15 | Source complete | Complete verified cache spans can be promoted to the permanent download path. | SAF/OEM promotion test. |
| G-16 | Source complete | `ProviderPlaybackLifecycle` eagerly resolves only current + 2, refreshes expiry, and retries selected failures. | Expiring provider URL device test. |
| G-17 | Source complete | Local player failures are local diagnostics/recovery and cannot emit a canonical skip. | Audio-focus/provider-error matrix. |
| G-18 | Source complete | Coordinator owns shared end/advance semantics; ordinary local rewind is not translated into Crew intent. | Near-end/repeat device matrix. |
| G-19 | Source complete | `ServiceRetentionPolicy` retains active/reconnecting Crew and media transfer independent of paused playback. | Background/doze/task-removal matrix. |
| G-20 | Source complete | `ActiveCrewRuntime` invokes recovery/rejoin from checkpoint and renewable lease while preserving membership ID. | Process-kill and route transition. |
| G-21 | Retained and tested | Playback checkpoint sanitizer excludes Crew-temporary and non-durable download locators. | OEM/process restoration. |
| G-22 | Source complete | Editable display name, regenerable private avatar descriptor, live `MemberUpdated`, split profile/device/membership IDs. | Multi-device UI acceptance. |
| G-23 | Source complete | Queue attribution and bounded recent Crew activity use the issuing membership/profile. | Concurrent action attribution test. |
| G-24 | Source complete | Local song action invokes `LocalMediaDeletionCoordinator` with MediaStore consent and reindex. | Android 7-16/OEM consent matrix. |
| G-25 | Source complete | Local delete, download removal, provider-cache clearing, and collection removal are distinct actions. | Destructive-action device matrix. |
| G-26 | Source complete | Root settings group providers/connections, Crew, cache/storage, and profile coherently. | UX acceptance. |
| G-27 | Source complete for automation | API 35 instrumentation covers real Media3 playback with generated audio plus Android-private temporary storage/service lifecycle; seeded JVM transport abuse covers protocol/domain convergence. | Run emulator CI and physical matrix. |
| G-28 | Source complete for CI | Workflow includes lint, JVM tests, release assembly, persistence/auth focus, and API 35 connected tests. | Green CI run and signed owner build. |
| G-29 | Corrected in R15.2 | Established alpha application ID `org.oxycblt.auxio` retained, debug suffix isolated, and provider authorities derived from the active ID. | R14-to-R15.2 in-place upgrade acceptance. |
| G-30 | Complete | `STATUS.md` now reports evidence levels and pending device gates without invented completion percentages. | Keep updated per release. |

## Automated evidence boundary

The product verification boundary is:

```bash
./gradlew spotlessCheck app:testDebugUnitTest musikr:testDebugUnitTest \
  app:lintDebug app:compileDebugAndroidTestKotlin
```

The repository-root aggregate `testDebugUnitTest` also enters vendored Media3
upstream test source sets that do not carry their upstream test dependencies;
it is not the Shippy product gate. CI additionally assembles release and runs
the API 35 instrumentation task.

Latest local result (2026-08-02): formatting passed; 497 app JVM tests ran with
496 passed and one intentional opt-in live smoke skipped; Musikr JVM tests
passed; lint reported zero errors; Android instrumentation sources compiled.
The connected emulator test and release assembly are configured in CI rather
than executed on this resource-constrained PC.

## Physical beta gate

Beta may be claimed only after evidence exists for:

1. Android 7/10/12/15/16 single-device playback, provider, cache, download,
   local deletion, background, and upgrade behavior.
2. Two-device LAN/Nearby/hotspot, offline, relay, pause/play/seek, queue edits,
   Push & Pull, throttling, route loss, and process rejoin.
3. Three-device non-coordinator supply, concurrent duplicate queue edits,
   attribution, coordinator loss/election, and resumed verified transfer.
4. Large media and storage-pressure tests with memory and exact-hash evidence.
5. The separate accessibility release-hardening pass.

Use `DEVICE_TEST_HANDOFF.md` to record device model, Android version, topology,
seed/trace where applicable, result, and first failure.
