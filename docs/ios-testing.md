# iOS testing

The **iOS tests** GitHub Actions workflow builds the full Debug iOS app and runs
both `calorietrackerTests` and `calorietrackerUITests` on an Apple Silicon runner.
It runs for relevant pull requests and pushes to `main`, daily, and manually.
It is separate from the existing tag-triggered store release workflows and does
not archive, upload, or submit an app.

## Run locally

Use an Apple Silicon Mac with Xcode 27 or newer and an iOS 26.2 or newer
Simulator runtime:

```sh
bash scripts/test-ios.sh
```

The script creates and later deletes its own simulator. It preserves existing
simulators and physical-device data. Build/package caches are reused under
`~/Library/Developer/Xcode/DerivedData/calorietracker-simulator-tests`. Results
are retained in a unique `TestResults` subdirectory; the command prints the
`.xcresult` path. Override `IOS_TEST_DERIVED_DATA`, `IOS_TEST_PACKAGES`, or
`IOS_TEST_RESULTS` when needed. An existing result bundle is never overwritten.

GitHub uses stable Xcode 27.0 on the `xcode-27` ARM64 runner, caches pinned Swift packages,
and checks out only the small workout image sample needed by tests. LiteRT-LM
does not include an Intel simulator library. Tests run serially to avoid
multiple UI simulators competing for runner resources. Failures fail the job;
`.xcresult` bundles, XCTest screenshots, coverage, and build logs are retained
as the `ios-test-results` artifact for seven days.

Xcode 27 is required to compile the app's iOS 27 FoundationModels image APIs;
a runtime availability check does not make those APIs compile with an older SDK.
The hosted `xcode-27` runner is currently in public preview, so runner capacity
and queue times can vary.

## Coverage and boundaries

- Unit/integration tests cover diary and storage behavior, nutrition, imports,
  workout logic, provider request/response handling, and model-file validation.
  Provider HTTP tests use controlled responses; no paid AI requests are needed.
- UI tests exercise onboarding navigation, manual food entry and persistence,
  water logging/deletion and persistence, settings/provider navigation, and
  workout search/detail navigation. They use real screens and local storage.
- Each UI test starts with isolated app defaults; persistence tests explicitly
  relaunch without resetting. Test startup controls require a Debug simulator
  build and reset only the dedicated Debug app's defaults. They do not delete
  files or Keychain entries. Automatic billing/account refresh, update checks,
  cloud work, and promotional prompts are suppressed during these tests.
- The suite is not a claim of complete app coverage. Real provider accounts,
  camera/HealthKit hardware, iCloud, on-device AI quality, and purchase/restore
  acceptance require separate device or approved store-sandbox testing.

Existing Release archives remain in Xcode Cloud. These Debug simulator tests
do not replace signed Release validation or change any store release switch.
