# Wave 1 review correction — Exact unmerge restoration

Read master specification sections 18 and 20 and the existing W1_D packet before editing.

## Outcome

Make merge audit data sufficient for a transactional unmerge that restores both recordings to
their exact pre-merge durable state, without creating a generic undo framework.

## Required corrections

- Snapshot the affected survivor state as well as retired state, especially library relationship
  flags and conflicting user overrides.
- Remove the 10,000-history-row correctness cap. Every reassigned history row must be represented.
- Before the first mutation during unmerge, preflight that every referenced row exists and is
  owned by the expected survivor/current state. Corrupt or stale audits must fail closed.
- Check affected-row counts where DAO updates can silently miss data.
- Restore survivor and retired library relationships and overrides exactly.
- Reverse the redirect and mark/reverse the audit only after all restoration succeeds.
- Extend the focused regression to cover a survivor with its own conflicting override and
  different library flags, then assert exact restoration on both sides.

## Boundaries

- Own merge/unmerge repository, directly required DAO methods, audit codec/model, and focused tests.
- No schema-version bump unless the existing audit payload cannot represent the correction; prefer
  extending its serialized payload.
- No generic undo framework, Gradle invocation, clean, commit, push, or unrelated refactor.
- Do not touch playlist UI, Last.fm, GC/cache, or native/taglib paths.
