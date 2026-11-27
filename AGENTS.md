# Goal
Maintain personal Android fork with root clipboard, reconnect, reviewed controls/presentation and headless management. User authorized replacement on reachable devices and mutual pairing. Origin AlexbeatsZ/Sefirah-Android, upstream shrimqy/Sefirah-Android.

# Current State
- 2026-11-26 Android 3.2.0.10/code48 is published and installed in place on K70, Pad 6 Pro and Pad 4 Plus. Local IDs, public keys, first-install times and storage grants are preserved. Each has five connected peers; all six Android/desktop devices report 30/30 directed links with matching SPKI pins.
- Control and independent transfer TLS pin SHA-256 SPKI derived from saved certificates. CryptoUtils initialization is serialized across instances, verifies private-key possession and persists an atomic no-backup fingerprint marker; unreadable/changed existing identity fails closed. API v2 provides unpair/remove, configuration-preserving explicit rebind and public identity status after saved-peer loading. Read docs/design/device-identity.md first.
- K70/OMEN/ROG explicitly rebound their old Pad 6 Pro key while retaining device records/settings. Pad 4 Plus's five missing local pairings were restored using matching codes against verified peer identities. Detailed receipt: desktop repository tests/reports/device-identity-deployment-20261126.md.
- Network JVM tests pass 20 checks; Pad 6 Pro and Mac each passed 9 live identity/management checks, including renewed certificates, wrong-key refusal, explicit rebind, old-key revocation and online unpair. Existing release signing identity was reused.
- The 2026-11-24 K70 legacy implicit DND rule was deleted from live and persisted policy; next natural boot remains unverified. One-shot control gating and large-image positional fd reads remain included. Do not reboot or drive phone UI solely for acceptance.

# Active Work
- Next natural K70 boot after the legacy DND cleanup is unverified. Do not reboot or drive the daily-use phone UI solely for acceptance.
- All three Android devices are updated and paired; physical UI acceptance remains separate from API and transport checks.
- Long-term background/Doze recovery, actual Bluetooth transitions and wallpaper sharing/revocation UI acceptance remain separate from builds and protocol checks.

# Build / Run / Test
JDK17, Android SDK ~/Library/Android/sdk, compileSdk37. ./gradlew :app:assembleRelease; uv run --no-project tools/sign_release.py reuses .private/Sefirah-release.p12 and release-signing.json. Never commit/regenerate private signing keys. gradlew lacks the exec bit: run `bash ./gradlew`. Install dist/Sefirah-Android-<version>.apk with adb install --no-incremental -r --user current (without --user it also installs into the Private space user 12).
Release validation history is recorded in docs/log/2026-11.md; the 2026-11-24 DND cleanup changed no application code. API: tools/manage.py -s <serial> status/start/stop/connect/pair/approve/disconnect/unpair/remove/rebind/options. Provider requires DUMP and UID0/2000. Pairing requires matching current verification code and uses existing TLS owner.

# Durable Lessons
Stale session callbacks must not disconnect replacement sessions. Explicit local/peer Disconnect stops retry. Reconnect cannot restart a killed host. Root helper drops credentials before ART and exits with host; root grant failures do not prompt repeatedly. Wallpaper publication rechecks consent after async image loading. mDNS ADB ports change; current stable service avoids stale direct addresses. An adb server started inside the command sandbox gets "No route to host" for LAN devices: kill-server and restart it outside the sandbox. Library modules' drawables are overridden by same-named app resources (app ic_devices is an animated-vector; vectorResource on it crashes) — check app/src/main/res before naming new icons. Android 13+ WallpaperManager.getDrawable throws SecurityException without MANAGE_EXTERNAL_STORAGE (target>=34); never swallow it silently. android-smsmms bundles android.net.LinkProperties/LinkAddress/RouteInfo copies: referencing those types makes R8 keep/merge them and the app crashes at startup (IllegalAccessError on RouteInfo$1) — use java.net.NetworkInterface; check the release mapping has no android.net.LinkProperties line. If the phone reboots mid-install, the APK disappears ("missing metadata") and APatch drops the app's su grant; reinstalling the same signed APK keeps data/UID but root must be re-granted in APatch. The phone is in daily use: do not drive its UI (input swipe/tap) for verification; install/screencap only.

# Docs Index
docs/design/device-identity.md: SPKI identity, persistent key checks and local administrative recovery.
docs/fork-management.md: root boundary, API, persistent folder picker and signing.
docs/device-controls.md, docs/device-presentation.md: negotiated protocols.
docs/design/reconnection.md: retry/lifecycle scope, local agent document.
