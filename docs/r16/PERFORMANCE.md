# R16 Performance Evidence

**Checkpoint:** 2026-08-20

No physical-device or runtime performance measurements exist yet. Targets and
required datasets are defined in Sections 87-95 of the master specification.
Results must record device, build, dataset, command/scenario, metric, budget,
and outcome.

## Current build and fixture evidence

- `:app:assembleBenchmark` is green.
- `:macrobenchmark:assembleBenchmark` is green.
- `:baselineprofile:assembleBenchmarkBenchmark` is green.
- The deterministic schema-v2 50,000-recording fixture was built and is cached
  for benchmark-variant use. This is fixture/build evidence, not a runtime
  timing result.

## Remaining evidence gaps

- No physical-device or runtime performance measurements exist.
- No real redacted owner-device v10 database fixture exists for migration
  evidence.

Gradle and build caches are retained between runs. Cache clearing is not a
routine benchmark or build step.
