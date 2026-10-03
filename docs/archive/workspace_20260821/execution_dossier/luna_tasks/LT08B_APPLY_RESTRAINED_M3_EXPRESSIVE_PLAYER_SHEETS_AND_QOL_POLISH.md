# LT08B — Apply restrained M3 Expressive player, sheets, and QoL polish

**Parent packet:** `WP08`  
**Execution wave:** 5  
**Dependencies:** `LT08A`, `LT06B`  
**Luna ownership:** R16 player/actions/sheets visual-interaction refinement and focused UI tests.  
**Terra-owned/shared seams:** Terra owns shared design tokens/styles and coordinates with accessibility task.

## Assignment

Now Playing, queue, Save Destinations, playlist/song actions and drawers feel like one polished Shippy system with subtle album-aware color, stable geometry and consistent motion.

## Why this task exists in the current worktree

The current R16 surfaces are functional engineering UI. The master direction is restrained Material 3 Expressive using the existing Material foundation: calm spacing, coherent sheets, album-derived accents, smooth transitions, truthful states and no flashy redesign.

## Locked decisions

- Content and contrast outrank decorative color.
- Sheets are the default mobile contextual action surface.
- Anchored popups remain for tiny local choices.
- Motion honors reduced-motion and cancellation.
- No state is communicated by color alone.

## Explicit non-goals

- Do not globally redesign typography/navigation.
- Do not add glassmorphism/RGB gradients.
- Do not sacrifice performance for palette animation.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/`
- `app/src/main/res/layout/fragment_r16_now_playing.xml`
- `app/src/main/res/layout/fragment_r16_queue.xml`
- `app/src/main/res/layout/fragment_r16_playlist_destination_picker.xml`
- `app/src/main/java/org/oxycblt/auxio/playback/ArtworkToneExtractor.kt`
- `app/src/main/java/org/oxycblt/auxio/ui/ExpressiveShapes.kt`
- `app/src/main/java/org/oxycblt/auxio/ui/Animations.kt`
- `app/src/main/res/values/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Design tokens

Centralize spacing, shapes, elevations, motion durations/easing and album surface roles using existing M3 attributes. Avoid per-screen magic values.

### 2. Album palette

Extract/cache palette asynchronously keyed to artwork; apply restrained surface/accent/gradient tint with contrast guard and stable fallback. Never block playback/UI on palette.

### 3. Now Playing

Balanced artwork, metadata, save state, seek/time, controls, utility actions, stable lyrics preview, queue access and retry/error. Remove awkward empty space and layout jumps.

### 4. Sheets/actions

One reusable sheet host for song/playlist/save/sort/download/identify actions; consistent header, selected state, drag/dismiss/back, loading/error and touch targets.

### 5. Motion

Mini↔full player, sheet, like/download and list updates use subtle M3 Expressive motion; cancel stale transitions and avoid animation during rapid identity changes.

### 6. Light/dark/dynamic color

Theme-aware icons including Settings, readable translucent/matte surfaces, system bars/insets and neutral fallback.

## Edge cases that must be handled

- artwork changes rapidly
- missing artwork
- very bright/dark art
- light/dark switch
- reduced motion
- large text
- landscape
- slow device

## Verification contract

- Palette/contrast unit tests where possible.
- Layout regression/resource tests.
- Screenshot/manual comparison with approved references.
- Frame timing measured in WP09.

## Completion contract

R16 retains its familiar Material character but feels calm, integrated and intentional; no known original UI regression remains.

## Return to Terra instead of improvising when

- A visual decision requires owner taste approval beyond restrained defaults.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
