# Touch Music ID — iOS Scaffolding

Native SwiftUI project on branch `grok/ios-testflight`.

## Current state
- Basic SwiftUI app with placeholder for:
  - ShazamKit song identification
  - Prominent album artwork
  - Full-screen blurred album-art background
  - Automatically extracted accent colors
  - Artist Wiki / Song Wiki / Lyrics tabs
  - Accessibility and responsive design

## GitHub Actions
- Workflow `.github/workflows/ios-build.yml` runs on `macos-latest`.
- First compiling build is the priority; signing and TestFlight come after.

## Next steps (requires Apple credentials)
For TestFlight distribution you will need:
1. An Apple Developer Program membership.
2. An App ID for the bundle identifier.
3. A signing certificate and provisioning profile.
4. An App Store Connect API key (or Xcode managed signing).

**Phone-friendly instructions for credentials will be provided once the first compile succeeds.**

Do not merge into main.
