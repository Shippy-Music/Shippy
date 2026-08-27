# Wave 1A - Listening Finalization and Migration Proof

## Outcome

Close the duplicate-scrobble P1 and strengthen the legacy v10 to v11 proof without changing the
production schema.

## Required behavior

1. Active listening checkpoints continue to upsert durable progress/history.
2. A checkpoint with `endedAt == null`, even after crossing the Last.fm threshold, creates no
   outbox row and returns `scrobbleQueued = false`.
3. Only a finalized record (`endedAt != null`) evaluates Last.fm disposition and may enqueue.
4. Finalization of the same session updates history and enqueues exactly once; normal retry
   idempotence remains intact.
5. Do not add a database version, table, column, delivery journal, or second outbox.
6. Strengthen the v10 to v11 test using the authentic complete v10 schema and reopen/validate the
   migrated database through Room using existing test infrastructure. Do not add a new test stack.

## Owned files

- `shippy-data/src/main/kotlin/app/shippy/data/listening/R16ListeningSessionRepository.kt`
- `shippy-data/src/test/kotlin/app/shippy/data/listening/R16ListeningSessionRepositoryTest.kt`
- `app/src/test/java/org/oxycblt/auxio/shippy/persistence/library/ShippyDatabaseMigrationIntegrationTest.kt`
- migration-test fixture/helper files only if directly required

Do not edit Home/navigation files; recommendation routing is Wave 2B.

## Acceptance

- Regression: threshold-crossed active checkpoint -> zero outbox rows -> final record -> one row.
- Regression: repeated finalized persistence remains idempotent while row exists.
- Migration proof starts from the authentic full v10 schema and Room validates v11 after reopen.
- Run the narrow listening repository and migration test classes only.
