# Shippy Live Status

**Updated:** 2026-08-02  
**Stage:** Beta-readiness implementation; physical acceptance pending  
**Canonical audit:** `Shippy_Beta_Readiness_Audit_2026-08-02.md`  
**Traceability:** `BETA_READINESS_IMPLEMENTATION.md`

Read this file after `PRODUCT_SPEC.md` whenever work resumes. This status is
evidence-based: source presence is not counted as verified device behavior.

## Implemented in the current pass

- Typed playback mutations separate user intent, Crew projection, and local
  player acknowledgement. Crew no longer infers group commands from arbitrary
  asynchronous player callbacks.
- Protocol v3 carries ordered control, snapshots, availability, readiness, and
  NTP-style clock probes. Future starts, bounded catch-up, and drift correction
  use the coordinator clock estimate.
- The Crew queue shown in UI is the canonical queue. Unresolved occurrences stay
  visible, granular edits retain stable occurrence IDs and anchors, and member
  attribution is attached to each shared mutation.
- Provider playback resolves only the current item plus two look-ahead items,
  refreshes expired URLs, and retains unresolved placeholders without changing
  queue shape.
- Provider playback uses a 512 MiB Media3 cache keyed by stable media-object
  identity. Complete cache entries can be promoted into permanent downloads.
- Push & Pull uses bounded, resumable, disk-backed chunk transfer with verified
  ranges, cumulative acknowledgements, progressive playback, active-reader
  leases, current-plus-two prefetch, and any-member supply through the session
  topology. Peer media stays temporary unless explicitly downloaded.
- Active and reconnecting Crew sessions retain the playback service and can
  restore membership from a valid checkpoint/lease after process or route loss.
- Local deletion is distinct from download removal and cache clearing, using
  MediaStore/Android consent where required. Crew profile name/avatar, live
  member updates, recent activity, grouped settings, and generated private
  avatars are present.
- Alpha application ID remains `org.oxycblt.auxio`; debug builds use
  `org.oxycblt.auxio.debug` so R14 updates retain data. Package-scoped provider
  authorities are derived from the active ID.
- CI now checks formatting, app JVM tests, Musikr JVM tests, lint, release
  assembly, focused persistence/authentication tests, and API 35 instrumentation.

## Verification state

- Kotlin source and Android/JVM test-source compilation: passed.
- Focused Crew/cache/provider/profile/recovery and seeded adversarial transport
  tests: passed.
- Full non-device gate passed after the final integration edits:
  `spotlessCheck`, 497 app JVM tests (496 passed, one intentional opt-in live
  smoke skipped), Musikr JVM tests, Android lint (zero errors), and
  `compileDebugAndroidTestKotlin`.
- Physical two/three-phone, Android 7-16, route-transition, real provider,
  large-file, process-death, and release-install behavior: not yet verified.
- Accessibility remains explicitly deferred to the dedicated release-hardening
  pass, per product decision; it is not counted as beta-ready evidence.

## Release truth

R15.2 restores the established R14 package identity so it is an in-place update
that retains existing app data. It also derives every content-provider authority
from that application ID. R15 changed the package identity and R15.1 therefore
installed as a second app instead of updating R14.

The R15.2 artifact is a debug-signed alpha package
(`org.oxycblt.auxio.debug`, version code 85), not a production-signed beta. Do
not label it beta-complete until the device matrix in
`DEVICE_TEST_HANDOFF.md` is recorded and passes. Artifact:
`Shippy-Alpha-R15.2-20260802.apk` (61,479,890 bytes; SHA-256
`2C7BDE7C9024A213BC40DEBDEB9B850236123ADC1136C358CA3FF9D6F1D47A66`).

## Next gate

Install R15.2 and run the physical acceptance matrix. Any device failure reopens
the relevant gap; percentages are intentionally omitted because they previously
obscured missing vertical evidence.
