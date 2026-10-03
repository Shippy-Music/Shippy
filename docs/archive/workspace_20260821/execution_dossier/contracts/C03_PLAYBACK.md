# C03 — Playback Contract

## State authority

`PlaybackCoordinator` owns queue intent, base order, traversal order, selected/committed QueueEntryId, generation, revision, phase, repeat, shuffle, prepared sources, position anchor, failures, and listening-session identity.

Media3 receives a tagged transaction and reports observations. It does not author product queue identity.

## Context play

Every collection row play must preserve the intended context:

- playlist: exact PlaylistEntryId and current visible sort/filter order;
- Songs: complete lightweight current Songs order;
- artist/release/genre/system collection: complete lightweight canonical order;
- Global Search provider result: immediate single result is acceptable unless a defined result context exists;
- Continue Listening: exact restored occurrence/context when available.

Do not hydrate rich display models for entire queues.

## Source preparation

- rank verified local/download/cache/provider/Crew sources through one policy;
- prepare current and bounded neighbors only;
- refresh expiring provider locators near playback;
- carry headers/locators only in process-local registries;
- retry/fallback within the same Recording and generation;
- never substitute a metadata-similar Recording.

## System agreement

Now Playing, mini-player, queue, notification, widget, Android Auto/MediaBrowser, Last.fm, lyrics, and Crew must agree on QueueEntryId and RecordingId. All controls submit the same command types.

## Persistence

Checkpoint stores source-neutral intent: queue occurrences, origins, selected occurrence, base/traversal order or deterministic shuffle state, repeat, position, and metadata fallback needed for recovery. It never persists expiring private URLs or headers.
