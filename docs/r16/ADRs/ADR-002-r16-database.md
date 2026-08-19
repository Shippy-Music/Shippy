# ADR-002 — Separate R16 Database

**Status:** Accepted

R16 will create a normalized `shippy-r16.db` and import the legacy v10 database
through a resumable, auditable, read-only legacy reader. It will not mutate the
legacy schema in place or clear data to recover from migration failure.

Cutover requires verified counts, referential integrity, asset revalidation,
checkpoint resolution, backup evidence, and a committed migration audit.
