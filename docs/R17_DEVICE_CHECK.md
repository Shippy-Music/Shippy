# R17 installation check

Build: `0.1.0-beta.2-r17`, version code 88, package `com.rtx09x.shippy`.
The update preserves the installed runtime selection and introduces no database
migration. Do not uninstall the existing app to update it.

## Changes

- Search opens within legacy playlists and system collections; menu visibility
  and enabled states update correctly.
- Collection filtering stays in place, runs off the legacy UI thread, and uses
  debounced, collection-scoped queries in the R16 runtime. Filtered Play,
  Shuffle, and row selection use the same collection context.
- Provider sections can arrive independently. Library has its own search entry.
- Hidden R16 screens suspend UI observers and media connections. Player progress
  updates avoid repeated artwork requests. Home recent history observes changes.
- Detail navigation selects the visible origin and retains its view on Back.

## Check on the phone

1. Install over the previous build. Confirm your library, settings, playlists,
   and downloads are still present.
2. Open a playlist, Liked, Downloads, and Local. Search for part of a title or
   artist, clear it, and press Back. Confirm you remain in the collection.
3. With several filtered matches, try Play, Shuffle, and a specific row. Check
   the queue and Next/Previous stay within those matches.
4. Type quickly in a large collection, scroll, switch tabs, and reopen the
   player. Compare responsiveness with the prior build.
5. Play through a track, return Home, and verify recent listening updates.

Automated verification: 46 focused app/data tests and formatting checks passed.
Physical performance, live providers, and installation remain owner acceptance.
The owner authorized GitHub synchronization and R17 release publication on
2026-10-05. Physical acceptance remains pending.
