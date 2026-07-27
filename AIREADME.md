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
- Hidden `BluetoothDevice.connect()` / `disconnect()` calls are asynchronous and may block or report acceptance before the profile state changes. Run them on a bounded worker, wait for the requested state, return a precise timeout error, and recycle the Shizuku UserService when the outer Binder call stops responding.
- Shizuku cannot be replaced by silently embedding shell/root privilege in an ordinary APK. On a non-root device its server must be started again after reboot through ADB/wireless debugging; trusted-WLAN auto-start can reduce this burden but is still an explicit Shizuku/device configuration.
- Shizuku UserService is not a normal application process, so Android does not initialize the Bluetooth mainline module's `BluetoothServiceManager`. On Redmi K70 this made `BluetoothManager.adapter`, hidden `BluetoothAdapter.createAdapter`, and `getDefaultAdapter()` return null even though the radio was on. Initialize `BluetoothFrameworkInitializer` with `android.os.BluetoothServiceManager`, then bind `bluetooth_manager` directly and cache the resulting adapter.
- The Windows `sefirahctl` path is the physical-device regression harness: `bluetooth list phone` now returns QCY-T13 and QCY AilyBuds Lite with real addresses and connection state, while `bluetooth discover` matches them to the PC catalog. Reinstalling with `adb install -r` retained the existing binding throughout validation.
- The Android Bluetooth card now follows the selected PC and separates connected-to-selected, connected-to-other, and saved-disconnected devices. Rows expand to Disconnect/Switch actions, and a settings dialog persists per-headset visibility.
- The updated debug APK installed with `adb install -r` without clearing app data; the app retained the existing Meta PC binding and the Shizuku UserService returned after restart.
- QCY AilyBuds Lite was physically switched PC to Redmi K70 and back. Android screenshots verified the three groups, expanded action row, and visibility dialog; the final headset connection was restored to the PC.
- Android Chinese localization must include the generic `values-zh`, `values-zh-rCN`, and `values-zh-rTW` resource sets because the Redmi K70 locale resolved to Traditional Chinese resources during physical testing. Keep all three sets complete and update hard-coded accessibility descriptions alongside visible labels.
- Bluetooth action rows use four equal layout slots at 40 dp minimum height and 12 sp text. Rows with fewer actions retain empty slots so every visible action button has the same width; the saved-device action therefore matches the width of actions in the four-button row.
- File transfer and Bluetooth handoff now share the Compose `DeviceSelectionDialog`; this avoids falling back to Xiaomi's native `AlertDialog` styling. `adb install -r` preserved the Meta PC binding, and the final QCY AilyBuds Lite regression ended with PC connected and Redmi K70 disconnected.
- Remote storage already exports Android storage volumes through SFTP to the Windows Cloud Files provider. The desired follow-up is a per-device selected-share model for Download, QQ, and WeChat folders rather than exporting the full volume.
- `SftpFeature` currently uses `PublickeyAuthenticator { _, _, _ -> true }`, which accepts any public key. Before broader remote-storage use, authentication must bind to the paired desktop identity and every SFTP operation must be confined to canonical paths in the selected-share allowlist.
- Android all-files access still cannot read another app's private `/Android/data/<package>` tree on modern Android. On the tested Redmi K70, usable public exports already include `Download/QQ`, `Download/WeiXin`, `Pictures/QQ`, `Pictures/WeChat`, and related media directories.

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
- [ ] Replace whole-volume SFTP export with capability-gated selected shares for Download/QQ/WeChat while keeping backward compatibility with current `SftpServerInfo.paths`.
- [ ] Reject arbitrary SFTP public keys and enforce a canonical-path allowlist for every read/write/rename/delete operation.
- [x] Install the updated APK in place and physically validate QCY AilyBuds Lite handoff, disconnect, visibility, and live UI state.
- [x] Bound and verify privileged Bluetooth actions, add Binder timeout recovery, bump the UserService generation, and deploy Android v46 in place.
- [x] Fully localize the Android UI and accessibility labels into Chinese, including settings, device controls, dialogs, navigation, and default PC actions.
- [x] Standardize Bluetooth action button width/height and enlarge action text while retaining one-row actions.
- [x] Reuse the app-styled file-transfer device selector for Bluetooth “switch to other device”.
- [x] Commit and push the feature branch.
# 2026-07-24 Bluetooth Catalog and HyperOS Experiment

