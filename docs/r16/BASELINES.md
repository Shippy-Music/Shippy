# R16 Baselines

## Source authority

- Control commit: `7fb431c9f3e5bad6805f7ff7b90127c67fd90b58`
- Imported Aug-19 snapshot commit: `491a1abc0`
- Frozen tag: `r15.3-stabilized-20260819`
- Working branch: `r16/architecture-reset`

## Artifacts

- Source ZIP: `artifacts/Shippy-Source-R15.3-Stabilized-20260819.zip`
  - Size: 6,615,963 bytes
  - SHA-256: `f097448c4566b983f6ec7d391f81271abcacd293cadc80c42669d95fe7fe5765`
  - Verification: all 1,235 archived files existed in the imported worktree and
    matched by SHA-256.
- Debug APK: `artifacts/Shippy-Alpha-R15.3-Stabilized-20260819.apk`
  - Size: 61,707,279 bytes
  - SHA-256: `768b3de16c8e17a2cbe580b9dd5cf80bb87a31cfd334f858902db321c038a0d8`
- Master specification:
  - External source: `X:\shippy\docs\Shippy_R16_Master_Architecture_and_Implementation_Spec.md`
  - Versioned copy: `docs/Shippy_R16_Master_Architecture_and_Implementation_Spec.md`
  - SHA-256: `8aa90071411e6253bd97761ce4073f728c0e62663acb0946c57fa1590ca6545b`

## Repository residue kept intentionally

- `artifacts/` remains untracked release evidence.
- `musikr/src/main/cpp/taglib/pkg/` is pre-existing untracked submodule output.
  It is not part of R16 source and has not been deleted.

## Build baseline

The imported R15.3 status records a passing debug assembly, formatting, JVM-test,
and lint gate. R16 will not reuse those claims as R16 evidence. Build caches are
preserved for subsequent focused builds.
