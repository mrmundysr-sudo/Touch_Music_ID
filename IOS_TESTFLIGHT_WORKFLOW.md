# iOS TestFlight Workflow

This document is the permanent operating guide for building, uploading, and installing Mundy Morning Studios iOS apps through GitHub Actions and TestFlight.

## Goal

The normal workflow is:

1. Developer/AI pushes the app code and workflow changes to GitHub.
2. GitHub Actions runs on a macOS runner.
3. The workflow generates the iOS project, tests it, archives it, exports an IPA, and uploads it to App Store Connect.
4. App Store Connect processes the build.
5. The owner is added as an Internal Tester.
6. TestFlight installs the processed build on the iPhone.

The app is not submitted for App Store review by this workflow.

## Source of truth

- GitHub is the source of truth.
- Confirm the repository, branch, commit, and workflow run before reporting success.
- Do not build from an unknown branch.
- Do not change application code while repairing CI unless the task requires it.
- Do not expose or commit credentials.

## Required GitHub repository secrets

The workflow expects these exact repository secret names:

### Apple signing

- `APPLE_SIGNING_PRIVATE_KEY` — contents of the Apple signing `.p8` key
- `APPLE_SIGNING_KEY_ID` — Apple signing key ID
- `APPLE_SIGNING_ISSUER_ID` — Apple signing issuer ID

### App Store Connect upload

- `APP_STORE_CONNECT_API_PRIVATE_KEY` — contents of the App Store Connect `.p8` key
- `APP_STORE_CONNECT_API_KEY_ID` — App Store Connect API key ID
- `APP_STORE_CONNECT_ISSUER_ID` — App Store Connect issuer ID

Never put the values in this document. Secret names must match the workflow exactly.

## App Store Connect setup required once per app

Before the first upload for a new app:

1. Create the app record in App Store Connect.
2. Use the exact bundle ID from the workflow.
3. Confirm the app record is under the same Apple Developer account used by the API key.
4. Confirm the app's marketing version matches the archive.
5. Add the owner as an Internal Tester.

The App Store Connect API key can upload a build, but it cannot create the initial app record.

## Build rules

- Bundle ID must remain stable for an existing app.
- Every upload must use a unique build number.
- Use `CURRENT_PROJECT_VERSION=${{ github.run_number }}` during archive/export.
- Keep the marketing version separate from the build number.
- The deployment target should be iOS 15.0 or newer.
- The app icon must be a valid opaque 1024x1024 RGB PNG with no alpha channel.

## Encryption compliance

The generated app's `Info.plist` must contain:

```xml
<key>ITSAppUsesNonExemptEncryption</key>
<false/>
```

Inject or verify this before archive.

Do not force an already-set encryption value again during upload. App Store Connect can return HTTP 409:

```
You cannot update when the value is already set.
```

If the plist already contains the compliance value, omit the upload action's `uses-non-exempt-encryption` override.

## Required workflow stages

A healthy run should include:

1. Checkout the intended branch.
2. Install dependencies.
3. Run project tests.
4. Generate the iOS project.
5. Apply native iOS patches.
6. Verify the icon and Info.plist.
7. Build/install/launch in the iOS Simulator when available.
8. Archive the app.
9. Export the signed IPA.
10. Upload the IPA with App Store Connect API credentials.
11. Wait for Apple processing.
12. Report the workflow URL, commit, build number, and processing result.

## Common failures

### Empty signing values

Error:

```
-authenticationKeyID is required
```

Cause: one or more `APPLE_SIGNING_*` secrets are missing or named incorrectly.

Fix: restore the exact six secret names above at repository level.

### No App Store Connect app

Error:

```
Unable to find App Store Connect app for bundle id ...
```

Fix: create the app record in App Store Connect first. Do not change the bundle ID just to bypass this.

### Bundle identifier collision

Error:

```
ITMS-90685: CFBundleIdentifier Collision
```

Fix: inspect the generated project and remove duplicate embedded targets or duplicate bundle identifiers. Do not create a second App Store Connect app with the same bundle ID.

### Encryption compliance 409

Error:

```
Failed to update encryption compliance for build.
You cannot update when the value is already set.
```

Fix: keep the plist value and remove the upload action's duplicate compliance override.

### Build not visible in TestFlight

Wait for Apple processing. Then verify the build under the app's TestFlight page. A successful GitHub upload does not necessarily mean the build is immediately installable.

### TestFlight says “Ready To Test”

This means the phone's TestFlight account has not accepted an invitation yet. Add the Apple ID email as an Internal Tester, then accept the invitation from the email or TestFlight redeem flow.

## Final verification checklist

Do not call the release complete until all applicable checks pass:

- [ ] Correct repository and branch confirmed
- [ ] Correct commit confirmed
- [ ] GitHub Actions run succeeded
- [ ] Simulator/build checks passed
- [ ] Archive succeeded
- [ ] IPA export succeeded
- [ ] Upload succeeded
- [ ] Apple processing completed
- [ ] Correct build number visible in App Store Connect
- [ ] Owner is an Internal Tester
- [ ] Owner can see the app in TestFlight
- [ ] App installs and launches on the iPhone
- [ ] No secrets or private key values were exposed

## Current proven reference

The first successful 7 Come 11 release proved this complete path:

- Repository: `mrmundysr-sudo/Touch_7_Come_11`
- Bundle ID: `com.touch7come11.game`
- App Store Connect app: `7 Come 11`
- App ID: `6830392637`
- TestFlight: internal testing
- Result: build installed and launched successfully on iPhone

Use this document as the starting point for every future iOS app until the workflow is converted into a reusable organization/template workflow.
