# Test and Evidence Matrix

## Per-slice minimum

| Change class | Minimum evidence |
|---|---|
| pure core rule | deterministic host unit tests, property/randomized where stateful |
| DAO/repository/schema | Room tests, migration from previous schema, backup round trip if durable |
| playback command/engine | reducer/coordinator test plus adapter/integration test |
| source/provider | contract fixtures, cancellation/failure/identity tests |
| WorkManager/download/cache | state-machine/restart/idempotency tests; instrumentation for SAF/Media3 |
| UI state | ViewModel/state tests and layout/resource checks |
| shell/navigation | instrumentation/device navigation and restoration |
| migration/cutover | synthetic + real owner fixture + interrupted/recovery test |
| performance | named fixture, build variant, device, measurement, threshold |
| optional external service | fake protocol tests plus real-account/device test before stable claim |

## Recommended command ladder

Run the narrowest useful gate first, then widen at packet integration:

```text
:shippy-core:check
:shippy-sources:check
:shippy-data:testDebugUnitTest
:app:testDebugUnitTest --tests <focused patterns>
spotlessCheck
:app:compileDebugKotlin
:app:lintDebug
:app:assembleDebug
```

Near release add release assembly/R8, instrumentation, baseline profile, macrobenchmarks, and physical journeys. Preserve Gradle/build caches unless diagnosed corruption requires clearing them.

## Evidence ledger fields

For every meaningful gate record:

- timestamp;
- source/tree hash;
- command;
- build variant;
- test count/failures/skips;
- environment/device/OS;
- fixture size/hash;
- duration and peak memory where relevant;
- artifact path/hash/signing identity;
- known unverified behavior.
