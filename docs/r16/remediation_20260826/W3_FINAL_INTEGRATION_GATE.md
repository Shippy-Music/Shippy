# Wave 3 - Supervisor Integration and Beta Evidence

## Integration review

- Review every worker diff against its packet and reject scope expansion.
- Reconcile shared fragments after Wave 1C/2B and shared data seams after Wave 1D/2A.
- Re-run static searches proving no production call site remains for each confirmed defect.
- Update Graphify only after source integration is stable.
- Update `docs/STATUS.md` with evidence labels, not completion percentages.

## Cached verification ladder

Run once, preserving caches and stopping at the first real failure:

1. Focused tests from all packets.
2. Kotlin/test source compilation for changed modules.
3. Existing bounded JVM suite.
4. Formatting/static checks.
5. Debug lint and cached debug assembly only if the machine remains healthy.

Do not manufacture hundreds of new tests. One contract-focused regression per real defect is
enough unless multiple independent failure modes exist.

## Handoff truth

Separate: Implemented, Syntax-checked, Unit-tested, Integration-tested, Build-tested, and
Device-tested. Device migration, playback, storage, provider, Last.fm-account, theme,
accessibility, performance, and system-surface checks remain owner-device work until actually run.
No commit or GitHub/Drive publication without owner request.
