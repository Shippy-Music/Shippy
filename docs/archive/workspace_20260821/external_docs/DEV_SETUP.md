# Dev setup (local machine)

> Current Shippy development uses `X:\piko\shippy` (Auxio/Kotlin). This file is
> preserved only to record the earlier Flutter/Bloomee setup.

Installed for the historical **Shippy** Bloomee attempt. No deploy.

## Toolchain

| Tool | Location / version |
|------|--------------------|
| Flutter | scoop `flutter` 3.44.6 stable → `~\scoop\apps\flutter\current` |
| Dart | 3.12.2 (bundled with Flutter) |
| JDK | scoop `temurin17-jdk` 17.0.19 → `JAVA_HOME` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` (`ANDROID_HOME`) |
| cmdline-tools | `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest` |
| adb | platform-tools |

User env: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME`, PATH includes flutter/java/adb.

## Windows note

Flutter plugin builds need **Developer Mode** (symlinks):

```text
start ms-settings:developers
```

Enable Developer Mode, then open a new terminal.

## Project deps

```powershell
cd X:\piko\bloomee
flutter pub get
```

Isar is `isar_community` from pub.dev (private isar host was unreliable).  
`youtube_explode_dart` is from pub.dev (old git fork 404).

## Phone

1. Developer options → USB debugging  
2. Plug in, accept prompt  
3. New terminal:

```powershell
cd X:\piko\bloomee
flutter devices
flutter run
```

Hot reload: `r` · Hot restart: `R`

## Product

- Display name: **Shippy**
- Tabs: Home · Search · Library · Crew Mode
- Internal Dart package name still `Bloomee` (Phase 1)
