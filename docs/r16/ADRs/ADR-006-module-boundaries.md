# ADR-006 — Minimal Enforced Modules

**Status:** Accepted

R16 begins with only the boundaries that pay for themselves:

- `:shippy-core` — pure Kotlin domain, policies, reducers, and invariants.
- `:shippy-data` — Room, repositories, read models, backup, and migration.
- `:shippy-sources` — local/provider/source/cache/download boundaries.
- `:app` — Views UI, Media3 engine/service, integrations, Crew wiring, and DI.

Existing `:musikr`, vendored media modules, and `relay` remain. Last.fm, lyrics,
and Crew are kept as strict packages in `:app` initially; they become separate
modules only if an independently enforceable lifecycle/test boundary provides
measured value. This resolves the broader Part VI sketch in favor of the leaner
Appendix M blueprint.
