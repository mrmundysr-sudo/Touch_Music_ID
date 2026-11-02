# Touch Music ID — Product Vision
Status: Approved for initial Android APK development, 2026-11-01.

## Promise
Hear a song, identify it, discover its story. A polished, Android-first music recognition and factual discovery app for Mundy Morning Studios.

## User experience
One-tap listening -> accurate recognition result -> song/artist/album -> verified Wikipedia and Wikidata artist/music facts -> outbound Apple Music, Spotify, and YouTube links -> save to local history/favorites -> share.

## Differentiator
Song recognition is the entry point; the ongoing discovery experience is the product. Avoid making a simple Shazam clone. Provide useful, cited factual context and handle ambiguous artist matches honestly.

## Scope
Official ShazamKit for Android integration where currently supported, with developer credentials supplied later. Wikipedia/Wikidata live integration, cache and attribution, history/favorites, original professional UI, privacy-forward microphone permissions, clear loading/permission/offline/error states. Optional daily unlock monetization architecture; no actual ads or payments required for first APK.

## Visual production
High-quality first-pass interface with replaceable asset slots, centralized theme tokens, asset manifest, scalable layouts, original iconography. Later art direction uses 720x1600 portrait assets without rewiring business logic.

## Guardrails
Do not ship Apple private signing keys, API secrets, copyrighted album art bundles, or copied Shazam branding. Clearly distinguish test/demo recognition from actual ShazamKit recognition. Target 95%+ accuracy as a future measured goal, not a guarantee. Do not imply verified live recognition without real device/token testing.

See MUSIC_ID_BUILD_BRIEF.md and CLAUDE.md.