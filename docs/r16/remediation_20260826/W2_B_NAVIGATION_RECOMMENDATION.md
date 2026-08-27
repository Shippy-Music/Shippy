# Wave 2B - Non-Destructive Detail Navigation and Last.fm Search Handoff

## Prerequisite

Wave 1B and Wave 1C are reviewed so their fragment edits are stable.

## Outcome

Preserve root/detail state and make Last.fm rows open a populated search while retaining the
original Auxio/Shippy visual feel.

## Required behavior

1. Keep the current add/show/hide root-tab model.
2. Replace destructive detail routes with one consistent non-destructive back-stack transaction
   that preserves the originating Fragment instance and list/scroll state.
3. Back closes sheet/detail, then collapses Now Playing, then navigates/finishes according to the
   existing Auxio hierarchy. Do not redesign the shell.
4. Last.fm recommendation/top-track tap forwards the actual title + artist into Global Search and
   executes that query; it must not open a blank screen.
5. Test the real production shell/navigation path, not a duplicated `TestShellActivity` algorithm.

## Owned files

- `MainActivity.kt` only if the shared navigation helper belongs there
- R16 Home/Library/detail/Mini Player/Now Playing navigation call sites
- `R16GlobalSearchFragment.kt` argument/query initialization
- real-shell navigation and recommendation handoff tests

Do not change layouts, theme tokens, typography, row styling, bottom-sheet styling, or player
choreography except where a proven navigation bug requires it.

## Acceptance

- Scroll a root list, open detail, Back: same root Fragment and exact scroll state remain.
- Open/collapse Now Playing without destroying the root/detail stack.
- Tap Last.fm row: Global Search contains and runs the row's title/artist query.
- Existing root-tab preservation remains green.
