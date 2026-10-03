# Package Manifest and Evidence Boundary

## Source evidence

| Item | Value |
|---|---|
| Snapshot | `Shippy-R16-Working-Snapshot-20260821-162642.zip` |
| Snapshot SHA-256 | `3b00ffcfb89ac19a6fe7bde42e606a57a64f11175f26747a1301b853ad4f9216` |
| Extracted repository files | 1,586 |
| Master spec path | `docs/Shippy_R16_Master_Architecture_and_Implementation_Spec.md` |
| Master spec SHA-256 | `8aa90071411e6253bd97761ce4073f728c0e62663acb0946c57fa1590ca6545b` |
| Operating model path | `docs/r16/AUTONOMOUS_EXECUTION_OPERATING_MODEL.md` |
| Operating model SHA-256 | `404ad82414c05c1ea41a63cf3f020d7a64bf9d286b8379cef20111dd545318aa` |
| Database schema claimed by source/docs | v4 |
| Durable production authority | R15.3 / legacy |
| R16 durable activation | fail-closed |

The master specification inside the snapshot matches the separately preserved master specification byte-for-byte. The in-repository operating model is the newer governing version and includes the UI-fidelity boundary and persistent Sol → Terra → Luna rules.

## Code scale in this snapshot

| Module | Production files | Production LOC | Test files | Test LOC |
|---|---:|---:|---:|---:|
| `app` | 513 | 104,840 | 150 | 25,827 |
| `shippy-core` | 22 | 3,126 | 9 | 959 |
| `shippy-data` | 83 | 19,632 | 34 | 12,113 |
| `shippy-sources` | 7 | 1,140 | 3 | 479 |
| `musikr` | 72 | 8,942 | 10 | 1,764 |
| `relay` | 0 | 0 | 0 | 0 |
| `macrobenchmark` | 1 | 273 | 0 | 0 |
| `baselineprofile` | 1 | 203 | 0 | 0 |

Line count is context, not a quality metric. Transitional coexistence currently contains legacy authority, R16 authority, migration machinery, and compatibility seams simultaneously. Final cleanup must remove superseded paths after cutover rather than preserving both systems permanently.

## Evidence classification

This package is based on:

- direct source inspection of the extracted snapshot;
- structural repository analysis;
- current Room schema and migration source;
- R16 status/ADR/migration/performance documentation;
- master-spec release requirements;
- previous checkpoint comparison available in the working environment.

This package does **not** claim a newly executed build, lint run, instrumentation run, physical-device run, real Last.fm-account test, owner v10 migration, or performance measurement. Repository documents contain prior reported build/test evidence; that evidence is useful baseline context but must not be silently upgraded to independent proof.

## Known documentation drift

The latest source contains a full R16 download worker, scheduler, execution coordinator, source resolver, Media3 verifier, SAF destination, and managed-download storage. Parts of `docs/r16/STATUS.md` still describe the pre-worker offline checkpoint. Terra should reconcile companion docs after meaningful slices, but documentation repair must not become a substitute for product work.

## Machine-readable files

- `machine/manifest.json` contains snapshot and module metadata.
- `machine/work_packets.json` contains packet dependencies and ownership paths.
- `machine/repo_index.tsv` lists R16/core/data/source files and top-level declarations.
