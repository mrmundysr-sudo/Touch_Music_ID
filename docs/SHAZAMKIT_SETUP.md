# ShazamKit for Android: what is done, what is blocked, exact steps

**Status (updated 2026-11-02): the real ShazamKit 2.1.1 engine is implemented and compiles in CI with the AAR, but live recognition has NOT been tested** (no token service, no real-device run). The "adapter template" and step 4 below are superseded: the adapter is `app/src/shazamkit/`, enabled automatically when `app/libs/shazamkit-android-release.aar` exists. The app contains the full
recognition architecture and an honest "not configured" state. It never fabricates a match. Demo mode returns clearly
labelled example songs and does not listen.

## What Apple documents (checked 2026-11-02, https://developer.apple.com/shazamkit/android/)
| Item | What the page says |
|---|---|
| Distribution | An **AAR file** to download and place in `libs/` (`flatDir`). **No Maven coordinates.** |
| Documented version | **2.1.1** (changelog: 2.1.0 added 16 KB page-size support; 2.0.2 lowered minSdk to 21) |
| Requirements | minSdk **21**, Kotlin, `INTERNET` permission, `RECORD_AUDIO` to record |
| Auth | An **Apple Developer token** supplied by your own `DeveloperTokenProvider`, created from a *media identifier and private key* ("Create a media identifier and private key" in Apple's docs) |
| Extra dependencies | kotlinx-coroutines 1.8.0, OkHttp 4.12.0, Retrofit 2.11.0, converter-gson 2.11.0 |
| Audio | PCM 16-bit mono at 48000, 44100, 32000 or 16000 Hz; Apple's sample records ~12 s (`catalog.maximumQuerySignatureDurationInMs`) |
| API | `ShazamKit.createSignatureGenerator`, `createShazamCatalog(developerTokenProvider, locale)`, `createSession(catalog)`, `session.match(signature)`; or `createStreamingSession(...)` + `matchStream(...)` + `recognitionResults()` |

**Not stated on that page:** the SDK's license terms; whether downloading the AAR needs a login; whether a free Apple ID
(no paid Developer Program membership) can create the media identifier and key. **Verify these in Apple's developer
portal and terms before relying on them.** This repo is public, so `*.aar` and `app/libs/` are git-ignored: do not commit
Apple's SDK unless Apple's terms allow redistribution.

## Why this build cannot do live recognition
1. The AAR is not obtainable from here (no Maven artifact; Apple's download is not part of this repo).
2. There is no developer token: the owner has only a regular Apple ID and no media identifier/key.
3. Even with 1 and 2, nothing has been tested on a real device with real music. Accuracy (DEC-010: 95%+ goal) is **unmeasured**.

## What the app already has (so only the SDK adapter is missing)
* `recognition/Engine.kt`: `RecognitionEngine` interface (the adapter boundary), `UnavailableEngine`.
* `recognition/RecognitionController.kt`: state machine: permission request/denial, one session at a time, cancel releases the
  mic, 20 s timeout, no-match, error kinds. Unit-tested without any SDK.
* `recognition/TokenSource.kt`: `RemoteTokenSource` fetches a short-lived token from **your HTTPS service**, caches it in memory
  only, refreshes before expiry. HTTPS-only. Tested.
* `recognition/EngineProvider.kt`: picks the real engine when (SDK on classpath AND token service configured), else the
  unavailable engine with the exact reason; demo only if the user turned it on.
* UI for every state: not-configured card, permission denied (incl. "open settings"), listening, identifying, no match, error.
* Manifest: `RECORD_AUDIO`, `INTERNET`; microphone requested only on tap, released on cancel/stop/match; audio never stored.

## Steps to enable live recognition
1. **Apple:** in your Apple Developer account create a media identifier and private key per Apple's guide. Keep the `.p8`
   private key **off the app and off GitHub**.
2. **Token service:** deploy a small HTTPS endpoint that signs short-lived developer tokens with that key. Contract and
   hardening checklist: [TOKEN_SERVICE.md](TOKEN_SERVICE.md). Never use a long-lived token in the app (DEC-007).
3. **SDK:** download Apple's ShazamKit Android AAR (read its terms). Put it at `app/libs/shazamkit-android-release.aar`
   (git-ignored). In `app/build.gradle.kts` add `implementation(files("libs/shazamkit-android-release.aar"))` and the
   dependencies listed above (okhttp, retrofit, converter-gson, coroutines-core).
4. **Adapter:** copy `docs/shazamkit/ShazamKitEngine.kt.template` to
   `app/src/main/kotlin/com/mundymorning/touchmusicid/recognition/ShazamKitEngine.kt`, fix any API mismatches against the
   SDK you downloaded (the template is written from Apple's public page and has **never been compiled**), then pass it to
   `EngineProvider(realEngineFactory = { ShazamKitEngine(it) })` in `TouchMusicIdApp.kt`.
5. **Build with the service URL:** `./gradlew assembleDebug -PTOKEN_SERVICE_URL=https://your-host.example/shazam-token`
6. **Test on a real phone** with real music: quiet and noisy, several genres, no-match (silence/speech), airplane mode
   (should show a network error, not hang), permission denied and "don't ask again", rotate, background mid-listen.
7. Record results in `docs/knowledge/` (testing register). Only then claim recognition works.

## Things that need an owner decision (open in DECISIONS.md)
SDK licensing/redistribution · where the token service is hosted · app attestation (Play Integrity) to stop token abuse ·
privacy disclosure wording for Apple's audio-signature processing · whether to show Apple's artwork URLs (display rules).
