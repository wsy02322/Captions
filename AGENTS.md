# AGENTS.md

## Cursor Cloud specific instructions

This is a single-module Android app (`:app`, package `app.captions`) — Kotlin + Jetpack Compose + Hilt, built with Gradle. Standard build/test commands are in `README.md`.

### Environment (already provisioned by the startup update script)
- JDK 21 is installed and used for Gradle (AGP 8.7.3 / Kotlin 2.1.0 are compatible with it).
- The Android SDK (platform-tools, `platforms;android-35`, `build-tools;35.0.0`) lives at `~/android-sdk` and persists in the VM snapshot.
- `local.properties` (git-ignored) is recreated on startup with `sdk.dir=$HOME/android-sdk`; Gradle reads the SDK path from it, so you do not need to export `ANDROID_HOME`.

### Running / validating (no emulator available)
- There is **no `/dev/kvm`** in this VM, so an Android emulator / instrumented (`connectedAndroidTest`) run is not possible. Validate changes with the JVM-based flows instead:
  - Build: `./gradlew :app:assembleDebug` (produces `app/build/outputs/apk/debug/app-debug.apk`).
  - Unit tests: `./gradlew :app:testDebugUnitTest` — these run on the JVM via Robolectric.
  - Lint: `./gradlew :app:lintDebug`.
- UI is exercised via Roborazzi screenshot tests (`app/src/test/.../ScreenshotTest.kt`) that render real Compose screens to PNG. To (re)generate the images run:
  `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`
  Output PNGs land in `app/build/reports/screenshots/`. Without `-Proborazzi.test.record=true` the capture calls are no-ops (they neither record nor verify by default).
