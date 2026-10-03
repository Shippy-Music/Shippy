# Codex Cloud setup

Use `Shippy-Music/Shippy`, branch `main`, as the source repository. Shippy is
native Android/Kotlin; the old Flutter setup is historical.

## Environment

Use Linux with JDK 21, Git, Bash, CMake, Ninja, Make, a C/C++ toolchain, pkg-config,
and Android command-line tools. Allow setup access to GitHub, Android SDK
downloads, Google Maven, Maven Central, Gradle distributions/plugins, JitPack,
and any dependency hosts requested by Gradle. Initial setup needs network access.

With JDK 21 selected and `sdkmanager` on PATH, install the Android packages:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" \
  "ndk;28.2.13676358" "cmake;3.22.1"
sdkmanager --licenses
```

Accept the SDK licenses in the environment setup. Set `ANDROID_HOME` to the SDK
directory (and `ANDROID_SDK_ROOT` to the same directory if the environment uses
it). No committed `local.properties` is needed.

Before initializing nested submodules, route the legacy FFmpeg host to its
official GitHub mirror. This matches the existing GitHub Actions checkout:

```bash
git config --global url.https://github.com/FFmpeg/FFmpeg.git.insteadOf https://git.ffmpeg.org/ffmpeg.git
git submodule sync --recursive
git submodule update --init --recursive
git submodule status --recursive
```

Use the committed submodule SHAs, not `git submodule update --remote`. The media
fork contains Shippy build changes; upstream Auxio media is not interchangeable.
TagLib and its utfcpp dependency are built from pinned sources. Their ignored
`build/` and `pkg/` directories are generated on the first native build.

## Verification

The owner's PC defaults cap Kotlin at 1 GiB. August GitHub builds failed during
`:app:compileDebugKotlin` with `OutOfMemoryError: GC overhead limit exceeded`.
Use a 3 GiB Kotlin compiler heap on a cloud worker with sufficient RAM:

```bash
./gradlew -Pkotlin.daemon.jvmargs=-Xmx3072m spotlessCheck \
  :shippy-core:test :shippy-sources:test \
  :shippy-data:testDebugUnitTest :app:testDebugUnitTest :musikr:testDebugUnitTest
./gradlew -Pkotlin.daemon.jvmargs=-Xmx3072m :app:lintDebug :app:assembleDebug
```

Match checks to the change. Android module tests still configure the full
multi-project build and may compile native dependencies. Keep build caches.
The output APK is `app/build/outputs/apk/debug/app-debug.apk`. Signing credentials
are not required for debug builds; owner release signing is a separate handoff.

The relay requires Node.js 22 or newer and has its own lockfile and verification path:

```bash
cd relay
npm ci
npm test
```

Physical-phone acceptance, real provider/Last.fm behavior, and multi-phone Crew
remain separate from host tests. See `DEVICE_TEST_HANDOFF.md`,
`r16/KNOWN_ISSUES.md`, and `r16/PERFORMANCE.md`.

## What is intentionally outside Git

Gradle/Kotlin caches, native outputs, local SDK paths, APKs, personal databases,
account tokens, signing keys, and generated Graphify indexes are not source
dependencies. Published APKs belong to GitHub releases. Runtime credentials are
configured through the app or environment secrets as appropriate; do not commit
them. `rtk` and Graphify are optional local helpers, not build prerequisites.
