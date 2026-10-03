# WP08 — Shell, M3 Expressive, QoL, and Accessibility

**Priority:** P1 before beta  
**Primary owner:** Terra: UI integration owner  
**Dependencies:** WP02, WP03, WP04, WP05, WP06  
**Parallel safety:** Parallel UI workers only on independent screens/resources; shell/navigation/common styles remain Terra-owned.  
**Required contracts:** `contracts/C01_LOCKED_INVARIANTS.md`, `contracts/C03_PLAYBACK.md`, `contracts/C07_PERFORMANCE_UX_RELEASE.md`

## Mission

Replace the isolated R16 scaffolding host with a fidelity-equivalent Shippy product shell and finish restrained M3 Expressive, interaction consistency, adaptive layout, and accessibility.

## Current repository state

R16 surfaces exist, but the isolated `activity_r16_active.xml` uses a horizontal button strip and FragmentContainer scaffolding. The original Auxio/Shippy shell has the desired navigation rhythm, mini/full-player choreography, theme, rows, drawers/sheets, and back behavior. R16 UI is functional but incomplete and not release-polished.

## Target outcome

R16 runs through the real Shippy shell; Home/Search/Library/Crew navigation, mini/full player, sheets, drawers, dynamic/album-derived color, state restoration, and accessibility feel coherent and remain recognizable as Shippy.

## Locked packet invariants

- UI observes R16 state; it never invents playback/data authority.
- Preserve original visual language and interaction rhythm.
- Views/Fragments remain; no Compose rewrite.
- Screen-specific controls cannot leak into shared layouts.
- Bottom sheets are keyed to captured identity.
- Readability/accessibility overrides decorative color/motion.

## Non-goals

Do not redesign the brand, typography, or navigation from scratch. Do not apply M3 Expressive to every component. Do not delay core behavior behind animation. Do not preserve legacy Kotlin merely for visual fidelity.

## Current code map

- `MainActivity`, `MainFragment`, Home/Navigation fragments/resources (Terra-only shell edits)
- isolated R16 active layout and all R16 UI fragments/layouts/adapters
- retained bottom sheet/drawer/player components and design tokens
- app theme/styles/colors/dimens/animations
- settings, accessibility, adaptive resources
- UI/instrumentation/screenshot tests

## Implementation work

### A. Real shell integration

Host R16 destinations inside the established four-destination Home/Search/Library/Crew shell. Preserve destination state/scroll, mini-player insets, full-player collapse/Back hierarchy, drawer/settings entry, predictive/back behavior where supported, and deep-link/system navigation. Delete the temporary button-strip host after replacement is proven.

### B. Common interaction components

Create/reuse one Shippy bottom-sheet style/host for song actions, playlist actions, sort/filter, save destinations, source info, cache/download options. Standardize handle, corners, insets, width, nested scroll, loading/disabled/error, identity header, result contracts, and accessibility title. Use anchored menus only for small immediate choices.

### C. Now Playing and palette

Preserve artwork-led hierarchy, stable seek/control layout, fixed lyrics preview, queue/sleep/download/save actions, and mini/full transition. Derive restrained palette/accent/tonal surfaces from artwork with bounded cache and contrast fallback. Avoid flashy gradients; use subtle tint/matte/translucency only where legibility remains strong.

### D. Home/Library/product polish

Compact horizontal Continue/Recent/Recommendations, no redundant Your Music/Recent Downloads dump, contextual search, correct long-press/sort/edit-order scope, stable loading/empty/error states, touch targets, and perceived-performance transitions.

### E. M3 Expressive

Adopt practical View-compatible M3 Expressive component sizing, shapes, state transitions, and motion selectively. Respect reduced motion and older Android behavior. Keep dependency choices compatible with existing backports/pins.

### F. Accessibility/adaptive

TalkBack labels/state announcements, focus order, 48dp targets, large text, contrast, non-drag reorder alternatives, keyboard/IME, RTL resilience, small phones, landscape/multi-window, and non-broken tablet layout. Bespoke tablet redesign is not required by default.

### G. State restoration

Search queries, selected tab, sort, sheet operation identity, scroll position, Now Playing expansion, and pending operations survive appropriate configuration/process boundaries without stale actions.

## Edge cases and failure behavior

- playback changes while action sheet open.
- configuration/process recreation.
- light/dark/dynamic palette contrast.
- huge font/TalkBack and long metadata.
- reduced motion.
- small phone/landscape/tablet.
- empty/loading/error and partial provider states.
- rapid Back/navigation/player transitions.

## Performance constraints

No palette extraction or artwork decoding on main thread. Avoid full-list transitions. Reuse Paging state. Motion stays within frame budgets and can be reduced. UI does not trigger repeated DB/network work on bind.

## Verification

Layout regression tests, UI state tests, accessibility scanner/manual TalkBack, large text, contrast, screenshot/device checks, navigation/restoration tests, frame timing, and UI-001–UI-014. Build/lint/resource checks after integration.

## Done means

R16 looks and behaves like a polished evolution of Shippy, not a temporary engineering shell; interactions are consistent, readable, responsive, accessible, and tied to canonical state.

## Escalate only when

A major visual product decision is not covered by the established UI-fidelity direction, or a dependency upgrade would require abandoning supported behavior.

## Luna handback

Report exact files changed, APIs/schema changed, focused commands/tests, unverified behavior, shared integration requested from Terra, and any packet assumption contradicted by the code.
