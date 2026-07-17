# Project Goal

- Maintain a long-lived personal fork of Sefirah Android while keeping changes suitable for upstream pull requests.
- Add Shizuku-assisted Bluetooth headset handoff for Redmi K70 and Xiaomi Pad 6 Pro on Android 15.
- Replace heuristic-only clipboard synchronization with a privileged clipboard listener while retaining safe non-privileged fallbacks.
- Preserve pairings and settings across all future fork updates using a fixed signing key and non-destructive database migrations.

# Lessons Learned

- Current automatic clipboard sync does not listen to clipboard changes. It guesses copy actions from accessibility events, opens `ClipboardChangeActivity`, waits 500 ms, then reads the foreground clipboard; custom copy buttons can bypass the heuristic.
- Android 13+ blocks ordinary apps from toggling Bluetooth, and public A2DP/HFP APIs do not expose connect/disconnect. Shizuku shell capability must be probed on each Xiaomi Android 15 device.
- The app's TLS private key lives in Android Keystore and is non-exportable. A self-signed fork cannot update the official APK or preserve that key on the first migration; use one-time secure re-enrollment, then keep the fork signing key stable.
- Existing Room migrations use `fallbackToDestructiveMigration(false)`; new schema changes must include explicit migrations and tests.
- The new protocol is capability-gated so upstream clients never receive unknown polymorphic messages.
- A Shizuku UserService bridge now probes shell clipboard and Bluetooth capabilities; Android compilation is pending, but use of the standard shared Gradle cache is now authorized.
- Local Android tooling is sufficient for this project: Android Studio 2025.3.2, SDK platforms 36/36.1, platform-tools 37.0.0, accepted SDK licenses, and Scoop Temurin JDK 17.0.19 are present. Build Tools 36.0.0 may be auto-downloaded because AGP 9.0 declares it as the default.
- Do not install Scoop `android-clt` on top of the current Android Studio SDK without planning a migration: its manifest sets `ANDROID_HOME` to the Scoop app directory, which would split the SDK across two roots. Install Android SDK Command-Line Tools into the existing SDK through Android Studio instead if headless `sdkmanager` use becomes necessary.
- Use the checked-in Gradle Wrapper (9.3.0); a global Scoop Gradle installation is unnecessary and would currently resolve to a different version.
- Treat any path shared across projects as global, even when it is under the user profile. Standard long-lived developer installations and caches are acceptable when their exact locations and cleanup boundaries are documented.
- Temurin JDK 17 is installed at `C:\Users\Meta\scoop\apps\temurin17-jdk`; user `JAVA_HOME` and the first Java `Path` entry point to its `current` junction. The obsolete Scoop `openjdk17` package, directory, and stale `Path` entry were removed. Gradle 9.3 successfully launched on Temurin 17.0.19.
- Gradle does not automatically honor the shell's `HTTP_PROXY`/`HTTPS_PROXY`; local builds currently need project-invocation-only JVM proxy properties for `127.0.0.1:7897`. Do not persist this machine-specific proxy in tracked project files.
- Shizuku API/provider 13.1.x requires minSdk 24. Pin both client artifacts to 13.0.0 to retain Sefirah's minSdk 23; all Shizuku APIs used by the privileged bridge are present in 13.0.0.
- The first full Android build installed SDK Build Tools 36.0.0 at `C:\Users\Meta\AppData\Local\Android\Sdk\build-tools\36.0.0`. Gradle distributions and Maven dependencies are shared under `C:\Users\Meta\.gradle`; project build outputs remain under each module's `build` directory.
- `test :app:assembleDebug` passes on Temurin 17.0.19 and Gradle 9.3.0. Four new unit tests pass, and the debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`; do not install this debug-signed APK over an existing official installation when preserving pairings.

# Task Board

- [x] Fork repository and configure `origin`/`upstream` remotes.
- [x] Add Shizuku provider, permission/setup state, and privileged service boundary.
- [x] Add reliable text clipboard polling, duplicate suppression, and accessibility/manual fallback.
- [x] Add Bluetooth device catalog and handoff command execution with radio-cycle fallback.
- [x] Add Android Shizuku setup status and three-endpoint handoff UI.
- [x] Audit the local Android Studio, SDK, JDK, ADB, Gradle Wrapper, and Scoop package availability without changing global tools.
- [x] Replace obsolete Scoop OpenJDK 17.0.2 with Temurin JDK 17.0.19, clean the old configuration, and verify Gradle 9.3 startup.
- [ ] Add one-time secure re-enrollment flow and fixed-signing documentation.
- [x] Run unit tests and assemble the debug APK with the updated JDK/SDK toolchain.
- [ ] Validate on both Android 15 Xiaomi devices and both QCY headsets after the signing/re-enrollment path is ready.
- [x] Commit and push the feature branch.
