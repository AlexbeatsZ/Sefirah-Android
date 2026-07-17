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
- A Shizuku UserService bridge now probes shell clipboard and Bluetooth capabilities; Android compilation is pending permission to populate the user-level Gradle/dependency cache.
- Local Android tooling is already sufficient for this project: Android Studio 2025.3.2, SDK platforms 36/36.1, platform-tools 37.0.0, accepted SDK licenses, and Scoop OpenJDK 17 are present. Build Tools 36.0.0 may be auto-downloaded because AGP 9.0 declares it as the default.
- Do not install Scoop `android-clt` on top of the current Android Studio SDK without planning a migration: its manifest sets `ANDROID_HOME` to the Scoop app directory, which would split the SDK across two roots. Install Android SDK Command-Line Tools into the existing SDK through Android Studio instead if headless `sdkmanager` use becomes necessary.
- Use the checked-in Gradle Wrapper (9.3.0); a global Scoop Gradle installation is unnecessary and would currently resolve to a different version.

# Task Board

- [x] Fork repository and configure `origin`/`upstream` remotes.
- [x] Add Shizuku provider, permission/setup state, and privileged service boundary.
- [x] Add reliable text clipboard polling, duplicate suppression, and accessibility/manual fallback.
- [x] Add Bluetooth device catalog and handoff command execution with radio-cycle fallback.
- [x] Add Android Shizuku setup status and three-endpoint handoff UI.
- [x] Audit the local Android Studio, SDK, JDK, ADB, Gradle Wrapper, and Scoop package availability without changing global tools.
- [ ] Add one-time secure re-enrollment flow and fixed-signing documentation.
- [ ] Run unit/build tests after dependency-cache download is authorized, then validate on both Android 15 Xiaomi devices and both QCY headsets.
- [ ] Commit and push the feature branch.
