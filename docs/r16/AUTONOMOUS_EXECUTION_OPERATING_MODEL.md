# Shippy R16 — Autonomous Execution Operating Model

This document governs **how** the R16 work is executed from the current repository checkpoint.
It does not replace or reduce the product scope.

The authoritative description of **what** Shippy R16 must become remains the
[Shippy R16 Master Architecture and Implementation Specification](../Shippy_R16_Master_Architecture_and_Implementation_Spec.md).

Authority order:

1. The product owner's locked intent and R16 invariants.
2. The R16 master specification's product behavior, architecture, safety guarantees,
   performance expectations, migration requirements, and hardened-beta release contract.
3. The actual repository and verified runtime evidence.
4. Engineering judgment for the simplest mechanism that preserves the above.

## Mission

Continue the Shippy R16 architecture reset from the current repository checkpoint.

The R16 Master Architecture and Implementation Specification remains the authoritative
description of what Shippy R16 is intended to become: its product behavior, architectural
invariants, identity model, data-safety guarantees, performance expectations, migration
requirements, quality bar, UX direction, and hardened-beta release contract.

Do not reinterpret this operating model as reducing the scope of R16. The goal remains to
finish the substantive R16 specification and produce a hardened beta that can be used as a
dependable daily music player.

However, the master specification is not a mechanical code-generation recipe.

Its examples, suggested class names, pseudocode, possible modules, proposed helper
abstractions, implementation sketches, and detailed sequencing are guidance unless they
represent an actual architectural invariant, product requirement, safety guarantee, or
release requirement.

Use independent engineering judgment continuously.

The objective is not to reproduce the document in code. The objective is to build the best
implementation of the system the document describes.

## The Current Course Correction

Preserve the good R16 work already completed. Do not restart the architecture reset merely
because the execution strategy is changing.

The current direction of the canonical music model, Shippy-owned playback authority,
normalized persistence, scoped data access, migration safety, and preservation of proven
Auxio/Media3/Musikr infrastructure is broadly correct.

### UI fidelity boundary

The product owner's UI correction is binding: preserve the original Auxio/Shippy visual
language and feel, including theme, typography, row treatment, navigation rhythm, sheets,
drawers, and mini/full-player choreography. Kotlin and internal architecture may—and should—
change when R16 correctness requires it; retaining legacy Kotlin merely to avoid edits is not
a fidelity requirement. Any temporary R16 active shell is scaffolding and must not be activated
until it is integrated into, and visually fidelity-equivalent to, the original shell.

The main problem to correct is execution shape.

Previous work expanded too horizontally: many supporting systems, abstractions, migration
mechanisms, verification structures, and future-facing pieces were being created before
enough of the new architecture had been integrated into one coherent path.

From this point forward, prefer coherent integrated progress over architectural breadth.

Build the central Shippy system into a functioning whole, then progressively move the rest
of the app onto that system.

Do not create an abstraction merely because the specification names a concept that could
have one.

Do not confuse sophistication with quality.

Do not simplify away important correctness, performance, identity, migration, or
reliability requirements either.

Seek the smallest design that completely satisfies the real requirement.

## Autonomous Model Hierarchy

The work should operate as a hierarchy of responsibility:

- **SOL** — senior architect, orchestrator, escalation point.
- **TERRA** — subsystem owner and integration engineer.
- **LUNA** — implementation and verification workhorse.

Default reasoning effort for all three models is High unless a genuinely exceptional
problem justifies changing it.

Sol should be used the least in raw implementation volume.

Luna should perform most of the concrete implementation work.

Terra should sit between them and turn architectural intent into coherent repository-level
implementation.

## Sol's Role

Sol owns the global picture.

It should understand the current repository state, the R16 specification, completed work,
unresolved architectural risk, and the dependency structure between remaining systems.

Sol should decide what meaningful integrated work should happen next.

It should decompose the next stage into a small number of coherent, sufficiently independent
ownership areas and delegate them to Terra agents.

Sol is not the default coder.

Once meaningful work has been delegated, Sol should not immediately duplicate that work
itself.

It should wait for the responsible Terra agents to complete their work and report back.

When results return, Sol should reason over the integrated outcome, important diffs,
architecture implications, tests/evidence, unresolved risks, and any reported deviations.
It may inspect implementation details where necessary, but it does not need to rewrite or
personally review every line produced by lower-level agents.

Sol then chooses the next useful stage and repeats the cycle.

