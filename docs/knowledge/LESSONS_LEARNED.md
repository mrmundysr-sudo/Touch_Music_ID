# Lessons learned (verified)

1. **A public repo cannot call a reusable workflow in a private repo.** The first AutoBuild run failed instantly with zero jobs. The hub
   `touch-development` is private today (live repo list) although its own rollout docs list it as public. Verified by the fix: a
   self-contained workflow built successfully (runs 2–4). *Studio-wide:* every public caller is likely affected. Flagged for owner review; not changed elsewhere.
2. **`android-actions/setup-android@v3` failed on `ubuntu-latest`** ("Failed to find package 'tools'"). The runner already has an SDK; accepting licences and
   installing the needed platform with the runner's `sdkmanager` works.
3. **Compose/Robolectric tests:** (a) `advanceUntilIdle()` ignores `backgroundScope` work, so a controller launched there looks stuck; (b) `LaunchedEffect` delays need
   `mainClock.advanceTimeBy`; (c) `captureToImage()` hangs on a paused looper, `PixelCopy.request` works with `@GraphicsMode(NATIVE)`; (d) Room suspend queries run on
   background executors, so settle loops need short real sleeps; (e) infinite animations stall `waitForIdle`, so the app honours reduce-motion (`ANIMATOR_DURATION_SCALE=0`) and tests use it.
4. **Robolectric android-all download** needs Maven Central; in a sandbox that rate-limits it, pre-fetch the jar and set `robolectric.offline` + `robolectric.dependency.dir` (kept out of the repo via an optional `-ProbolectricDir`).
5. **Sandbox network policy** may block hosts the app needs (here `en.wikipedia.org`, `www.wikidata.org`). Don't mock around it; add an opt-in live test that CI runs.
6. **Don't state unverified vendor facts** (e.g. that a free Apple ID cannot get a ShazamKit key). Apple's page is silent on it; the UI text and docs say "check in Apple's portal".
