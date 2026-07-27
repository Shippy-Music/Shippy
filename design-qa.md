# Shippy r9 Design QA

Source visuals: owner-provided Spotify Now Playing and full-lyrics screenshots,
plus the Spotify playlist-detail screenshot, 2026-07-27.

Static checks completed:

- portrait hierarchy follows the selected target
- title-row artwork/save state, Sleep Timer, conditional Download, Queue,
  top-right overflow, playback controls, and lyrics retain working code paths
- full lyrics has matte inactive lines, a larger active line, native edge fade,
  Download, and direct consolidated overflow
- the full-player root observes downward swipes before nested children consume
  them, without stealing their touch streams; lyrics scrolling retains priority
  until it reaches the top
- permanent collections and user playlists share one artwork-led header and
  full-width virtualized track list
- Android resource compilation, ViewBinding generation, lint, and APK assembly pass
- no connected Android device or accelerated emulator is available locally

Blocking visual check:

- capture r9 on the owner's phone at the same Now Playing/full-lyrics and
  playlist-detail states
- compare spacing, crop, track thumbnail, control hierarchy, lyrics fold/fade,
  swipe-down collapse, and animation timing against the source screenshots

final result: blocked
