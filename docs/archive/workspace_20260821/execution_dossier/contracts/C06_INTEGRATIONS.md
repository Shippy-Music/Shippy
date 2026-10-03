# C06 — Last.fm, Lyrics, and Crew Contract

## Last.fm

- Disconnected: no Home modules, status clutter, background requests, or new scrobble work.
- Connected: now-playing/scrobble/discovery/profile/history integration may appear.
- Eligibility uses monotonic audible time, not incidental player callbacks.
- Local/provider/download/cache/Crew sources can scrobble when canonical metadata is valid.
- Local play history is independent of Last.fm delivery.
- Outbox is durable, idempotent, bounded/FIFO, account/session-aware, and handles accepted/ignored/reauth/retry truth.

## Lyrics

- Key requests/cache by Recording identity plus metadata fingerprint/source evidence.
- UI state additionally carries QueueEntryId/generation so late Track A results cannot overwrite Track B.
- Source chain, cache validation, retry, instrumental/no-lyrics, synced/plain, and offline behavior are explicit.
- Preview height is stable; full lyrics respects user scrolling and accessibility.

## Crew

- Wire protocol uses portable R16 Recording/Source/QueueEntry-compatible descriptors, never local database IDs as global truth.
- Crew command state and local playback remain separate authorities: Crew coordinates shared intent; local PlaybackCoordinator remains the sole local player authority.
- Remote actions enter the same command router.
- Source availability and Push & Pull provide sources/assets without changing Recording identity.
- If physical Crew evidence is incomplete at beta, gate it honestly as experimental rather than blocking ordinary playback or claiming stability.
