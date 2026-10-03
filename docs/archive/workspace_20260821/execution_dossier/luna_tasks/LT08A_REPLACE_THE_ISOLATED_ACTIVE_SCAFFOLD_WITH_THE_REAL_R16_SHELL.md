# LT08A — Replace the isolated ACTIVE scaffold with the real R16 shell

**Parent packet:** `WP08`  
**Execution wave:** 4  
**Dependencies:** `LT04A`, `LT04B`, `LT04C`, `LT05B`, `LT06B`  
**Luna ownership:** R16 main shell/navigation/back/state restoration and resources.  
**Terra-owned/shared seams:** Terra owns MainActivity, outer/inner navigation, manifest and shared shell resources.

## Assignment

One production-quality Views/Fragments shell hosts all R16 surfaces, preserves navigation/scroll/search state, transitions mini/full player coherently, and reuses proven Auxio UI infrastructure behind Shippy-owned navigation/state.

## Why this task exists in the current worktree

`activity_r16_active.xml` is an isolated engineering host with a horizontal strip of buttons and fragment container. R16 product surfaces exist, but they are not yet composed into a daily-driver shell with Home/Search/Library/Crew, mini/full player, settings/drawer and correct back hierarchy.

## Locked decisions

- No screen owns playback state.
- Home/Search/Library/Crew primary destinations are stable.
- Library contextual search stays in Library.
- Back hierarchy follows master spec.
- Mini player and Now Playing are one connected surface.
- No screen-specific controls leak through shared layouts.

## Explicit non-goals

- Do not rewrite the app in Compose.
- Do not redesign owner-controlled visual language wholesale.
- Do not activate durable R16 authority in this task.

## Start with these repository surfaces

- `app/src/main/res/layout/activity_r16_active.xml`
- `app/src/main/java/org/oxycblt/auxio/MainActivity.kt`
- `app/src/main/res/navigation/outer.xml`
- `app/src/main/res/navigation/inner.xml`
- `app/src/main/java/org/oxycblt/auxio/home/HomeFragment.kt`
- `app/src/main/java/org/oxycblt/auxio/playback/PlaybackBottomSheetBehavior.kt`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/home/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/library/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/search/`
- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/`
- `app/src/main/java/org/oxycblt/auxio/shippy/crew/ui/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Define shell state

Primary destination, nested detail stack, Library tab/order, search state, mini/full player expansion, sheet/dialog overlay and settings/drawer. Use lifecycle-safe saved state, not duplicate domain state.

### 2. Compose navigation

Bottom navigation or existing equivalent for Home/Search/Library/Crew; Library tabs include Songs/Playlists/Albums/Artists/Genres per settings; detail routes carry typed encoded IDs.

### 3. Player surface

Embed mini player persistently when active, expand/collapse to full Now Playing with smooth shared transition and correct insets. Back collapses player before navigating away.

### 4. Drawer/settings

Keep drawer for navigation-level Settings/About only; contextual actions use sheets. Match Shippy typography/spacing/theme and correct light/dark icon contrast.

### 5. Restore

Process/config restoration preserves destination/detail/search/tab/scroll as appropriate without restoring stale playback copies.

### 6. Remove scaffold

Delete temporary ACTIVE buttons/host code only after all routes are reachable through the shell.

## Edge cases that must be handled

- cold start no music
- player active/no player
- deep link/detail
- rotation/multi-window
- sheet open/back
- large text
- Library tab hidden
- Crew unavailable/experimental

## Verification contract

- Navigation/state restoration tests.
- Layout/resource checks for no control leakage.
- Instrumentation smoke for primary/detail/player/back routes.
- No durable activation change.

## Completion contract

The isolated R16 composition looks and behaves like one coherent Shippy app shell, with every implemented product route reachable and state-safe.

## Return to Terra instead of improvising when

- Owner must choose a major navigation/visual behavior not covered by master design.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
