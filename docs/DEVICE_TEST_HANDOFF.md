# Shippy Owner Build And Device Handoff

This is the first full-build and physical-device path. The implementation pass
intentionally did not run Gradle or build an APK on the Windows development PC.

## 1. Supported Build Environment

Use WSL2 Ubuntu, native Linux, or the existing GitHub Actions Ubuntu workflow.
Auxio's custom Media3 and TagLib tasks invoke Unix tooling and do not support a
native Windows build.

Required baseline:

- Recursive Git submodules
- JDK 21 for the app build
- Android SDK/target 36
- Android NDK `28.2.13676358`
- CMake and `ninja-build`
- One or more Android API 24+ devices; recent Android is preferred for the
  Quick Settings, widget, MediaSession, and background checks

## 2. Build And Install

From the repository root in Linux/WSL:

```bash
git submodule update --init --recursive
chmod +x gradlew
./gradlew spotlessCheck
./gradlew app:testDebugUnitTest musikr:testDebug
./gradlew app:packageDebug
(cd relay && npm test)
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install through either Android Studio or:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If generated Hilt, Room, Safe Args, ViewBinding, or Kotlin compilation fails,
record the first real compiler error before changing architecture. Do not
attribute an upstream native-tool failure to Shippy source.

## 3. Single-Phone Acceptance

1. Open Shippy and verify exactly Home, Search, Library, and Crew.
2. Select Local folders, rescan, and play MP3, M4A, WAV, FLAC, Ogg/Opus, and AAC
   samples available on the device.
3. Confirm mini-player persistence, full-player expansion, and Back collapse.
4. Create, rename, pin, reorder, and delete a user playlist. Confirm Liked,
   Downloads, and Local cannot be deleted.
5. Search JioSaavn and YouTube Music; play a track and a JioSaavn album,
   playlist, and artist result.
6. Set a download destination, download a provider track, disable networking,
   and play it from its original context and Downloads.
7. Verify queue replace, Play next, Add to queue, reorder, remove, shuffle,
   repeat, and process-restart restoration.
8. Check save/playlist membership, sharing, Song Information, lyrics, sleep
   timer, ReplayGain, gapless, and several crossfade durations.
9. Connect Last.fm, scrobble an eligible listen, restart the app, and verify Home
   shows the same account's cached/live activity without exposing credentials.
10. Verify notification, lock screen, headset/Bluetooth, audio focus,
    becoming-noisy pause, home-screen widgets, Quick Settings playback tile,
    and Android Auto where available.

## 4. Crew LAN Acceptance

Use two phones first, then three.

1. Start a Crew, join through QR, and repeat through pasted link.
2. Disconnect internet while retaining the LAN; confirm session controls and
   Local playback continue.
3. From every phone, play/pause, seek, skip, replace the playlist, add, move, and
   remove queue items.
4. Confirm all devices show the same queue occurrence, contributor, repeat,
   shuffle, artwork, lyrics, and current position.
5. Send reactions from every member and verify they remain ephemeral.
6. With Push & Pull off, queue a unique Local track and verify the enable prompt.
7. Enable Push & Pull only for the active Crew. Queue a unique Local track from
   a non-coordinator phone and confirm prefetch, temporary playback, and no
   permanent Downloads relationship.
8. Explicitly Download that temporary track and confirm it survives Crew end.
9. Drop a supplier and then the coordinator. Confirm bounded recovery,
   deterministic handoff where quorum exists, and safe pause for a two-member
   split.
10. Background, foreground, rotate, change Wi-Fi, and kill/restart one joined
    process. Confirm renewable rejoin restores the same session without the
    expired public QR invite.

## 5. Remote And Relay Acceptance

1. Configure the self-hosted `relay/` HTTPS/WebSocket service and, when desired,
   an operator-owned coturn service using `relay/README.md`.
2. Place phones behind different networks/NATs.
3. Verify hosted signaling, direct ICE where possible, and TURN fallback where
   direct connectivity fails.
4. Confirm Push & Pull still carries only active-Crew temporary media.
5. Interrupt the host WebSocket briefly and verify the bounded host-resume path.
6. Verify a stale/rotated resume credential, expired invitation, wrong member,
   and wrong Crew cannot reconnect.
7. Repeat with the supported maximum of eight members if enough devices or
   controlled clients are available; watch phone memory, battery, and upload
   pressure.

## 6. Evidence To Record

Record:

- Commit SHA and build environment
- Device model, Android version, and network topology
- Build/test commands and complete first failure
- Provider and relay configuration without secrets
- Screen recording for the core player and Crew flows
- Drift/buffering observations for LAN and remote Crew
- Any mismatch between app, notification, widget, Quick Settings, and Auto

## 7. Deliberately Unverified In This Pass

- APK compilation, generated Android code, and installation
- Codec/device-specific audio behavior and real crossfade/gapless quality
- Live provider longevity and undocumented response drift
- SAF grant/revocation behavior across OEMs
- Real WebRTC NAT traversal, coturn allocation, multi-phone synchronization,
  renewable rejoin, and temporary-media throughput
- Accessibility release pass: TalkBack, large text, focus order, contrast,
  reduced motion, and accessible queue/Crew alternatives
