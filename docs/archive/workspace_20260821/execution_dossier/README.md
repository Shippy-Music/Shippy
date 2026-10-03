# Shippy R16 Implementation Execution Dossier

**Source snapshot:** `Shippy-R16-Working-Snapshot-20260821-162642.zip`  
**Snapshot SHA-256:** `3b00ffcfb89ac19a6fe7bde42e606a57a64f11175f26747a1301b853ad4f9216`  
**Master specification SHA-256:** `8aa90071411e6253bd97761ce4073f728c0e62663acb0946c57fa1590ca6545b`  
**In-repository operating model SHA-256:** `404ad82414c05c1ea41a63cf3f020d7a64bf9d286b8379cef20111dd545318aa`  
**Prepared for:** Terra High orchestration with Luna High implementation and selective Sol review  
**Purpose:** finish the substantive R16 program while minimizing repeated architectural reasoning inside Codex.

## What this package is

This is an implementation-ready execution package derived from the actual 2026-08-21 R16 worktree, the unchanged R16 master specification, the current R16 companion documentation, and a source-level audit of the current modules and seams.

It is **not** another restatement of the master specification. The master specification remains the source of truth for product intent, invariants, migration guarantees, quality targets, and the final release contract. This package converts the remaining work into bounded, repository-aware implementation packets so Luna can execute without rediscovering the product architecture or rereading the entire master document.

## How to use it without wasting quota

### Sol

Read:

1. `01_CURRENT_STATE.md`
2. `02_EXECUTION_DAG.md`
3. `verification/APPENDIX_V_COVERAGE.md`
4. Terra completion reports

Sol should not send the full package to every child and should not reproduce implementation already delegated.

### Terra

Read:

1. this file;
2. `02_EXECUTION_DAG.md`;
3. `03_OWNERSHIP_AND_PARALLELISM.md`;
4. `04_TERRA_RUNBOOK.md`;
5. the packet(s) being coordinated;
6. only the contract documents referenced by those packets.

Terra owns integration and shared choke points. It should delegate bounded packet subsections to no more than the number of genuinely independent workers.

### Luna

A Luna worker should receive:

1. exactly one work packet or one explicitly isolated subsection of it;
2. the packet's listed contract documents;
3. the relevant source files or paths;
4. the current parent report, if one exists.

Luna should **not** be asked to read the entire master specification, the entire repository, or every packet. The packet embeds the decisions needed for implementation. Luna still uses local engineering judgment, but it does not reopen product or architecture decisions already resolved here.

## Package map

- `00_PACKAGE_MANIFEST.md` — evidence, snapshot identity, and limitations.
- `01_CURRENT_STATE.md` — concise code-grounded state of R16 now.
- `02_EXECUTION_DAG.md` — dependency order and recommended waves.
- `03_OWNERSHIP_AND_PARALLELISM.md` — file ownership and merge-conflict rules.
- `04_TERRA_RUNBOOK.md` — orchestration, integration, reporting, and escalation.
- `05_LUNA_EXECUTION_PROTOCOL.md` — direct execution behavior for implementers.
- `contracts/` — compact binding rules embedded from the master architecture.
- `packets/` — implementation-ready remaining-work dossiers.
- `luna_tasks/` — direct bounded implementation units with exact starting files, decisions, edge cases, verification, and handback contracts.
- `07_LUNA_TASK_INDEX.md` — dependency/wave routing table for those units.
- `verification/` — Appendix V coverage, evidence classification, and beta acceptance.
- `machine/` — JSON/TSV manifests for programmatic routing.
- `VALIDATION_REPORT.md` — structural and authority validation of the dossier itself.

## Authority hierarchy

1. Product-owner decisions and locked R16 invariants.
2. `docs/Shippy_R16_Master_Architecture_and_Implementation_Spec.md`.
3. Actual repository evidence in the source snapshot.
4. This dossier's implementation packets.
5. Local engineering judgment for mechanics.

A packet may adapt a suggested mechanism when the repository proves a simpler or safer implementation, but it may not silently weaken a product behavior, identity rule, data-safety rule, performance contract, or Appendix V release requirement.

## Current high-level conclusion

The current worktree is not an architectural failure. The pure core, normalized data model, playback coordinator, bounded Media3 projection, scoped Library/Search reads, migration foundation, and verified-download publication path are broadly aligned with R16. The main work remaining is product completion, activation/cutover, identity tooling, cache, optional integrations, Crew migration, visual/accessibility hardening, performance evidence, and deletion of superseded authorities.

Several concrete correctness gaps are called out in `packets/WP00_FOUNDATION_CORRECTIONS.md`. They should be repaired before they become dependencies of later feature work.
