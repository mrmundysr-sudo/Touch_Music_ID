# iOS TestFlight Workflow

This is the operating guide for building and installing Mundy Morning Studios iOS apps through the established GitHub Actions launcher and TestFlight.

## Goal and completion standard

For each app:

1. Confirm its App Store Connect record exists under the Apple Developer account used by the existing GitHub secrets.
2. Dispatch the established workflow with the app's repository, branch, bundle ID, and app-record name.
3. Monitor the complete macOS build, tests, archive, IPA export, upload, and Apple processing.
4. Configure internal TestFlight access for the signed-in owner account and attach the processed build to that internal group.
5. Verify the build appears in the owner's TestFlight app on iPhone before reporting that it is ready.

A successful GitHub run or Apple's `VALID` processing result confirms upload/processing, but does not by itself prove the owner can install the app. This launcher uploads and waits; it does not currently create TestFlight groups or add testers.

This is an internal TestFlight path. Do not submit the build for App Review or Beta App Review, and do not press “Add for Review.” Do not add external testers unless specifically requested.

## Established launcher

- Repository: `mrmundysr-sudo/Touch_Music_ID`
- Workflow: `.github/workflows/portfolio-ios-testflight.yml`
- Run it with the GitHub Actions **Run workflow** button and all four inputs:
  - `source_repository`
  - `source_ref`
  - `bundle_id`
  - `app_name`
- Use the exact source repository and branch supplied for the app. Verify those values in the run log before reporting.
- Existing Apple signing and App Store Connect credentials are stored in GitHub Actions secrets. Use them as configured. Never ask the owner to recreate them unless a run log proves that a specific required secret is missing.
- The archive sets `CURRENT_PROJECT_VERSION=${{ github.run_number }}`; therefore the TestFlight build number is the GitHub Actions run number. The marketing version is separate.
- Do not create a new Apple signing workflow or change signing credentials for a normal app launch.

## App Store Connect app record

Create the app record once in App Store Connect before the first upload for that app. Apple's App Store Connect API does not create the initial app record.

Before dispatch, confirm:

- App name (record name)
- Platform: iOS
- Bundle ID: exact match for the iOS project and workflow input
- SKU: unique and final; Apple does not let you change it after record creation
- User Access: the intended setting, such as Full Access

The record must be in the same Apple Developer account as the existing upload key. A name can be unavailable even when the bundle ID is valid. If Apple rejects the desired name as already in use, use an available record name (for example, “Touch One Beta”), keep the exact bundle ID, and clearly report the difference between the App Store Connect record name and the app's displayed name. Never change a bundle ID to work around a name conflict.

“Full Access” on the app record controls which App Store Connect users can access the app record; it does not by itself add a user to a TestFlight tester group.

## TestFlight test information

For apps using the metadata helper, include `.apple-actions/test-information.json` in the app's source repository. The helper needs the bundle ID, locale, description, and feedback email. Example:

```json
{
  "bundleId": "com.example.app",
  "locale": "en-US",
  "description": "Short beta description",
  "feedbackEmail": "owner@example.com"
}
```

Use the owner's supplied feedback email. Do not assume the feedback email is also the Apple Account email or use it to invite a tester without verifying the signed-in account.

A previous Touch One attempt failed at “Populate TestFlight Test Information” because the feedback email was missing. Another attempt then failed because App Store Connect had no record for the requested bundle ID. Both were fixed without changing or recreating any Apple secrets.

## Internal tester setup after processing

After Apple processing completes:

1. Open the exact app record's **TestFlight** page.
2. Confirm the processed build and build number.
3. Check for an existing internal group. If none is set up for the owner, create a clearly named group such as `Touch One Internal Testers`.
4. Add the owner's currently signed-in, eligible App Store Connect account as an internal tester. Do not assume the feedback email identifies that account.
5. Attach the processed build to the internal group.
6. Confirm the group lists the build and the owner account.
7. Open TestFlight on the iPhone and verify the app is listed for that same Apple Account. If Apple presents an invite or acceptance step, complete it through the normal TestFlight flow.

Use only internal testing for this workflow. Do not create an external testing group or submit for beta review. If the signed-in account is not eligible as an internal tester, report the exact App Store Connect blocker rather than silently inviting another person or switching to external testing.

