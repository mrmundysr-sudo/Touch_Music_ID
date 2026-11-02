# Verified patterns

Each pattern was used in this repo and exercised by tests. They are **tested in this app only**; none is "reusable" in the registry sense until verified in a second app.

- **Engine adapter + state machine (recognition)**: `RecognitionEngine` interface and a pure-Kotlin `RecognitionController`, tested with virtual time (`runTest`). Launch controllers under test in the `TestScope` itself, not `backgroundScope` (see lessons).
- **Name-addressed replaceable art**: enum of slots resolved with `Resources.getIdentifier`, code-drawn fallback, `res/raw/keep.xml` for shrinking, and a unit test that the manifest documents every slot. Evidence: `design/AssetSlots.kt`, `AssetManifestTest`.
- **Conservative entity matching with a visible basis**: show data only when a checkable signal passes; otherwise ask the user or show nothing. Evidence: `KnowledgeResolver` and its tests.
- **Cache-first with stale-on-error**: `KnowledgeRepository` and tests for fresh, expired, offline-stale, offline-empty, rate-limited.
- **Injectable app container for UI tests**: `AppContainer(http, inMemoryDatabase, engineProvider)` lets Robolectric run the real activity against fakes.
