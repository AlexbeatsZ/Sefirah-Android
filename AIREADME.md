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
- Shizuku `13.6.0.r1086.2650830c` is installed on the Redmi K70 and its adb-mode server was started successfully. Sefirah has `API_V23` permission and binds a live shell-owned `com.castle.sefirah:privileged` UserService, so remaining Bluetooth failures are beyond Shizuku installation/authorization.
- Shizuku cannot be replaced by silently embedding shell/root privilege in an ordinary APK. On a non-root device its server must be started again after reboot through ADB/wireless debugging; trusted-WLAN auto-start can reduce this burden but is still an explicit Shizuku/device configuration.
- Shizuku UserService is not a normal application process, so Android does not initialize the Bluetooth mainline module's `BluetoothServiceManager`. On Redmi K70 this made `BluetoothManager.adapter`, hidden `BluetoothAdapter.createAdapter`, and `getDefaultAdapter()` return null even though the radio was on. Initialize `BluetoothFrameworkInitializer` with `android.os.BluetoothServiceManager`, then bind `bluetooth_manager` directly and cache the resulting adapter.
- The Windows `sefirahctl` path is the physical-device regression harness: `bluetooth list phone` now returns QCY-T13 and QCY AilyBuds Lite with real addresses and connection state, while `bluetooth discover` matches them to the PC catalog. Reinstalling with `adb install -r` retained the existing binding throughout validation.
- The Android Bluetooth card now follows the selected PC and separates connected-to-selected, connected-to-other, and saved-disconnected devices. Rows expand to Disconnect/Switch actions, and a settings dialog persists per-headset visibility.
- The updated debug APK installed with `adb install -r` without clearing app data; the app retained the existing Meta PC binding and the Shizuku UserService returned after restart.
- QCY AilyBuds Lite was physically switched PC to Redmi K70 and back. Android screenshots verified the three groups, expanded action row, and visibility dialog; the final headset connection was restored to the PC.
- Android Chinese localization must include the generic `values-zh`, `values-zh-rCN`, and `values-zh-rTW` resource sets because the Redmi K70 locale resolved to Traditional Chinese resources during physical testing. Keep all three sets complete and update hard-coded accessibility descriptions alongside visible labels.
- Bluetooth action rows use four equal layout slots at 40 dp minimum height and 12 sp text. Rows with fewer actions retain empty slots so every visible action button has the same width; the saved-device action therefore matches the width of actions in the four-button row.
- File transfer and Bluetooth handoff now share the Compose `DeviceSelectionDialog`; this avoids falling back to Xiaomi's native `AlertDialog` styling. `adb install -r` preserved the Meta PC binding, and the final QCY AilyBuds Lite regression ended with PC connected and Redmi K70 disconnected.

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
- [x] Install Shizuku on the Redmi K70 and verify the Sefirah privileged UserService end to end.
- [x] Fix phone Bluetooth catalog discovery in Shizuku UserService and validate both QCY headsets through `sefirahctl`.
- [x] Redesign the Android Bluetooth card around the selected PC with three sections, expandable actions, and visibility settings.
- [x] Install the updated APK in place and physically validate QCY AilyBuds Lite handoff, disconnect, visibility, and live UI state.
- [x] Fully localize the Android UI and accessibility labels into Chinese, including settings, device controls, dialogs, navigation, and default PC actions.
- [x] Standardize Bluetooth action button width/height and enlarge action text while retaining one-row actions.
- [x] Reuse the app-styled file-transfer device selector for Bluetooth “switch to other device”.
- [x] Commit and push the feature branch.
