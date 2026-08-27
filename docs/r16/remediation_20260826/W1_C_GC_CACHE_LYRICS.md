# Wave 1C - Runtime Retention, Cache Lifecycle, and Lyrics Generation

## Outcome

Wire the existing GC/cache/lyrics primitives into production with the smallest bounded lifecycle.

## Required behavior

1. Catalogue GC must not delete recordings referenced by the live queue, active playback/cache
   lease, or pending durable work. Prefer a conservative defer/skip when runtime protection cannot
   be captured safely; do not create a new process-wide state framework.
2. Wire bounded cache maintenance to real production lifecycle: configured size budget, optional
   age cleanup, free-space floor, displayed size, and Clear Cache.
3. Active playback protects its exact cache key until the player releases/switches it.
4. Clear/maintenance must not remove protected active bytes; downloads and local files are never
   affected.
5. Pass the real `PlaybackSnapshot.generation` through production lyrics ViewModel, Now Playing,
   and lyrics sheet calls. Remove reliance on the coordinator's default `0` in production.
6. Preserve the fixed retry behavior: a failed load does not retry on every render; explicit
   retry/refresh remains the only same-token retry.

## Owned files

- `app/.../r16/maintenance/CatalogueGcWorker.kt` and its focused tests
- `app/.../shippy/media/cache/**` and cache settings/resources directly required
- cache integration call sites that own playback acquire/release only
- `app/.../r16/playback/ui/R16LyricsViewModel.kt`
- `R16NowPlayingFragment.kt` and `R16LyricsBottomSheetFragment.kt` only for generation plumbing
- focused cache/GC/lyrics tests

Do not change playback commands, coordinator/reducer semantics, database schema, or navigation
transactions. Wave 2B owns navigation edits in the same fragments.

## Acceptance

- GC request contains or conservatively respects active runtime protections.
- Production callers exist for bounded age/free-space maintenance.
- Protected active cache content survives clear/maintenance until release.
- Two playback generations for the same Recording cannot display stale lyrics.
- Run only focused GC/cache/lyrics tests.
