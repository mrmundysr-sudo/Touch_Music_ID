# Touch Music ID — iOS

Native SwiftUI app (iOS 16+). Brought to feature parity with the Android app as of `grok/android-dynamic-art` (2026-11-03)
plus the on-demand lyrics work from `grok/integrated-lyrics`.

> **Verification status:** the code in this change was written without access to a Swift toolchain. It has **not** been compiled or run.
> The first proof is the GitHub Actions run of `iOS Build` (Core unit tests + unsigned simulator build). Nothing here has run on a physical iPhone.

## Parity with Android

| Area | Android | iOS |
|---|---|---|
| Navigation: Listen / History / Favorites / Settings | yes | yes (`ContentView`) |
| One-tap Listen, listening / identifying / no-match / error / mic-denied states, 20 s timeout | yes | yes (`ListenView`, `ShazamService`) |
| Recognition engine | ShazamKit Android SDK (not linked yet) | ShazamKit (native) |
| Demo mode (labelled example songs, no microphone) | yes | yes |
| Result screen with permanent **Artist Wiki / Song Wiki / Lyrics** tabs | yes | yes (`ResultView`) |
| Wikipedia + Wikidata facts, conservative matching, ambiguity picker, sources + attribution | yes | yes (`Core/Wiki*`, `KnowledgeResolver`) |
| Failure vs. not-found kept separate (a failed lookup is never "not found", never cached) | yes | yes |
| Offline cache + stale fallback (7 d verified / 1 h negative) | Room | JSON file (`FileKnowledgeCache`) |
| Apple Music / Spotify / YouTube links, host allow-list, Spotify copy-search fallback, share | yes | yes (`ExternalLinks`, `ShareLink`) |
| History + favorites, delete, clear, clear demo data | Room | JSON file (`LibraryStore`) |
| Dynamic album-art theme + blurred artwork background | yes | yes (`Theme.swift`) |
| Settings, privacy text, Setup guide | yes | yes |
| Online lyrics (LRCLIB), default OFF, **debug builds only** pending licensing review | yes | yes (`#if DEBUG`; TestFlight/Release builds keep it off) |
| Local LRC import (session only) | button is a placeholder | working file picker |
| Lyrics Lab (debug prototype for synced lyrics) | debug only | **not ported** |
| Replaceable artwork slots (`slot_*.png`) | yes | **not ported** |

## Layout
```
TouchMusicID/
  Core/            Foundation-only logic (shared by the app and the unit tests)
    Models, Http, Knowledge*, Wiki*, Lyrics, ExternalLinks, Library
  ShazamService.swift   ShazamKit + demo engine, mic lifecycle
  AppModel.swift        app state (settings, library, open result)
  Theme.swift           tokens, artwork accents, shared components
  ContentView / ListenView / ResultView / LibraryViews / SettingsView
Tests/CoreTests/   XCTest unit tests for Core (run with `swift test`)
Package.swift      test harness only; the app is built from TouchMusicID.xcodeproj
```

## GitHub Actions
- `.github/workflows/ios-build.yml` (push to `grok/ios-testflight` or `claude/**`, or manual): `swift test` for Core, then an unsigned simulator build.
- `.github/workflows/ios-testflight.yml` (manual): signed archive and TestFlight upload. Unchanged.

## Still needed from the owner
- ShazamKit must be enabled as an App Service for the app's identifier in the Apple Developer account.
- Real-device test of recognition accuracy (Android's 95% goal is likewise unmeasured).

Do not merge into main.
