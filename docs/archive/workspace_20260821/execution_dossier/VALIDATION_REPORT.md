# Dossier Validation Report

**Source snapshot:** `Shippy-R16-Working-Snapshot-20260821-162642.zip`  
**Source SHA-256:** `3b00ffcfb89ac19a6fe7bde42e606a57a64f11175f26747a1301b853ad4f9216`  
**Master spec SHA-256:** `8aa90071411e6253bd97761ce4073f728c0e62663acb0946c57fa1590ca6545b`  
**Operating model SHA-256:** `404ad82414c05c1ea41a63cf3f020d7a64bf9d286b8379cef20111dd545318aa`

## Structural validation

- 11 Terra-level work packets are present.
- 28 direct Luna execution tasks are present.
- Every Luna dependency references an existing task.
- The Luna dependency graph is acyclic.
- Every explicit repository path in a Luna task's starting-surface section exists in the audited source snapshot.
- `machine/manifest.json`, `machine/work_packets.json`, `machine/luna_tasks.json`, and `machine/repo_index.tsv` parse/exist.
- `SHA256SUMS.txt` covers every dossier file other than itself.

## Intent validation

The package deliberately separates:

- **locked requirements and invariants**, which are binding;
- **current repository facts**, which were checked against the 2026-08-21 worktree;
- **Terra integration packets**, which coordinate coherent ownership areas;
- **Luna execution tasks**, which remove product/architecture ambiguity while retaining local implementation freedom;
- **release evidence**, which remains distinct from source presence or unit-test claims.

The dossier does not reduce R16 scope and does not mechanically reproduce the 42,000-word master document. It encodes the remaining work, known correctness gaps, current code entry points, dependencies, parallelism boundaries, failure cases, verification, and completion contracts needed for implementation.

## Known limitations

- This dossier is based on a static source audit of the named snapshot. It does not claim a fresh Gradle build, device test, owner-data migration, or performance run.
- Source-specific guidance must be rechecked by Terra if the worktree advances beyond the hashed snapshot before a task begins.
- Final application ID/signing ownership and any other Appendix L genuinely blocking owner decision remain owner gates.