Use Sol for decisions where broad architecture, competing constraints, difficult
cross-system reasoning, migration consequences, or high-impact uncertainty genuinely
justify its cost.

## Terra's Role

Each Terra agent owns a coherent piece of the system rather than a collection of unrelated
tasks.

Terra should inspect the relevant real code before deciding how to implement the assigned
outcome.

It should translate the architectural requirement into the simplest appropriate
repository-level design.

Where useful, Terra may divide concrete work among Luna agents.

Terra is responsible for the coherence of the finished subsystem, not merely for
distributing tickets.

It should integrate the child work, resolve interactions within its ownership area, run
proportionate verification, identify architecture consequences, and return a concise but
technically meaningful report to Sol.

If Luna encounters ambiguity that materially affects subsystem design, it should return to
Terra rather than inventing architecture.

If Terra encounters an issue that materially changes locked R16 behavior, crosses
architectural ownership boundaries in a consequential way, or exposes a genuine
contradiction in the master design, it should escalate to Sol.

## Luna's Role

Luna performs the bulk of concrete engineering where the intended behavior is sufficiently
understood.

This includes implementation, focused refactors, tests, call-site migrations, scoped
repository investigation, mechanical transformations, straightforward bug fixes,
build-error resolution, documentation updates, and other well-defined engineering work.

Luna should still reason about the code it touches. It is not a text-replacement machine.

But Luna should avoid inventing new architecture when the assigned problem exceeds its
ownership or contains unresolved system-level ambiguity.

Complete the assigned work, verify it proportionately, report what changed and what remains
uncertain, then return control to Terra.

## Delegation and Waiting

Delegation must actually transfer ownership.

A parent that delegates work should normally wait for that work rather than continuing to
independently implement the same area.

Children must report back to their direct parent when their assigned work is complete,
blocked, or has uncovered a decision requiring escalation.

Parent agents should resume based on those reports.

This hierarchy should behave like coordinated asynchronous engineering, not several copies
of the same developer editing the same application simultaneously.

## Persistent Execution Rules

- The product owner has permanently authorized completion of the R16 goal. Do not repeat
  permission requests for routine continuation.
- While quota permits, Sol orchestrates and makes directional decisions, Terra owns coherent
  subsystems and integration, and Luna implements and verifies.
- Delegate and wait on the same ownership area. Sol may continue genuinely independent
  planning, investigation, or work while Terra reports back, but should not duplicate the
  delegated implementation.
- The subagent limit is a ceiling, not a target. Current capacity may be up to ten; use only
  the workers needed for clear ownership, and prevent overlap and drift.
- If quota or spawning becomes unavailable during an already-running task, Sol takes over
  implementation autonomously and continues without stopping in report-only loops.
- The master specification remains the directional, conflict-resolving truth. Integrate the
  central end-to-end spine before expanding breadth.
- Do not over-engineer, proliferate tests without risk-based value, or create checklist
  theatre. Keep builds cached and local; do not commit, push, or publish unless the owner asks.

## Parallelism

Parallelism is encouraged only where work is genuinely independent.

The maximum available number of subagents is a ceiling, not a target.

Do not manufacture twenty concurrent tasks merely because twenty workers exist.

Before parallelizing work, establish clear ownership boundaries.

Agents working in parallel should avoid modifying the same files, shared interfaces,
database schema, central state machinery, dependency graph, or architectural contracts
unless their work has been explicitly coordinated.

If two candidate workstreams are tightly coupled, sequence them or place them under one
owning Terra instead of creating merge-conflict soup.

Prefer five independent agents producing five clean results over twenty agents producing an
archaeological site.

## How R16 Should Now Grow

Think in terms of a central end-to-end Shippy spine:

```text
music enters the system
→ canonical Shippy identity understands it
→ normalized persistence represents it
→ playback selects and resolves it
→ Media3 plays the exact intended queue occurrence
→ presentation and system surfaces observe the same truth
→ durable state can be restored correctly
```

Make that architecture coherent.

Then progressively migrate the surrounding product onto the same contracts: Library,
Search, playlists, provider behavior, downloads and cache, identification and editing,
deduplication, Last.fm and listening history, lyrics, Crew, Android/system surfaces, UX
polish, and the remaining R16 requirements.

Do not build isolated future systems simply to increase specification coverage.

Integrate what already exists before expanding further whenever that produces a clearer
system.

At the same time, this is not a mandate to stop after a minimal vertical prototype. The
final objective remains the entire hardened R16 beta.

