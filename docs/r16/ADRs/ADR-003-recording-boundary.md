# ADR-003 — Canonical Recording Boundary

**Status:** Accepted

One `Recording` represents one specific audible performance, mix, or version.
Provider listings, local files, downloads, caches, and Crew media are sources or
assets attached to a Recording. Provider IDs, URLs, file paths, playlist rows,
and queue indices never define `RecordingId`.

Identity decisions remain conservative, evidence-backed, reversible, and
provenance-preserving. Version contradictions veto automatic linking.
