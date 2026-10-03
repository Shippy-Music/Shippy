# Shippy Agent Instructions

## Resume Protocol

On every fresh turn, automatic goal continuation, or context compaction:

1. Read `docs/PRODUCT_SPEC.md` completely.
2. Read `docs/CLOUD_HANDOFF.md`, then `docs/STATUS.md` completely. The handoff
   records the current GitHub baseline and identifies historical status text.
3. Read the specific companion contract for the current task:
   - UI/product work: `docs/UX.md`
   - Crew/network work: `docs/CREW.md`
   - architecture/code work: `docs/ARCHITECTURE.md`
   - sequencing: `docs/IMPLEMENTATION_PLAN.md`
4. Inspect the real files and current Git state before making claims.
5. Continue the next concrete action recorded in `docs/STATUS.md`.

Do not rely on remembered summaries when canonical files are available.

## Product Authority

`docs/PRODUCT_SPEC.md` is authoritative. Companion documents may add detail but
must not contradict it. If the owner changes a decision, update the canonical
document and relevant companion/status files in the same change.

## Engineering Style

- Engineer exactly to the requirement: neither speculative over-engineering nor
  fragile shortcuts.
- Ship top-down: establish the complete visible end-to-end product path first,
  then refine each hidden subsystem to the exact quality its real behavior,
  measurements, and device failures require.
- Do not finish increasingly deep infrastructure layers while a required
  user-facing workflow is still disconnected. Preserve sound foundations, but
  prioritize the next broad vertical connection.
- Reuse Auxio behavior and structure before replacing it.
- Treat Bloomee as inspected donor behavior, not directly reusable Kotlin code.
- Work in complete vertical slices.
- Keep one playback state, one queue authority, and one ordered Crew state.
- Add focused deterministic tests with new domain/protocol behavior.
- Do not represent placeholders, mocks, or unverified flows as complete.
- Preserve unrelated upstream/user changes.
- Use `apply_patch` for source/document edits.
- Use `rtk` when installed; plain commands are supported in fresh cloud environments.

## Machine Constraint

Do not run repeated or resource-intensive Gradle/Android builds on this PC.
Prefer source inspection, formatting, narrow static checks, and focused JVM
tests when inexpensive. Final APK build, installation, and physical-phone
acceptance belong to the owner handoff unless the owner explicitly changes this.

This constraint applies to the owner's Windows PC. In Codex Cloud/Linux CI,
follow `docs/CLOUD_SETUP.md` and run checks appropriate to the change. Do not
depend on Windows drive paths, local Gradle caches, generated native libraries,
or ignored graph outputs. GitHub and its pinned submodules are the source baseline.

## Parallel Work

At most four active agents may exist at once, including the root agent. Use
subagents only for tightly scoped code-writing work whose product intent,
architecture boundary, dependencies, integration path, and acceptance checks
have already been decided by the root agent.

- The root agent plans and specifies the slice before delegation.
- GPT-5.6 Terra at medium reasoning is the default implementation worker.
- Give workers disjoint files or explicitly coordinated boundaries.
- Workers do not make new product or architecture decisions and do not expand
  scope.
- While workers implement, the root agent continues independent planning,
  inspection, or review so delivery does not stall.
- The root agent reviews every returned patch, fixes integration issues,
  reconciles canonical documentation, runs the available verification, and owns
  commits.
- Do not delegate vague exploration, whole-product ownership, or work whose
  future integration is unclear merely to increase concurrency.

## Progress Ledger

Update `docs/STATUS.md` after:

- Completing a meaningful implementation slice
- Discovering a new concrete blocker/risk
- Changing a decision
- Finishing a verification layer

Keep STATUS concise. Put durable technical explanation in the appropriate
canonical companion document.

## Verification Language

Use only accurate labels:

- Implemented
- Syntax-checked
- Unit-tested
- Integration-tested
- Simulated
- Device-tested
- Not verified

Never say “fully working” without the actual end-to-end device workflow.

## Graphify

- From the repository root, update the active Android code graph with
  `rtk graphify update app --no-cluster`.
- Do not target the repository root or nested source directories. The root
  includes large vendored media fixtures, while nested targets create stray
  `graphify-out` folders inside source trees.
- `graphify-out/graph.json` mirrors the curated `app` graph for normal queries.
  The lower-level `musikr` graph remains separate under its existing output.
