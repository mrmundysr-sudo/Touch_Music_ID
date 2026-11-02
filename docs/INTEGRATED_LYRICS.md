# Integrated Lyrics Architecture

Feature branch: `grok/integrated-lyrics`

## Design
- LRCLIB client lives inside the main app (`lyrics/` package).
- Settings toggle **Enable Online Lyrics** (default OFF).
- Online fetch is allowed only in debug builds (`BuildConfig.DEBUG`). Public release keeps it disabled pending licensing review.
- Local LRC import works independently of the online toggle (session-only, no permanent library).
- Result screen has permanent tabs: Artist Wiki, Song Wiki, Lyrics.
- Existing Lyrics Lab, sync engine, timing estimators and recent fixes on `grok/lyrics-fix` are preserved (debug-only).
- The `lyrics/` package can be removed without affecting ShazamKit recognition, Wikipedia, music-service links, or other tabs.

## Status
Core wiring complete. Full file-picker for local LRC and live device tests remain to be finished. Existing unit tests are unchanged.

## Next
- Wire system file picker for local LRC import.
- Run `./gradlew testDebugUnitTest` and device validation.
- Do not merge to main or release until licensing review is complete.
