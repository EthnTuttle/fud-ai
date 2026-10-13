# Android testing

The **Quality checks** workflow runs two Android jobs on relevant pull requests,
every push to `main`, daily, and on demand. Changes to Android, shared resources,
workflow configuration, exercise data, or bundled legal notices trigger both jobs.
Release workflows that reuse Quality checks also wait for both jobs to pass.

| Job | Checks |
| --- | --- |
| `android` | Debug JVM unit tests, Release lint, and an unsigned minified Release APK build. |
| `android-instrumentation` | All Debug instrumentation tests, including Compose component tests and full-app UI flows, on a fresh headless Android 16 / API 36 emulator. |

CI's Release build catches R8, resource shrinking, and packaging failures before a
release tag. It does not install or test the Release APK, sign it with a store
key, or upload it. Tag-triggered signed builds and store switches remain separate.

## Run locally

Use JDK 17 and the Android SDK required by `android/app/build.gradle.kts`.
Run unit tests, lint, and the Release packaging check with:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease -PworkoutVectors=none
```

Locally, `assembleRelease` uses an existing `keystore.properties` when present;
CI's quality job has no store signing credentials and produces an unsigned APK.

For instrumentation, create a disposable API 36 emulator and confirm its serial
with `adb devices`. Replace the example serial below with that emulator's serial:

```sh
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -PworkoutVectors=sample
```

The custom `FudAITestRunner` runs the Debug app with isolated test startup, without
automatic billing, account, cloud, update, or health work. App-flow tests reset
their own Debug app data between cases and explicitly preserve it when restarting
or recreating the Activity to check persistence. These checks do not simulate OS
process death. The test runner requires an emulator and the exact
`com.apoorvdarshan.calorietracker.debug` package. Use a disposable emulator;
these resets are not intended for a developer's existing Debug install. The
Release package is not cleared or changed.

GitHub uses hardware acceleration and an x86_64 Google APIs image. An Apple
Silicon Mac can use the corresponding ARM64 image. Both run the same test suite;
the hosted job does not cache emulator app data or snapshots.

## Results and scope

- JVM test reports and Release lint results are in `android-quality-reports`.
- Instrumentation HTML/XML results, captured failure screenshots, Gradle logs,
  and emulator logcat are retained for seven days in
  `android-instrumentation-reports`, including when tests fail. Locally, reports
  are under `android/app/build/reports/androidTests/connected/` and machine-readable
  results under `android/app/build/outputs/androidTest-results/connected/`.
- UI coverage includes onboarding, manual meal persistence, water logging and
  deletion, settings persistence, and workout navigation. Component tests also
  check saved-state restoration, image handling, and workout input behavior.
- This is one emulator/API configuration, not a complete device compatibility
  matrix. Real camera/microphone, Health Connect permissions, Google account
  backup, billing/restore, provider accounts, and on-device model quality require
  separate device or approved sandbox checks. The Whisper dependency contains
  ARM64 native libraries only, so x86_64 CI does not validate Whisper inference.

No test command or CI job here publishes an app or changes store configuration.
