# R16 Final Remediation Orchestration

## Authority

1. `docs/Shippy_R16_Master_Architecture_and_Implementation_Spec.md`
2. `docs/PRODUCT_SPEC.md`
3. `docs/r16/AUTONOMOUS_EXECUTION_OPERATING_MODEL.md`
4. Current source and verified evidence
5. `X:/shippy/dossier/Shippy_R16_Execution_Dossier_20260821`

The external Aug-26 audit is evidence, not authority. Its confirmed defects are the bounded
scope of this pass. Do not reopen already-green architecture or redesign the Auxio-derived UI.

## Global worker rules

- Implement only the assigned packet. Do not make product or architecture decisions.
- Preserve the original Auxio/Shippy visual language and player choreography.
- Reuse existing R16 repositories, source-discovery contracts, queue authority, and Room schema.
- No speculative abstractions, broad cleanup, dependency upgrades, schema bumps, or package renames.
- Never touch `musikr/src/main/cpp/taglib`, `artifacts/`, or unrelated dirty-tree changes.
- Do not commit, push, publish, or delete legacy authority.
- Keep Gradle caches. Run only the packet's focused checks; the supervisor owns integration gates.
- Report exact files changed, behavior implemented, checks run, failures, and shared-file hazards.

## Execution waves

Wave 1 runs four disjoint packets in parallel:

- `W1_A_LASTFM_AND_MIGRATION.md`
- `W1_B_LIBRARY_QUEUE_CONTEXTS.md`
- `W1_C_GC_CACHE_LYRICS.md`
- `W1_D_UNMERGE_RESTORATION.md`

The supervisor reviews and integrates Wave 1 before Wave 2 starts.

Wave 2 runs only after shared UI/data seams are stable:

- `W2_A_IDENTIFY_DISCOVERY_UNDO.md`
- `W2_B_NAVIGATION_RECOMMENDATION.md`

Wave 3 is supervisor-owned integration and evidence:

- `W3_FINAL_INTEGRATION_GATE.md`

## Completion rule

A packet is complete only when its production call path is connected and its focused acceptance
test protects the actual contract. A helper, codec, DAO method, or fake-shell test alone is not
completion.
