# ADR-005 — Bounded Media3 Engine Window

**Status:** Accepted

The product queue remains lightweight and complete. Source preparation and rich
Media3 items are limited to the current occurrence and a small adjacent window.
The initial target is one previous item and the next two or three items.

If a supported Android/MediaSession path proves incompatible, Media3 may receive
a lightweight full item list while source preparation remains bounded. Shippy
queue identity and traversal remain authoritative either way.