## Project Goal

- Android 默认上报全部 bonded 蓝牙设备并携带 `isHeadset`，由 UI 提供“全部 / 仅耳机”筛选。
- 改善电脑、服务器、Xiaomi Pad 6 Pro 与 Redmi K70 的长期连接。

## Lessons Learned

- Android 需要在前台服务中同时持有 partial WakeLock 与高性能 Wi-Fi Lock，但这些锁不能覆盖 HyperOS 的厂商级策略。
- 连接碰撞必须按端点 ID 固定选择方向；重连使用 5/10/20/40/60 秒退避，避免失败风暴。
- Xiaomi Pad 6 Pro 的 Sefirah 和无线 ADB TLS 都会在约 20–30 秒失去入站连接；这把问题定位到应用协议之外。
- 标准 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、`deviceidle whitelist` 和 active standby bucket 已在平板验证，但仍未消除断流。
- 设置首页新增“后台连接保护”，在 Xiaomi/Redmi/POCO 上明确提示 HyperOS 还需“无限制”和后台自启动；这改善可发现性，但本轮未证明厂商设置是最终修复。
- Redmi K70 上修复服务器固定证书后，电脑和服务器可同时稳定认证。

## Task Board

- [done] 全部 bonded 蓝牙目录、耳机分类、筛选 UI 与协议序列化测试。
- [done] Shizuku 蓝牙桥、心跳、碰撞策略、退避、WakeLock/Wi-Fi Lock。
- [done] Android v43 构建和全量测试（240 tasks）。
- [done] v43 部署至 Redmi K70 与 Xiaomi Pad 6 Pro；平板回读 versionCode 43、白名单和 active 桶。
- [done] 手机服务器证书固定值经备份和 SHA-256 验证后修复。
- [paused] 平板系统层断流仍未解决；用户要求结束本轮。
- [pending] 后续先恢复 USB MTP/ADB 枚举并抓断流 logcat，再检查 HyperOS 应用省电“无限制”、后台自启动和网络策略。

# 2026-07-26 Android Data / QQ SFTP

## Project Goal

- 通过 Shizuku 让已配对 Windows 设备读取
  `/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv`。

## Lessons Learned

- Android 11+ 的普通应用即使有 all-files access 也不能可靠读取其他应用的 `Android/data`；实际 SFTP 文件系统操作必须落在 Shizuku shell 服务中。
- SFTP 服务生命周期不能直接绑定瞬时设备连接。最后客户端断开后延迟 120 秒关闭，设备重连则取消关闭，以保持端口和凭据稳定。
- 不应使用 connected instrumentation test 验证安装包：测试部署会卸载/重装应用，清除 Room 数据和 Android Keystore；只用 `adb install -r` 做保留数据升级。

## Task Board

- [x] 实现 Shizuku 特权 SFTP 文件系统桥和 Android `data` 路径访问。
- [x] 增加 SFTP 断连宽限期，避免 HyperOS 短暂重连轮换凭据。
- [x] `gradlew test :app:assembleDebug` 构建成功。
- [x] 通过 `adb install -r` 将 v45 部署到 Redmi K70 与 Xiaomi Pad 6 Pro。
- [x] Windows 端成功读取平板 QQ 下载目录中的 PNG 文件；手机目录可枚举且当前无顶层已完成下载。

# 2026-07-28 Privileged Bluetooth Timeout Recovery

## Current State

- Android v46 is installed in place on Xiaomi Pad 6 Pro with app data, pairing, and Shizuku authorization retained.
- A per-device connect now waits up to six seconds for the requested state. The app-side Binder wrapper caps the complete call at nine seconds and removes the stale UserService on timeout.

## Evidence

- Three consecutive attempts to connect the unavailable `84:AC:60:B4:EC:25` headset returned `device_state_timeout` in 6.32–6.36 seconds.
- A full catalog query succeeded immediately after every failed attempt with six devices and no error; the former persistent bridge timeout did not recur.
- `:features:testDebugUnitTest :app:assembleDebug` completed successfully (204 tasks).
