# Touch Music ID

Hear a song, identify it, discover its story. Android-first, by Mundy Morning Studios.

> **Status: first debug build (0.1.0-dev). Live song recognition is NOT working yet.**
> It needs Apple's ShazamKit Android SDK and a developer token, which don't exist for this project yet. See
> [docs/SHAZAMKIT_SETUP.md](docs/SHAZAMKIT_SETUP.md). Until then the app says so plainly and offers a clearly-labelled **Demo mode**.

## What works today
| Area | State | Evidence |
|---|---|---|
| One-tap Listen UX, permission flow, listening/identifying/no-match/error/not-configured states | Built | UI tests + screenshots |
| Recognition architecture (engine interface, state machine, token-source client) | Built, SDK adapter **not linked** | 22 unit tests |
| Demo mode (example songs, labelled everywhere, no microphone) | Built | UI test |
| Wikipedia + Wikidata facts: conservative matching, sources, attribution, offline cache, ambiguity picker | Built; tested on fixtures **and** against the real APIs in CI (Queen, Bohemian Rhapsody, Prince, nonsense names; run 6). The dev sandbox itself cannot reach Wikipedia | 29 unit tests |
| Apple Music / Spotify / YouTube links (search URLs, allow-listed canonical links), share sheet | Built | unit tests |
| History + favorites (Room), delete/clear, demo cleanup | Built | persistence tests |
| Replaceable artwork slots for 720×1600 art | Built (22 slots, none required) | [manifest](docs/VISUAL_ASSET_MANIFEST.md) + test |
| Settings, privacy, Setup guide | Built | UI tests |

## What is blocked / not done
* **Real recognition**: no ShazamKit AAR, no Apple token, never tested on a device. Accuracy (DEC-010 95%+) is **unmeasured**.
* **Token service**: client built, service not deployed ([docs/TOKEN_SERVICE.md](docs/TOKEN_SERVICE.md)).
* **Device testing**: nothing here has run on a physical phone or emulator. Screenshots are JVM (Robolectric) renderings.
* **Album art, monetization (DEC-009), release signing/Play listing**: out of scope for the first APK.
* English-only Wikipedia/Wikidata; UI strings are not yet in `strings.xml` (no localisation).

## Build and test
Requires JDK 17+ and an Android SDK (platform 35, build-tools 34+).
```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # 76 JVM tests: 73 run by default, 3 live-network tests are opt-in
LIVE_WIKI=1 ./gradlew testDebugUnitTest --tests '*LiveWikiSmokeTest*'   # real Wikipedia/Wikidata
```
UI-flow tests write screenshots to `app/build/screenshots/` (copies in [docs/screenshots](docs/screenshots)).

CI: `.github/workflows/autobuild.yml` runs on every `claude/**` push: unit tests, an informational live-Wikipedia smoke job, and the
debug APK (artifact `Touch-Music-ID-debug-<run>`). The shared hub workflow is not used because this repository is public and the hub
is private; see the work report.

## Layout
```
app/src/main/kotlin/com/mundymorning/touchmusicid/
  recognition/  engine boundary, state machine, demo engine, token client, engine provider
  knowledge/    Wikipedia/Wikidata client, parsers, conservative resolver, cache codec, repository
  data/         Room (history, favorites, knowledge cache), settings
  links/        Apple Music / Spotify / YouTube / Wikipedia links, share
  design/       tokens, theme, ASSET SLOTS, icons, shared components
  ui/           screens, navigation, view-model
docs/           brief, decisions, plan, manifest, ShazamKit + token docs, privacy, knowledge/, screenshots/
```

## Replacing the artwork
Drop `slot_*.png` files into `app/src/main/res/drawable-nodpi/`; no code changes. Names, sizes and rules: [docs/VISUAL_ASSET_MANIFEST.md](docs/VISUAL_ASSET_MANIFEST.md).

## Rules
GitHub is the source of truth. Work on `claude/*` branches. Nothing is merged to `main` or released without the owner's approval. No secrets,
private keys or tokens in the repo or APK. See [CLAUDE.md](CLAUDE.md).

Wikipedia text is CC BY-SA 4.0, Wikidata is CC0. Not affiliated with Apple, Shazam, Spotify, YouTube or the Wikimedia Foundation.
