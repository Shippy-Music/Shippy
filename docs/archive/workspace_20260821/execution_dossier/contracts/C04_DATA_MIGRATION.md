# C04 — Data, Migration, and Authority Contract

## Database

- R16 uses its own normalized Room database and explicit migrations.
- No fallback-to-destructive migration.
- Derived FTS/index tables are rebuilt/verified deterministically.
- Shared runtime consumers use the same Room instance.
- Schema changes are additive or have explicit verified transformations and exported schema JSON.

## R15 → R16 importer

- R15 is read-only during import.
- Import is resumable, idempotent, bounded, and auditable.
- Every legacy identity maps deterministically.
- Counts, relationships, order, assets, outbox/checkpoint state, warnings, and unresolved items are verified.
- A backup is created and verified before cutover.
- Real owner data must pass with zero unexplained loss before release.

## Recovery

Corrupt/interrupted bootstrap state must have an explicit safe recovery route. “Retry forever against the same corrupt marker” is not recovery. Recovery may restore the verified backup, reconstruct a marker from audit state, or reset only an uncommitted R16 attempt while leaving R15/backup untouched.

## Authority

At runtime exactly one durable authority exists:

- LEGACY before cutover;
- isolated migration/recovery while migration marker exists;
- R16 after atomic M14 activation.

Do not allow both legacy and R16 playback/Library state to be active. Compatibility after cutover is one-way and temporary.
