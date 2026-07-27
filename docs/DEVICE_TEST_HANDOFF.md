# Shippy Owner Build And Device Handoff

The first full Windows build and automated verification pass is complete.
Physical-device verification remains the owner's next step.

## 1. Supported Build Environment

Native Windows, WSL2 Ubuntu, native Linux, and the existing GitHub Actions
Ubuntu workflow are supported. On Windows, Git Bash supplies `sh`; the native
bootstrap scripts normalize paths and use Ninja.

Required baseline:

- Recursive Git submodules
- JDK 17+ to launch Gradle; the configured Java toolchain supplies JDK 21
- Android SDK/target 36
- Android NDK `28.2.13676358`
- CMake and `ninja-build`
- One or more Android API 24+ devices; recent Android is preferred for the
  Quick Settings, widget, MediaSession, and background checks

## 2. Build And Install

From the repository root:

```bash
git submodule update --init --recursive
chmod +x gradlew
./gradlew spotlessCheck
./gradlew app:testDebugUnitTest musikr:testDebugUnitTest app:lintDebug
./gradlew app:assembleDebug
(cd relay && npm test)
```

Expected APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Internal r10 artifact (superseded by Alpha Release 1):

```text
Drive: gdrive:Shippy Builds/Shippy-debug-4.1.3-20260727-r10.apk
Size: 66,885,204 bytes
SHA-256: 7264BD1B21DC627361836F001E20AC75E6F6760B2D145EC428B2974D79262162
MD5: AD36C7FD31FBED9B023DDD1B14F3DEED
Package: org.oxycblt.auxio.debug
Label: Shippy Debug
Minimum Android: API 24
```

The public replacement is `Shippy-Alpha-Release-1.apk`, version
`0.1.0-alpha.1` (version code 74), package `org.oxycblt.auxio.debug`, label
`Shippy Alpha`.

```text
Size: 61,015,449 bytes
SHA-256: 82C83CA5C08F2485BBEE04F257F77C7B9274D80FD3A184DD1E285D3E6B62850E
MD5: 243B69968BA227095A0A4B707A2A6CBB
Signature: APK Signature Scheme v2, Android alpha/debug certificate
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

1. Open Shippy and repeatedly switch between Home, Search, Library, and Crew,
   including Library -> Home with the mini-player visible. Confirm every tab
   opens without closing the app.
2. With the mini-player visible, scroll Home and Crew to their final controls.
   Confirm no content is hidden behind the mini-player or primary navigation.
3. Confirm the Home title and toolbar sit below the status bar, then open
   Settings from the Home top-right gear. Leave Settings with Back, reopen it,
   and confirm neither transition terminates the app.
4. Expand the full player and confirm Queue is absent while closed. Open Queue
   through its explicit action, close it, and confirm it never overlaps player
   controls.
5. Save a local and a provider track, open Liked, and confirm both visible song
   rows match its count and can start playback.
6. Select Local folders, rescan, and play MP3, M4A, WAV, FLAC, Ogg/Opus, and AAC
   samples available on the device.
7. Confirm mini-player persistence; tap and upward-swipe expansion; downward
   swipe collapse from the artwork, track identity, and the lyrics-at-top
   regions; and Back collapse. With the player scrolled down, confirm content
   scrolls to the top before the sheet collapses.
8. Create, rename, pin, reorder, and delete a user playlist. Confirm Liked,
   Downloads, and Local cannot be deleted.
9. Switch Search between JioSaavn, YouTube, and YouTube Music; play a track
   from each available source plus a JioSaavn album, playlist, and artist.
10. Set a download destination, download a provider track, disable networking,
   and play it from its original context and Downloads.
11. Verify queue replace, Play next, Add to queue, reorder, remove, shuffle,
   repeat, and process-restart restoration. Open Queue from a long playlist and
   confirm it lands on the currently playing item. Confirm Shuffle retains the
   neighboring spring/bounce seen on Repeat without hitching or delaying its
   visible state.
12. Check the three-dot action surface from Search, Home, the full player,
    Library Songs/Downloads, and Crew. Confirm it opens immediately for the
    selected track in every context and never appears after switching tabs.
13. Check save/playlist membership, sharing, Song Information, lyrics, sleep
   timer, ReplayGain, gapless, and several crossfade durations.
14. Confirm the Now Playing title-row plus/tick edits saved destinations; the
    compact identity artwork is correct; Sleep Timer, conditional Download, and
    Queue work; and top-right overflow contains the consolidated player actions.
    Scroll to the synced lyrics preview, open the full lyrics sheet, seek, and
    confirm both surfaces stay on one timeline. Confirm the active lyric is
    larger/brighter, inactive lines are matte, overflow opens immediately, and
    Local tracks do not show Download.
15. Change streaming and download quality independently, then verify a new
   stream and download still resolve on every enabled provider.
16. Connect Last.fm, scrobble an eligible listen, restart the app, and verify Home
   shows the same account's cached/live activity without exposing credentials.
17. Verify notification, lock screen, headset/Bluetooth, audio focus,
   becoming-noisy pause, home-screen widgets, Quick Settings playback tile,
   and Android Auto where available.

## 4. Crew LAN Acceptance

Use two phones first, then three.

1. Start a Crew and join through Google's native QR scanner, then repeat
   through pasted link. Confirm Shippy never requests camera permission.
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

- Installation and behavior on physical Android devices
- Codec/device-specific audio behavior and real crossfade/gapless quality
- Live provider longevity and undocumented response drift
- SAF grant/revocation behavior across OEMs
- Real WebRTC NAT traversal, coturn allocation, multi-phone synchronization,
  renewable rejoin, and temporary-media throughput
- Accessibility release pass: TalkBack, large text, focus order, contrast,
  reduced motion, and accessible queue/Crew alternatives
