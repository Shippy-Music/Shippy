<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" alt="Shippy icon">
</p>

<h1 align="center">Shippy</h1>
<p align="center"><strong>Your music, one place — local, downloaded, streamed, and shared.</strong></p>

<p align="center">
  <a href="https://github.com/Shippy-Music/Shippy/releases/tag/v0.1.0-alpha.1"><img alt="Alpha Release 1" src="https://img.shields.io/badge/release-Alpha%201-376bda"></a>
  <a href="LICENSE"><img alt="GNU GPL v3 or later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-376bda"></a>
  <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-376bda">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-native-376bda">
</p>

> [!WARNING]
> **Shippy is alpha software.** Core playback and automated checks pass, but
> provider APIs can change and Crew still needs broader real-device,
> multi-phone, and network testing. Expect bugs and keep a backup of important
> playlists.

## What Shippy is

Shippy is an Android-native, open-source music app built around one idea:
**music is music; its source is an implementation detail.**

- Play and index music already on your phone.
- Search JioSaavn, YouTube, and YouTube Music from one surface.
- Save provider tracks and mix local, downloaded, and online music in playlists.
- Use Liked, Downloads, and Local as built-in collections.
- Get a persistent mini-player, full player, queue, synced lyrics, sleep timer,
  ReplayGain, gapless playback, crossfade, widgets, Android Auto, and a Quick
  Settings tile.
- Start a **Crew** for shared queues, synchronized playback, reactions,
  QR joining, LAN discovery, remote relay signaling, and temporary active-Crew
  Push/Pull media.

The interface keeps Auxio's clear, native Android foundation while adding a
Spotify-like library, provider, download, player, and collaborative-listening
model.

## Install Alpha Release 1

Download `Shippy-Alpha-Release-1.apk` from
[Alpha Release 1](https://github.com/Shippy-Music/Shippy/releases/tag/v0.1.0-alpha.1).

New development builds use the stable Shippy package `org.shippymusic.shippy.debug` and require
Android 7.0 (API 24) or newer. Published alpha APKs are signed for testing. Android may ask you to allow installs
from your browser or file manager.

## Alpha status

| Area | Current state |
|---|---|
| Local library and playback | Implemented; owner-device tested |
| Search and provider playback | Implemented; external APIs may drift |
| Downloads and mixed playlists | Implemented; device edge cases still being tested |
| Player, queue, lyrics, widgets | Implemented; visual/runtime refinement continues |
| Crew LAN and remote foundations | Implemented and unit-tested; wider multi-device testing pending |
| Accessibility audit | Planned before stable release |

See [live status](docs/STATUS.md), [device test handoff](docs/DEVICE_TEST_HANDOFF.md),
and the [product specification](docs/PRODUCT_SPEC.md) for the honest engineering
boundary.

## Build

Clone recursively:

```bash
git clone --recurse-submodules https://github.com/Shippy-Music/Shippy.git
cd Shippy
```

Requirements:

- JDK 21
- Android SDK 36
- Android NDK `28.2.13676358`
- CMake, Ninja, and Git Bash/`sh` on Windows

Verify and assemble:

```bash
./gradlew spotlessCheck
./gradlew app:testDebugUnitTest musikr:testDebugUnitTest app:lintDebug
./gradlew app:assembleDebug
(cd relay && npm test)
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Contributing

Alpha feedback is especially useful when it includes the Android version,
device model, exact steps, and a logcat or screen recording. Read the
[contribution guide](.github/CONTRIBUTING.md), then open an
[issue](https://github.com/Shippy-Music/Shippy/issues) or pull request.

## Credits and license

Shippy is maintained by **Rudra Tiwari** and the Shippy Music community.

The project is derived from [Auxio](https://github.com/OxygenCobalt/Auxio) and
retains its copyright and attribution. Bloomee informed parts of Shippy's
provider and product direction; Shippy's Android implementation is native
Kotlin. Third-party components retain their own notices and licenses.

Shippy is free software licensed under the
[GNU General Public License, version 3 or (at your option) any later version](LICENSE).
