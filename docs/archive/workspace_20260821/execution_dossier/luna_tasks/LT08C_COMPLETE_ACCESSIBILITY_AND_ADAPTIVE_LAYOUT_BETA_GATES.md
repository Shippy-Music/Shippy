# LT08C — Complete accessibility and adaptive layout beta gates

**Parent packet:** `WP08`  
**Execution wave:** 5  
**Dependencies:** `LT08A`  
**Luna ownership:** Accessibility semantics, keyboard/large-text/reorder alternatives and adaptive layout fixes.  
**Terra-owned/shared seams:** Terra owns broad shell/layout changes and coordinates visual fixes with LT08B.

## Assignment

Core journeys work with TalkBack, large text, keyboard/switch navigation, reduced motion, small/large screens, orientation and multi-window without clipped/hidden critical controls.

## Why this task exists in the current worktree

Accessibility and adaptive layout are final beta requirements, not yet proven. New R16 lists, player, sheets, queue/reorder and status indicators need a systematic pass rather than one-off content descriptions.

## Locked decisions

- 48dp practical touch targets.
- Meaningful labels/state/action, not filename/class names.
- Reorder has non-drag alternative.
- Status not color-only.
- Large text may reflow/scroll; it must not overlap or disappear.
- Responsive correctness is required; bespoke tablet redesign is not.

## Explicit non-goals

- Do not change product behavior to satisfy a screenshot.
- Do not disable dynamic type.
- Do not claim accessibility from static lint alone.

## Start with these repository surfaces

- `app/src/main/java/org/oxycblt/auxio/shippy/r16/`
- `app/src/main/res/layout/fragment_r16_*.xml`
- `app/src/main/res/layout/item_r16_*.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/styles_ui.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/java/org/oxycblt/auxio/ui/`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Inventory journeys

Home, all searches, Library tabs/details, Save sheet, queue/reorder, Now Playing/lyrics, downloads, Identify/editor, migration/recovery, Last.fm/settings/Crew gates.

### 2. Semantics and focus

Set labels/headings/collection info/state descriptions/live regions where justified; logical focus order; preserve focus across Paging updates; keyboard/DPAD actions.

### 3. Large text/layout

Test font scales, narrow devices, landscape, split-screen and large screens. Replace fixed heights where inappropriate while keeping the intentionally bounded lyrics/player regions.

### 4. Reduced motion

Respect animator scale/reduced motion; provide nonanimated state correctness.

### 5. Accessible alternatives

Move up/down/to position for queue/playlist, explicit state for scrobble/download/like, retry labels, errors with actions.

### 6. Automated and physical checks

Lint/accessibility checks plus TalkBack/manual matrix; document device/OS/evidence honestly.

## Edge cases that must be handled

- RTL
- font scale 2.0
- screen reader during rapidly changing progress
- Paging inserts/removals
- modal sheet back/focus return
- external keyboard
- fold/multi-window

## Verification contract

- Accessibility lint and focused UI tests.
- Instrumentation focus/navigation checks.
- Manual TalkBack/large-text/reduced-motion evidence near beta.
- No clipped controls in target matrix.

## Completion contract

All core daily-driver journeys are perceivable, operable and understandable under the accessibility/adaptive matrix.

## Return to Terra instead of improvising when

- A required journey cannot be made accessible without a major owner-approved UX change.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
