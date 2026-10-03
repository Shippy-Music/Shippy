# C05 — Offline, Cache, and Asset Contract

## Distinct concepts

- **Streaming cache:** disposable, bounded, evictable bytes associated with a source/variant.
- **Permanent download:** verified durable managed asset owned by the user.
- **Local file:** user-owned external asset discovered by Musikr.
- **Library membership:** user relationship, not byte availability.

Clear cache, remove download, remove from Library, and delete a local file are separate actions.

## Download identity

A download job stores exact RecordingId, requested SourceReferenceId, media variant, destination identity, and job ID. No provider search/fuzzy fallback occurs inside a resumed download job.

## Publication

Private stage → byte/format verification → pending SAF document → copy → destination verification/Media3 readability → durable asset/job transaction → scanner-visible publication. Scanner suppression begins before pending publication/reindex. Same-name files are never adopted by filename.

## Cache

Use stable cache keys based on exact source/variant identity, never expiring URL. Enforce size and age policy with a free-space floor. Complete valid cache content may be promoted/copied into a permanent download without redownloading; ownership changes only after durable verification.

## Failure

Network/provider/permission/space failures persist honest state. Retry, pause, cancel, cleanup, and process restart are idempotent. Revoked SAF grants require user action and do not corrupt the canonical Recording.
