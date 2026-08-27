# Wave 1 review correction — Last.fm and migration

Read the master specification sections 59-61 and the existing W1_A packet before editing.

## Outcome

Make active listening checkpoints durably observable without ever enqueuing a Last.fm
scrobble before final session completion, and leave a compiling migration proof.

## Required corrections

- Keep the final listening-session transaction as the only path that may enqueue an outbox row.
- Represent an active checkpoint truthfully; do not label a threshold-crossed active session as
  `INELIGIBLE_ACTIVE_TIME`. A new string-backed disposition is allowed without a schema bump.
- Fix the focused repository test to query an API that actually returns active rows and assert
  the real model property.
- Prove: repeated active checkpoints create zero outbox rows; finalization overwrites the
  checkpoint disposition and creates exactly one row; repeated finalization remains idempotent.
- Review the v10-to-v11 integration fixture for compile errors and authentic schema mismatch.
  Keep it small and file-backed; do not invent another migration framework.

## Boundaries

- Own only listening repository/model/tests and the existing migration integration test.
- No Gradle invocation, clean, commit, push, schema-version bump, or unrelated refactor.
- Do not touch UI, queue, identity, cache, or native/taglib paths.

