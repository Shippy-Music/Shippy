# Contributing to Shippy

Shippy is in alpha. Small, focused fixes with reproducible evidence are the
fastest contributions to review.

## Report a bug

Search existing issues first. New reports should include:

- Shippy version and installation source
- device model and Android version
- exact reproduction steps
- expected and actual behavior
- logcat, screenshot, or screen recording when available

Do not post API keys, credentials, private Crew invitations, or personal media.

## Submit code

1. Open or reference an issue for behavior-changing work.
2. Keep the change focused and preserve the product rules in
   `docs/PRODUCT_SPEC.md`.
3. Add or update tests.
4. Run:

   ```bash
   ./gradlew spotlessCheck
   ./gradlew app:testDebugUnitTest musikr:testDebugUnitTest app:lintDebug
   ./gradlew app:assembleDebug
   (cd relay && npm test)
   ```

5. State what was implemented, syntax-checked, smoke-tested, and device-tested.

Kotlin is preferred for application code. Vendored Java/native changes require
a concrete interoperability or performance reason. New dependencies must have
licenses compatible with GPL-3.0-or-later distribution.

## Product direction

Shippy aims to make local, downloaded, provider, and Crew music feel like one
coherent Android product. Avoid unrelated feature accumulation, provider
lock-in, hidden network behavior, or changes that weaken offline playback.

## License

By contributing, you agree that your contribution is distributed under the
project's GNU GPL v3-or-later license.