The remote App Store Connect browser session is separate from Safari on the owner's phone. If the remote session needs authentication, use the secure browser sign-in handoff; never ask for the Apple password in chat. Keep the session scoped to App Store Connect and do not place credentials in GitHub files, logs, ChatGPT handoffs, or app code.

## Build rules

- Keep the bundle ID stable for an existing app.
- Use a unique build number on every upload.
- Keep the marketing version separate from the build number.
- Use iOS 15.0 or newer as the deployment target for this launcher.
- The app icon must be an opaque RGB PNG at 1024×1024 with no alpha channel.
- Preserve any app-specific native patches needed by the source app.

## Encryption compliance

The generated app's `Info.plist` must contain:

```xml
<key>ITSAppUsesNonExemptEncryption</key>
<false/>
```

Inject or verify this before archive. Do not force an already-set encryption value again during upload. App Store Connect can return HTTP 409:

```
You cannot update when the value is already set.
```

If the plist already contains the compliance value, omit the upload action's duplicate encryption override.

## Required run verification

A healthy run includes:

1. Correct source repository, branch, and source commit checked out.
2. Dependencies installed and project tests passed.
3. iOS project generated and native patches applied.
4. Archive succeeded.
5. IPA export succeeded.
6. TestFlight test information populated for the correct bundle ID and app record.
7. Upload succeeded.
8. Apple processing completed successfully.
9. Correct processed build visible in App Store Connect.
10. Owner added to an internal group that has this build.
11. App visible in the owner's TestFlight app on iPhone.

Report the workflow URL, run number/build number, marketing version, source commit, app-record name, bundle ID, processing state, tester/group state, and iPhone TestFlight availability. Never say “ready to test” until steps 9–11 are verified.

## Common failures

### Empty signing values

Example error:

```
-authenticationKeyID is required
```

First inspect the exact failing step and confirm which named secret is empty. Do not ask the owner to recreate any keys or secrets based on guesswork; the existing launcher has already succeeded with the configured credentials.

### No App Store Connect app record

Example error:

```
Unable to find App Store Connect app for bundle id ...
```

Create or verify the app record under the correct Apple Developer account, with the exact bundle ID. Do not change the bundle ID to bypass this error.

### Bundle identifier collision

Example error:

```
ITMS-90685: CFBundleIdentifier Collision
```

Inspect the generated project and remove duplicate embedded targets or duplicate bundle identifiers. Do not create a second App Store Connect record with the same bundle ID.

### Encryption compliance 409

Example error:

```
Failed to update encryption compliance for build.
You cannot update when the value is already set.
```

Keep the plist value and remove the duplicate upload override.

### Build processed but missing from the owner's TestFlight app

Do not rebuild or recreate secrets first. Confirm the exact app record and bundle ID, the processed build number, the signed-in App Store Connect account, its internal tester eligibility, group membership, and that the build is attached to the internal group. Also verify TestFlight on iPhone is using the same Apple Account. Complete any displayed invitation acceptance, then verify the app appears before reporting availability.

## Proven runs

### 7 Come 11

- Source repository: `mrmundysr-sudo/Touch_7_Come_11`
- Source branch: `claude_ios_seven_come_eleven`
- Bundle ID: `com.touch7come11.game`
- App Store Connect record: `7 Come 11`
- SKU: `touch7come11ios`
- Internal TestFlight path; do not use “Add for Review.”

### Touch One

- Source repository: `mrmundysr-sudo/Touch-One`
- Source branch: `claude/ios`
- Source commit: `f8191a36c0ce5a7456ab27c04c23ec3b7c56f081`
- Bundle ID: `com.touchone.game`
- App Store Connect record: `Touch One Beta` (the name “Touch One” was already in use)
- SKU: `touchoneios`
- App Store Connect ID: `6830716342`
- Feedback email in source metadata: `mmundy78@yahoo.com`
- Launcher run: [41850903285](https://github.com/mrmundysr-sudo/Touch_Music_ID/actions/runs/41850903285)
- Version/build: `1.0 (6)`
- Apple processing: `VALID`; status: `Ready to Test`
- Internal group created: `Touch One Internal Testers`
- The signed-in Matthew Mundy App Store Connect account was the sole internal tester, and build `1.0 (6)` was attached to the group.
- The app was confirmed available to that account in TestFlight without beta review, external testers, a rebuild, or credential changes.

Use this document as the starting point for every future iOS app.