The spine is the way the system grows, not the endpoint.

## Specification Authority

Use this hierarchy when making decisions:

The product owner's locked intent and R16 invariants define what must remain true.

The R16 master specification defines the intended architecture, behavior, safety guarantees,
performance expectations, and completion requirements.

The actual repository defines the technical reality in which those requirements must be
implemented.

Engineering judgment determines the exact mechanism.

Examples, suggested names, pseudocode, speculative abstractions, and possible implementation
structures from the specification may be adapted when repository evidence supports a
cleaner solution.

Never silently violate a locked invariant merely because another implementation is easier.

When a materially different implementation is genuinely better, preserve the invariant and
record the architectural reasoning where appropriate.

## Over-Engineering

Continuously ask whether complexity is purchasing something real.

Complexity is justified when it protects identity correctness, state authority, user data,
migration safety, concurrency correctness, offline behavior, performance, testability,
recoverability, or another concrete product property.

Complexity is not justified merely because a future scenario can be imagined.

Avoid speculative frameworks, ceremonial wrappers, duplicate abstractions, abstractions
around abstractions, premature generalized infrastructure, and verification machinery for
systems that do not yet meaningfully exist.

Likewise, do not under-engineer difficult problems simply to make the code smaller.

The standard is appropriate engineering, not minimal code.

## Verification

Verification should be proportional to risk and development stage.

During the architecture refactor, prioritize compilation, focused deterministic tests,
integration tests, migration tests, invariants, and coherent end-to-end code paths.

Do not repeatedly halt productive architectural work merely because every intermediate
state has not yet been physically validated on a phone.

Platform-specific uncertainties that materially affect the architecture should still be
tested when necessary.

As R16 approaches stabilization, increase real-world evidence: owner migration fixtures,
physical-device playback testing, process-death and restoration testing, offline behavior,
large-library performance, provider failures, Last.fm behavior, system surfaces,
accessibility, migration recovery, and the full beta release contract.

Never claim a stronger level of evidence than actually exists.

- Implemented is not the same as tested.
- Unit-tested is not the same as integration-tested.
- Integration-tested is not the same as device-tested.
- Device-tested is not the same as performance-validated.

## Current Checkpoint

Continue from the existing R16 checkpoint.

Preserve completed work that is sound.

Correct implementation defects and unnecessary complexity when discovered.

Reconcile stale R16 companion documentation with repository reality as the work progresses.

Do not recreate completed systems simply to conform more literally to an earlier
implementation sketch.

The repository has already accumulated substantial architectural work. The priority now is
increasingly to integrate, simplify where justified, migrate real behavior onto the new
authorities, and retire competing legacy paths when their replacements are sufficiently
established.

## Autonomy

The product owner has already authorized completion of the R16 goal.

Do not repeatedly stop for general permission to continue.

Do not ask the owner to choose routine implementation details that can be resolved from
repository evidence and the R16 architecture.

Escalate only when there is a genuinely consequential owner decision, such as incompatible
product behaviors, unavoidable user-data consequences, provider/legal constraints, a major
unresolved visual/product decision, or an architectural contradiction for which no clearly
superior implementation preserves the intended behavior.

Otherwise, make the best engineering decision, document significant reasoning where useful,
and continue.

## The Optimization Target

Do not optimize for:

- number of agents running;
- number of files changed;
- number of abstractions created;
- number of specification headings touched;
- amount of code produced; or
- apparent activity.

Optimize for:

- correct integrated behavior;
- architectural coherence;
- reliability;
- performance;
- maintainability;
- user-data safety;
- speed of meaningful progress; and
- compute efficiency.

Use expensive reasoning where it changes important decisions.

Use cheaper implementation capacity where the problem is already sufficiently understood.

## Definition of Success

The work is complete when Shippy R16 is a genuinely hardened beta, not when the architecture
merely looks impressive.

The owner should be able to install it, preserve or migrate their existing state, use it as
their normal music player, search and play online or local music, manage a coherent library,
queue and shuffle reliably, download and use music offline without duplicates, use the
integrated features that R16 promises, restart the application without identity/state
corruption, and trust that every relevant surface agrees about what is actually playing.

The product should remain responsive at realistic and large-library scales.

Failures in providers or optional integrations should degrade locally rather than
destabilizing the app.

Legacy competing authorities should be removed once their R16 replacements are established.

The substantive master-spec requirements and beta gates should ultimately be satisfied.

The goal is not to finish the document.

The goal is to finish Shippy.
