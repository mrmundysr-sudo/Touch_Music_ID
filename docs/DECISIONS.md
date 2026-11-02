# Touch Music ID — Approved Decisions
2026-11-01. Append future decisions with stable IDs; never silently rewrite history.

- DEC-001 Approved: Android-first app, Touch Music ID.
- DEC-002 Approved: ShazamKit recognition plus Wikipedia/Wikidata facts as central experience.
- DEC-003 Approved: Apple Music, Spotify, YouTube outbound discovery links, where valid.
- DEC-004 Approved: Local identification history, favorites, share.
- DEC-005 Approved: Commercial-quality first-pass UX, modular and replaceable visual assets (720x1600 portrait design workflow).
- DEC-006 Approved: Real integration architecture and explicit demo/unconfigured state until Apple credentials exist.
- DEC-007 Approved: No private key or long-lived secret bundled in APK or repository.
- DEC-008 Approved: GitHub-first, separate development branch, early buildable APK, CI and honest test evidence.
- DEC-009 Directional: Daily usage/unlock and Touch family subscription; precise pricing and implementation deferred.
- DEC-010 Approved: Accuracy goal 95%+, to be validated, not claimed.
- DEC-011 Approved: Follow Mundy-App-Template and inspect Touch-Modules before duplicating components.

Open: exact ShazamKit Android SDK version, licensing and credential workflow; secure token service design; privacy disclosure; source-link behavior; release monetization.

## Implementation decisions from the first build (2026-11-02)
These were made by Claude while building within the approved scope. They are **Proposed**: owner confirmation requested; none is merged to `main`.

- DEC-012 Proposed: Native Kotlin + Jetpack Compose (Material 3), Room, minSdk 24 / target 35, manual DI. Chosen for budget-phone performance and testability; adds AndroidX/Compose/Room (the studio guide lists "adding AndroidX or other large dependencies" as an owner-approval item, so this needs confirmation).
- DEC-013 Proposed: Demo mode is **off by default**; with no live engine the Listen screen shows a "not set up" card with a "Try demo mode" action. Demo results carry a `DEMO` source, a visible label on every screen and are removable from Settings.
- DEC-014 Proposed: ShazamKit sits behind `RecognitionEngine`. The SDK is **not** linked (Apple AAR, terms unverified, repo is public so the AAR is git-ignored). The adapter in `docs/shazamkit/` is an uncompiled template.
- DEC-015 Proposed: Wikipedia/Wikidata matching is conservative: exactly one verified musical Wikidata entity, else the user chooses (ambiguous) or nothing is shown. Wikipedia text is shown verbatim with attribution; no images; English only.
- DEC-016 Proposed: Outbound links are search URLs unless the engine supplies a canonical Apple link on an allow-listed https host.
- DEC-017 Proposed: This repository is public, the hub (`touch-development`) is private, and GitHub does not let a public repo call a private repo's reusable workflow, so `autobuild.yml` is self-contained here. Owner options: keep this, make the hub public (its own docs list it as public), or make this repo private.
- DEC-018 Proposed: Artwork replacement by file name (`slot_*`) in `res/drawable-nodpi`, no code change; defaults drawn in code.

Still open (unchanged): exact ShazamKit SDK licence/redistribution, token-service hosting and attestation, privacy disclosure wording (draft in `docs/PRIVACY.md`), release monetisation, whether a free Apple ID can create the media identifier/key.


- DEC-019 Proposed: Real engine uses ShazamKit streaming sessions (first match wins, 12 s mic budget, silence reported separately). SDK types live only in `src/shazamkit`; builds without the AAR still work. Token claims in `backend/token-service` are unverified against Apple. Artwork loads only from https Apple/Shazam hosts.
