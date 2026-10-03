# LT06B — Integrate stable lyrics preview and full lyrics UI

**Parent packet:** `WP06`  
**Execution wave:** 3  
**Dependencies:** `LT06A`  
**Luna ownership:** R16 Now Playing lyrics presentation, full sheet/screen and accessibility tests.  
**Terra-owned/shared seams:** Terra owns shared Now Playing layout/theming and shell navigation with WP08.

## Assignment

Now Playing shows a stable lyrics preview and accessible full lyrics surface tied to the exact current queue occurrence, with synced follow, manual scrolling and offline/error states.

## Why this task exists in the current worktree

The legacy player has lyrics UI and the original bug report requires fixed/bounded preview size, integrated album-aware presentation, smooth full lyrics, and no layout jumping. The R16 Now Playing surface currently has no complete canonical lyrics experience.

## Locked decisions

- Preview height is fixed/predictably bounded.
- Lyrics identity follows QueueEntryId/generation, not title string.
- Album-derived colors preserve contrast.
- User scroll is not fought by auto-follow.
- Reduced motion/accessibility supported.

## Explicit non-goals

- Do not turn lyrics into a full screen that blocks basic playback controls.
- Do not resize the entire player for line count.
- Do not duplicate repository state in Fragment.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/playback/ui/R16NowPlayingFragment.kt`
- `app/src/main/res/layout/fragment_r16_now_playing.xml`
- `app/src/main/java/org/oxycblt/auxio/playback/LyricsDialog.kt`
- `app/src/main/java/org/oxycblt/auxio/playback/ArtworkToneExtractor.kt`
- `app/src/main/java/org/oxycblt/auxio/ui/ViewBindingBottomSheetDialogFragment.kt`
- `app/src/main/res/values/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Preview component

Fixed/bounded card/region with current/next or meaningful unsynced excerpt, loading skeleton, unavailable/failure/retry. Surrounding controls do not jump.

### 2. Full lyrics

Use bottom sheet/full surface according to existing shell; large readable text, synced highlighting/scroll, user-scroll pause/resume, seek-on-line only if reliable and accessible.

### 3. Theming

Use restrained artwork palette/surface tones, safe light/dark contrast and fallback. Keep lyrics/player visually one composition.

### 4. Lifecycle

Collect coordinator state at STARTED, preserve manual scroll where sensible, cancel stale animations, handle rotation/process restoration.

### 5. Accessibility

Semantic heading/current line, TalkBack order, large text, non-color state, reduced motion.

## Edge cases that must be handled

- very long/short lines
- no synced timing
- orientation/large text
- rapid skip
- lyrics disappear after metadata edit
- dark/light low contrast
- screen reader

## Verification contract

- UI state mapper tests.
- Layout/resource regression test for bounded preview.
- Coordinator identity integration tests.
- Instrumentation/manual accessibility and rapid-skip journeys near beta.

## Completion contract

Lyrics feel integrated, remain stable, and never show another track’s text or disturb player layout.

## Return to Terra instead of improvising when

- A major visual choice conflicts with owner-controlled design direction.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
