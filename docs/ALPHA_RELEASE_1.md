# Shippy Alpha Release 1

This is Shippy's first public alpha and replaces the internal `r10` artifact.

## Verification

- Kotlin/Java compilation: passed
- JVM tests: 433 executed, 432 passed, 1 opt-in live provider smoke test skipped
- Spotless: passed
- Android lint: passed
- APK assembly: passed
- APK Signature Scheme v2 verification: passed
- Physical-device and multi-phone acceptance: still in progress

## Known alpha risks

- JioSaavn, YouTube, YouTube Music, LRCLIB, Musixmatch, and Last.fm behavior can
  change independently of Shippy.
- Crew has extensive deterministic coverage, but real-world LAN, NAT, relay,
  reconnect, and temporary-media behavior needs broader device testing.
- Accessibility is not yet release-complete.

Install only if you are comfortable testing prerelease software and reporting
reproducible failures.

## Artifact

```text
File: Shippy-Alpha-Release-1.apk
Package: org.oxycblt.auxio.debug
Version: 0.1.0-alpha.1 (74)
Label: Shippy Alpha
Size: 61,015,449 bytes
SHA-256: 82C83CA5C08F2485BBEE04F257F77C7B9274D80FD3A184DD1E285D3E6B62850E
MD5: 243B69968BA227095A0A4B707A2A6CBB
Signature: APK Signature Scheme v2, Android alpha/debug certificate
```
