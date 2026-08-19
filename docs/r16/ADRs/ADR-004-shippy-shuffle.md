# ADR-004 — Shippy-Owned Queue Traversal

**Status:** Accepted

Shippy owns base queue order and deterministic traversal order by `QueueEntryId`.
Media3 shuffle remains disabled and receives the exact projected order required
for playback. Duplicate recordings remain distinct occurrences and toggling
shuffle never changes the current occurrence identity.
