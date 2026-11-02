# Touch Music ID — Development Plan

> Status: **First Android debug APK authorised** by the owner on 2026-11-02 (see `docs/MUSIC_ID_BUILD_BRIEF.md`). Later phases are *proposed* and need approval.

## Source of truth and working rules
- GitHub repository is the source of truth: `mrmundysr-sudo/Touch_Music_ID`, default branch `main`.
- Work only on `claude/*` branches. First-build branch: `claude/touch-music-id-first-build`. Never merge to `main`, publish releases, or modify other repositories without owner approval.
- GitHub Actions builds and tests; an APK is "ready" only after a verified green run for the exact commit.
- No secrets, private keys or long-lived tokens in the repo or APK (DEC-007).

## Feasibility audit (2026-11-02)
| Question | Evidence | Result |
|---|---|---|
| Existing reusable code | Touch-Modules `registry/components.json` has `"components": []` (read at its current `main`); sibling apps not audited | **No verified reusable component exists.** Everything was built new; candidates proposed in the work report |
| ShazamKit Android availability | Apple page: AAR download, v2.1.1, minSdk 21, no Maven coordinates, licence/membership terms not stated | **Not linkable here.** Adapter boundary built; SDK, token and device tests outstanding |
| Platform constraints | Compiles/targets API 35, minSdk 24; Compose + Room | Builds in CI and locally. **No device or emulator test performed** |
| Dependencies/licences | Compose/AndroidX/Room (Apache-2.0), Robolectric (MIT) test-only; Wikipedia CC BY-SA 4.0, Wikidata CC0 | OK for debug; legal review before release |
| Performance | Not measured | Designed for budget phones (no image loading, light animations, reduce-motion honoured). **Unmeasured** |
| Security/privacy | `docs/PRIVACY.md` draft; HTTPS-only fetcher; link allow-list; no backup; no raw audio retention | Draft, owner review needed |
| Wikipedia/Wikidata live behaviour | Dev sandbox network policy blocks both hosts | Tested on fixtures only; **CI live smoke job** gives real-API evidence |

## Architecture (as built)
- **Boundaries:** `recognition/` (engine interface + state machine + token client) · `knowledge/` (API client, parsers, resolver, cache, repository) · `data/` (Room, settings) · `links/` · `design/` (tokens, asset slots, components) · `ui/`.
- **Adapters:** `RecognitionEngine` for ShazamKit; `HttpFetcher` for network (faked in tests); `KnowledgeCache` and `TokenSource` interfaces.
- **Data:** Room v1: `history`, `favorites` (independent of history), `knowledge_cache`. Schema exported to `app/schemas/`. No migrations yet (v1).
- **Offline/network:** cache-first (7 d / 1 d for unverified), stale-on-error with a visible banner, retry button, 429 and 5xx distinguished, polite User-Agent and request spacing.
- **Permissions:** INTERNET; RECORD_AUDIO at runtime only for a live engine.
- **CI:** `.github/workflows/autobuild.yml` (self-contained; see DEC-017).

## Milestones
### Phase 0 — Audit and design
- [x] Review brief, vision, decisions, registry, branch state
- [x] Document risks, dependencies, unknowns (this file, `docs/SHAZAMKIT_SETUP.md`)
- [x] Owner approved first-APK scope (2026-11-02); architecture choices recorded as Proposed (DEC-012..018)

### Phase 1 — Minimal working application (first APK)
- [x] Compiling debug APK and CI from the first push
- [x] Listen flow, demo mode, honest unconfigured state
- [x] Wikipedia/Wikidata discovery with sources, cache, offline
- [x] History/favorites persistence, links, share, settings, privacy, help
- [x] Replaceable artwork slots + manifest
- [x] Unit/integration/UI-flow tests; CI green on the delivered commit (see BRANCH_STATUS)
- [ ] **Owner review of the APK on a real phone**

### Phase 2 — Real recognition and reliability (PROPOSED, needs approval)
- [ ] Apple media identifier + key; deploy token service; link ShazamKit AAR; compile `docs/shazamkit/ShazamKitEngine.kt.template`
- [ ] Real-device test matrix (see SHAZAMKIT_SETUP step 6); measure accuracy against DEC-010
- [ ] Android instrumented/Compose UI tests on an emulator; TalkBack pass; large-font pass
- [ ] Move UI strings to `strings.xml`; localisation decision

### Phase 3 — Release readiness (PROPOSED)
- [ ] Privacy/licence review (Apple SDK terms, store disclosures), crash handling, signing (owner-held keys), versioning, rollback
- [ ] Monetisation architecture (DEC-009)
- [ ] Album-art display rules if Apple artwork URLs are used

## Definition of done (first APK)
- [x] Approved acceptance criteria pass in tests (76 JVM tests: 73 by default, 3 live-network opt-in)
- [x] GitHub Actions green for the delivered commit (recorded in BRANCH_STATUS and the work report)
- [x] APK delivered to the owner labelled CI or local
- [x] Known limitations and untested items recorded (README, work report)
- [x] No unapproved merges/releases

## Risks and open questions
| Risk | Impact | Mitigation |
|---|---|---|
| Apple SDK/token unavailable or restricted for a free Apple ID | Core feature blocked | Adapter boundary, demo mode, documented steps; owner to verify in Apple's portal |
| Wikipedia matching wrong or too strict | Users see no facts or wrong facts | Conservative rule (verified or nothing), ambiguity picker, "how we matched this" text; measure on real songs after live smoke |
| Real APIs differ from fixtures | Parsing bugs | Opt-in live smoke test in CI; fixtures built from documented response shapes |
| Debug build never run on a device | Unknown runtime issues | Explicitly unverified; owner phone test is the next gate |
| Public repo + private hub | Shared workflow unusable | Self-contained workflow (DEC-017) |

## Approval
- **Development authorized:** Yes — first Android debug APK only.
- **Approved scope / date:** 2026-11-02, owner message + `docs/MUSIC_ID_BUILD_BRIEF.md`.
- **Not approved:** merge to `main`, release/publishing, Phase 2+ work, cross-repository changes.

## Knowledge retention
- [x] Reviewed `docs/knowledge/` (did not exist in this repo; created minimal structure)
- [x] Work report: `docs/knowledge/reports/2026-11-02-first-build.md`
- [x] Verified lessons promoted to `docs/knowledge/LESSONS_LEARNED.md` / `PATTERNS.md`; cross-project items flagged, not applied elsewhere

## Component reuse gate
- [x] Studio registry searched (empty) and hub docs read; no reusable component to inspect
- [ ] Propose registry additions after a second app verifies them (see work report)
