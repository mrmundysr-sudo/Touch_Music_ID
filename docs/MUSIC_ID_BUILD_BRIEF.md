# Touch Music ID — First Android Build Assignment
Status: User explicitly approved initial development. Read CLAUDE.md, PRODUCT_VISION.md, DECISIONS.md, DEVELOPMENT_PLAN.md, COMPONENT_REGISTRY.md, BRANCH_STATUS.md first.

Repository: mrmundysr-sudo/Touch_Music_ID
Work branch: claude/touch-music-id-first-build (create if absent). Do not merge to main or delete/reset other work without approval.

## Objective
Build the highest-quality functional Android debug APK possible in this session. Deliver a coherent music-identification-and-discovery product, not merely attractive screens. Build early, commit regularly, test in CI.

## Architecture and recognition
Research/inspect current official Apple ShazamKit Android SDK documentation and licensing before selecting SDK/version or claiming catalog support. Integrate the real Android SDK if obtainable. Design microphone permission flow, capture/session lifecycle, cancellation, timeouts, errors and match/no-match states. No simulated recognition presented as real. Until credentials are configured, provide clearly labeled Demo Mode with representative synthetic results and fully usable discovery UX. Document exact Apple developer steps, Media ID/key/token requirements and what cannot be tested. Never embed private signing keys, secrets or hardcoded production tokens in APK; if token delivery requires backend, create a secure interface and documented deployment path, not an insecure shortcut. Do not falsely claim end-to-end recognition works without Apple credentials and real-device evidence.

## Knowledge experience
Fetch Wikipedia and Wikidata using appropriate official APIs with polite user-agent and attribution. Resolve song/artist ambiguity conservatively; distinguish sourced facts from unverified inference; show artist bio, interesting facts, song/album/release context where verified, source links, fallback for no article, loading/offline/error states and caching. No hallucinated biographies. Respect API rate limits and media copyright. Open Apple Music/Spotify/YouTube with safe external intents/search URLs if no verified canonical link is available.

## Core app
Premium, original one-tap Listen screen, recognition progress, results/discovery, local history, favorites, settings/help, share sheet, accessible touch targets, thoughtful animations and fast launch. Secure privacy-forward microphone behavior; only record while requested, do not retain raw audio by default. Implement actual persistent storage. Design for budget Android phones.

## Replaceable visuals — mandatory
Use centralized design tokens, reusable components, named slots/IDs for backgrounds, logo, waveform, listening control, results frame, album-art container, history tiles, navigation, empty states. Document exact dimensions/aspect ratios and transparent asset expectations in docs/VISUAL_ASSET_MANIFEST.md. Ensure later 720x1600 portrait PNG assets can replace default artwork without altering screen logic. Do not bake navigation or business behavior into background images. Use original graphics and no Shazam trademark confusion.

## Tests and delivery
Automated tests for recognition state machine/credential-unavailable path, Wikipedia parsing/entity disambiguation, caching, local persistence, external link handling, and lifecycle edge cases. CI debug APK build and artifact, with truthful run URL and commit SHA. If SDK/network constraints prevent real ShazamKit linking, keep the APK compiling with an explicit adapter boundary and clear unsupported/unconfigured UI, never a fake recognition success. Include screenshots if feasible. Update build status, decision log, handoff and next steps.

## Priority
1. Early compiling Android APK/CI
2. Premium coherent UI and persistent local history/favorites
3. Live Wikipedia/Wikidata lookup with attribution and safe matching
4. ShazamKit Android integration and secure credential boundary
5. Asset manifest and later artwork replacement workflow
6. Polish, tests, documentation

No release publishing, no merging without approval.