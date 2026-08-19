# R16 Migration Ledger

**State:** Design accepted; importer not implemented.

- Source: legacy `ShippyDatabase` v10, read-only during import.
- Target: new normalized `shippy-r16.db` v1.
- IDs: deterministic UUIDv5 mappings for legacy durable identities.
- Safety: create `ShippyBackupV1`, checkpoint each phase, verify counts and
  invariants, then cut over atomically.
- Downgrade: no silent dual writes; recovery uses explicit backup/import.

No real owner database fixture has been imported yet.
