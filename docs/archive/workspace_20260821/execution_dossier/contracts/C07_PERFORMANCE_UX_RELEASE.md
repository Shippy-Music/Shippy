# C07 — Performance, UX, and Release Contract

## Performance

- No main-thread network, Room, fingerprinting, full catalogue mapping, or large bitmap work.
- Large lists use Paging/FTS/indexed queries.
- Queue state uses lightweight IDs; expensive validation/preparation happens at mutation boundaries or bounded windows.
- Provider concurrency is bounded and cancellable.
- Artwork/palette work is cached with bounded memory.
- Background work is event-driven, unique/idempotent, constrained, observable, and backoff-aware.
- Measure 10k queue and 50k Library paths before beta.

## UI

Preserve the established Shippy/Auxio design language. Integrate R16 into the real shell rather than shipping the temporary button-strip host. Prefer consistent bottom sheets for multi-action workflows, stable loading/empty/error states, correct Back behavior, 48dp targets, readable dynamic/album-derived color, and restrained M3 Expressive motion/shape.

## Accessibility

TalkBack labels/state, large text, contrast, reduced motion, focus order, non-drag alternatives, touch targets, and adaptive small/large-screen behavior are release requirements.

## Release

A beta candidate requires clean install, real-data migration, backup/restore, signed update path, Android/device matrix, offline/provider/process-death/Last.fm/Crew truth, performance evidence, crash/ANR review, known limitations, and owner acceptance. No ignored failing test may conceal a release requirement.
