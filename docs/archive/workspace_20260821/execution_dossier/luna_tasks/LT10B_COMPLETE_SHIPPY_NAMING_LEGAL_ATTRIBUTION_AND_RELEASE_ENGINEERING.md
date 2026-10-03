# LT10B — Complete Shippy naming, legal attribution, and release engineering

**Parent packet:** `WP10`  
**Execution wave:** 7  
**Dependencies:** `LT10A`  
**Luna ownership:** Brand/package/release configuration, CI/signing/update handoff and docs.  
**Terra-owned/shared seams:** Final application ID and signing ownership are owner-blocking; Terra/Sol integrate manifests/build files.

## Assignment

The release candidate is consistently branded Shippy, legally truthful, signed/update-compatible under the owner decision, and reproducibly built with CI/release artifacts and hashes.

## Why this task exists in the current worktree

User-facing and new first-party code should become Shippy, while historical Auxio/Musikr copyright/license truth must remain. Final application ID, authorities, signing/update path, release R8/CI and artifacts are unresolved beta gates.

## Locked decisions

- Do not falsify copyright/history.
- Namespace and application ID are separate decisions.
- Authorities/deep links/backup/update paths change consistently.
- Secrets/signing keys never enter source/diagnostics.
- Release build must be tested, not inferred from debug.

## Explicit non-goals

- Do not rename every legacy package blindly.
- Do not expose private signing material in handoff.
- Do not choose final app ID without owner.

## Start with these repository surfaces

- `app/build.gradle`
- `build.gradle`
- `settings.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/xml/backup_descriptor.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `.github/workflows/`
- `fastlane/`
- `NOTICE`
- `LICENSE`
- `third_party/`
- `docs/r16/RELEASE_HANDOFF.md`

Do not begin with a repository-wide search. Read these files and their direct callers/tests first. Expand only when a concrete call or schema dependency requires it.

## Required implementation

### 1. Obtain owner decision

Record final application ID and upgrade/coexistence strategy in ADR. Record signing certificate ownership and secure local/CI mechanism.

### 2. Brand first-party surfaces

User-facing names/icons/descriptions/settings/about become Shippy. New R16 internal names/packages can be Shippy-first; preserve historical file headers and third-party attribution.

### 3. Update platform identity

Manifest authorities, FileProvider/content URIs, deep links, widgets, backup filenames, WorkManager unique names where package-sensitive, automotive descriptors and update compatibility.

### 4. Release pipeline

CI stages format/unit/migration/lint/debug/release/R8/instrumentation where available; no false green. Produce APK/AAB, mapping, baseline profile, SBOM/dependency/license inventory, hashes and signing cert fingerprint.

### 5. Update test

Install/update chosen path on representative Android versions; verify stored data, grants, widgets, deep links and media controls.

## Edge cases that must be handled

- old app installed
- new app coexistence
- provider authorities collision
- backup restore across ID
- signing mismatch
- R8 strips Room/Hilt/Media reflection
- third-party attribution

## Verification contract

- Release assemble/R8 and lint.
- Install/update tests with signing identity.
- Manifest/authority/deep-link tests.
- License/notice audit and artifact hashes.

## Completion contract

A reproducible legally truthful Shippy beta build installs/updates according to the owner decision and carries complete release artifacts/evidence.

## Return to Terra instead of improvising when

- Final app ID or signing owner is not provided.
- Legal/provider licensing issue requires owner counsel.

## Required handback

Return a short structured report containing: outcome; exact files changed; public/schema/API changes; tests and commands with results; unverified behavior; requested Terra integration; packet deviations; and whether the owned files are safe for another worker.
