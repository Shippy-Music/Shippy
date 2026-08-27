<p align="center">
  <img src="docs/assets/shippy-logo.webp" width="144" alt="Shippy earbuds logo">
</p>

<h1 align="center">Shippy</h1>
<p align="center"><strong>Your music, one place.</strong></p>
<p align="center">Local files, downloads, online sources, lyrics, Last.fm, and shared listening in one native Android app.</p>

<p align="center">
  <a href="https://github.com/Shippy-Music/Shippy/releases/tag/v0.1.0-beta.1-r16"><img alt="R16 Beta 1" src="https://img.shields.io/badge/release-R16%20Beta%201-39d3ea"></a>
  <a href="LICENSE"><img alt="GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-39d3ea"></a>
  <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-39d3ea">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-native-39d3ea">
</p>

> [!IMPORTANT]
> **Shippy R16 is beta software.** Back up important playlists before testing.
> Provider APIs, device-specific media behavior, migration edge cases, and Crew
> still need feedback across more phones and networks.

## Meet Shippy

Shippy is an Android-native, open-source music player built around a simple
idea: your music should feel like one library, regardless of where it came from.

- Play music stored on your phone.
- Search supported online providers without leaving the app.
- Keep local, downloaded, and online tracks together in playlists.
- Browse Liked, Downloads, and Local as first-class collections.
- Use a persistent mini-player, full player, queue, synchronized lyrics, sleep
  timer, ReplayGain, gapless playback, crossfade, widgets, Android Auto, and a
  Quick Settings tile.
- Connect Last.fm for recommendations and durable scrobbling.
- Start a Crew for shared queues, synchronized playback, reactions, QR joining,
  LAN discovery, relay signaling, and temporary Push/Pull media.

Shippy keeps the clean native-Android character inherited from Auxio while
building a broader library, source, download, playback, and collaboration model.

## What R16 changes

R16 is the largest Shippy rebuild so far. It introduces canonical recording and
source identities, a resumable migration path, bounded Room-backed library
reads, deterministic queue entries, safer downloads and streaming cache,
offline-aware playback, backup foundations, listening history, Last.fm outbox
delivery, and performance tooling for large libraries.

| Area | Beta state |
|---|---|
| Local library, collections, and playback | Implemented; focused regressions covered |
| Mixed playlists, queue identity, and artwork | Implemented; wider device feedback wanted |
| Search and online playback | Implemented; external providers may drift |
| Downloads and offline behavior | Implemented; storage-provider edge cases remain in testing |
| Lyrics, Last.fm, widgets, and system playback | Implemented; device acceptance continues |
| Crew | Foundation implemented; broader multi-phone/network testing remains |
| R15-to-R16 migration | Resumable foundation implemented; real-world fixture coverage remains important |

The engineering boundary is tracked in [R16 status](docs/r16/STATUS.md),
[known issues](docs/r16/KNOWN_ISSUES.md), and the
[R16 architecture specification](docs/Shippy_R16_Master_Architecture_and_Implementation_Spec.md).

## Install R16 Beta 1

Download `Shippy-R16-Beta-1.apk` from the
[R16 Beta 1 release](https://github.com/Shippy-Music/Shippy/releases/tag/v0.1.0-beta.1-r16).

Requirements:

- Android 7.0 (API 24) or newer
- Permission to install apps from your browser or file manager

R16 uses the application ID `com.rtx09x.shippy`. It installs separately from
legacy Shippy Alpha/Auxio package identities. This beta APK is test-signed;
Android may require a reinstall if a future build uses a different signing key.

## Send useful feedback

Open a [bug report](https://github.com/Shippy-Music/Shippy/issues/new) with:

- phone model and Android version;
- the exact actions that triggered the issue;
- whether the track was local, downloaded, or streamed;
- a screenshot, screen recording, or redacted logcat when possible.

Please never attach private Last.fm credentials, personal library databases, or
unredacted file paths.

## Build from source

```bash
git clone --recurse-submodules https://github.com/Shippy-Music/Shippy.git
cd Shippy
./gradlew app:testDebugUnitTest musikr:testDebugUnitTest
./gradlew app:assembleDebug
```

Development requirements:

- JDK 21
- Android SDK 36
- Android NDK `28.2.13676358`
- CMake, Ninja, and Git Bash/`sh` on Windows

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Additional
verification and performance commands are documented in
[R16 performance](docs/r16/PERFORMANCE.md).

## Contributing

Read the [contribution guide](.github/CONTRIBUTING.md), then open an issue or
pull request. Small, focused changes with clear reproduction and verification
evidence are easiest to review.

## Credits and license

Shippy is maintained by **Rudra Tiwari** and the Shippy Music community.

The project is derived from [Auxio](https://github.com/OxygenCobalt/Auxio) and
retains its copyright and attribution. Bloomee informed parts of Shippy's
provider and product direction; Shippy's Android implementation is native
Kotlin. Third-party components retain their own notices and licenses.

Shippy is free software licensed under the
[GNU General Public License, version 3 or (at your option) any later version](LICENSE).
