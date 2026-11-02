# Work report — Touch Music ID first Android build — 2026-11-02

- **Repository:** mrmundysr-sudo/Touch_Music_ID (public). **Branch:** `claude/touch-music-id-first-build`. **Base:** `main` @ `f1caaad` (docs-only; it was the only branch, no PRs).
- **Source commit of the delivered APK:** `1c48530d2ad05fae813f0735b8fd84bc97d701d4`.
- **Owner request:** build Touch Music ID from the brief: polished UI, real Wikipedia integration, persistent history/favorites, proper ShazamKit architecture, replaceable 720×1600 artwork with documented slots, no embedded credentials, no pretended live recognition, separate branch, early builds, regular commits, debug APK, no merge to `main`. Regular Apple ID only.
- **Scope approved:** first Android debug APK. **Out of scope / not done:** merge, release, signing, Phase 2+, other repositories.

## Branch discovery (CLAUDE.md requirement)
Live GitHub: one branch (`main`, `f1caaad`), zero PRs, no workflow runs. `BRANCH_STATUS.md` was an unfilled template. Nothing to preserve; branched from `main`.

## Knowledge / registry consulted
- Read: CLAUDE.md, MUSIC_ID_BUILD_BRIEF, PRODUCT_VISION, DECISIONS, DEVELOPMENT_PLAN, COMPONENT_REGISTRY, BRANCH_STATUS, `.autobuild/deliver.conf`.
- `docs/knowledge/` did not exist: created a minimal structure.
- **Touch-Modules registry (read-only clone):** `components.json` has `"components": []` → no verified component to reuse. **Gap recorded; everything was built new.**
- **Hub (`touch-development`, read-only clone):** read `AUTOBUILD.md`, `autobuild/APK-DELIVERY.md`, `ONBOARDING.md`, `ROLLOUT.md`. Followed the delivery loop. Did **not** modify the hub or register this app in `projects.yml` (needs owner approval).
- Apple ShazamKit Android page (fetched twice): see `docs/SHAZAMKIT_SETUP.md`.

## What was built
Native Kotlin + Compose (Material 3) + Room, minSdk 24 / target 35.
- Recognition: `RecognitionEngine` boundary, `RecognitionController` state machine, `DemoEngine` (labelled), `UnavailableEngine`, `RemoteTokenSource` (HTTPS, in-memory), `EngineProvider`.
- Knowledge: `WikiApi`, `WikiParsers`, `KnowledgeResolver` (conservative matching), `KnowledgeRepository` (cache-first, stale-on-error), JSON codec.
- Data: Room `history`, `favorites`, `knowledge_cache`; settings.
- Links: Apple Music / Spotify / YouTube search or allow-listed canonical links; Wikipedia links; share sheet.
- UI: Listen (animated pulse + waveform, reduce-motion aware), Result/discovery, History, Favorites, Settings, Setup guide; bottom nav; accessibility labels, ≥48 dp targets, live region.
- Design system: tokens, theme, **22 named asset slots** (no art required), `docs/VISUAL_ASSET_MANIFEST.md` + a test that keeps it in sync.
- Docs: README, SHAZAMKIT_SETUP, TOKEN_SERVICE, PRIVACY (draft), plan, decisions DEC-012..018 (Proposed), screenshots.

## Tests actually run
| What | Result |
|---|---|
| Local `./gradlew testDebugUnitTest` at the source commit | 76 tests, 73 run, 3 skipped (opt-in live), **0 failed** |
| Local `assembleDebug` | success |
| CI run 6 (`71d32c4`): unit tests, debug APK, live Wikipedia/Wikidata smoke | all three jobs success; live output below |
| CI run 8 (source commit `1c48530`) | see BRANCH_STATUS.md / final report |

**Live API evidence (CI run 6, GitHub runner):** Queen → VERIFIED (Q15862; formed 1970, London, genres, labels; extract + article link). Bohemian Rhapsody → VERIFIED (Q187745; released 1975-10-31; A Night at the Opera; producer Roy Thomas Baker; composer/lyricist Freddie Mercury). Prince → VERIFIED single musician (Q7542). Nonsense song/artist → NONE (nothing asserted). The dev sandbox itself cannot reach Wikipedia/Wikidata (network policy), so this evidence comes only from CI.
The live run found one cosmetic defect (duplicate "Origin"/"Formed in" for a person); fixed in the source commit with a test.

## Repairs / failures (honest log)
1. First AutoBuild run failed with 0 jobs (reusable hub workflow). Cause **inferred**, not confirmed: public repo cannot call private hub. Replaced with a self-contained workflow. (1 repair)
2. `setup-android` action failed on the runner; replaced with the runner's `sdkmanager`. (same push, counted with 1)
3. Local only: Maven Central 429s in the sandbox (worked around with a mirror in a local Gradle init script, not committed); Robolectric android-all pre-fetched.
4. Test-harness issues fixed: `backgroundScope` vs `advanceUntilIdle`, effect delays on the test clock, `captureToImage` hang, Room executor timing, infinite-animation idling. A real controller bug was found by a test (permission grant re-checked permission) and fixed.
5. An unverified claim I had put in the Help text (about Apple Developer membership) was removed.
Repair cycles against CI: 1 (≤3 limit).

## Not tested / blocked / limitations
- **Real recognition**: not implemented, not tested. No AAR, no token, no device. Accuracy unmeasured.
- **No device or emulator run.** Screenshots are Robolectric renderings.
- Performance, TalkBack, large-font, tablets, landscape, process-death restore: untested.
- English-only facts; strings hard-coded; no album art; Room has no migrations.
- Wikipedia matching quality beyond four live probes is unmeasured.
- The CI live job is informational (`continue-on-error`).

## Candidate reusable components (NOT verified in a second app, so not "reusable")
`RecognitionController` pattern · `AssetSlot` replaceable-art system · `KnowledgeResolver`/`KnowledgeRepository` (Wikipedia/Wikidata) · `ExternalLinks` allow-list · Robolectric UI-flow + screenshot harness. Proposal only; no registry change made.

## Lessons / studio-wide flags
See `LESSONS_LEARNED.md`. Flag for owner review: **public repos cannot use the private hub workflow**; the hub's docs say it is public but the live repo list says private.

## Next steps
1. Owner installs the APK on a phone and runs the checklist in the final message / README; report issues.
2. Decide DEC-012..018 and the hub-visibility question.
3. If proceeding: Apple credentials → token service → link AAR → compile adapter template → real-device test matrix (SHAZAMKIT_SETUP).
