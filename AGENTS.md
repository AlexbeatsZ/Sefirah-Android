# Goal
Maintain personal Android fork with root clipboard, reconnect, reviewed controls/presentation and headless management. User authorized replacement on reachable devices and mutual pairing. Origin AlexbeatsZ/Sefirah-Android, upstream shrimqy/Sefirah-Android.

# Current State
- 2026-11-24 K70 DND recurrence was a persisted legacy `implicit_com.castle.sefirah` rule restored at boot (`readXml`), not a new control command in the retained system history. Removed only that rule through the notification service API; confirmed absent from live rules and `/data/system/notification_policy.xml`, Zen OFF, root clipboard Ready and all five peers connected. Android remains 3.2.0.9/code47. Actual next reboot is untested; see docs/device-controls.md for the cleanup boundary.
- 2026-11-24 Android 3.2.0.9/code47 on the K70: large (>2MB) clipboard images sent to several peers read the shared dup()ed fd with pread (PositionalFdInputStream); before this every peer got partial bytes and timed out. Verified on device: large images and text reach the Mac. Pre-release v3.2.0.9 published.
- 2026-11-23 Android 3.2.0.8/code46 was installed on the K70: manual disconnect is mutual, explicit connects send `userInitiated`, discovery/reconnect are automatic, and refresh no longer sends `Disconnect`. Root clipboard Ready; Mac link verified. Tablets not updated.
- Android 3.2.0.7/code45 is published and installed on the K70. It isolates legacy ringer, DND and stream status to desktop peers and keeps explicit control requests under the existing consent. K70 retained its identity, folder grant and root clipboard, and reconnected to Pad 4 Plus and all three desktops without new ringer/DND writes. Both tablets were not updated during that rollout.

# Active Work
- 2026-11-26 Android 3.2.0.10/code48 built and signed: SPKI pins, serialized fail-closed AndroidKeyStore initialization with atomic no-backup fingerprint marker, API v2 unpair/rebind, saved-peer loading barrier and authenticated asymmetric-pair repair. Network JVM tests pass; physical deployment and pairing acceptance in progress. Read docs/design/device-identity.md before changing identity/recovery.
- Next natural K70 boot after the legacy DND cleanup is unverified. Do not reboot or drive the daily-use phone UI solely for acceptance.
- Both tablets are connected, but their installed versions and physical upgrade acceptance were not checked in the DND incident.
- Long-term background/Doze recovery, actual Bluetooth transitions and wallpaper sharing/revocation UI acceptance remain separate from builds and protocol checks.

# Build / Run / Test
JDK17, Android SDK ~/Library/Android/sdk, compileSdk37. ./gradlew :app:assembleRelease; uv run --no-project tools/sign_release.py reuses .private/Sefirah-release.p12 and release-signing.json. Never commit/regenerate private signing keys. gradlew lacks the exec bit: run `bash ./gradlew`. Install dist/Sefirah-Android-<version>.apk with adb install --no-incremental -r --user current (without --user it also installs into the Private space user 12).
Release validation history is recorded in docs/log/2026-11.md; the 2026-11-24 DND cleanup changed no application code. API: tools/manage.py -s <serial> status/start/stop/connect/pair/approve/disconnect/options. Provider requires DUMP and UID0/2000. Pairing requires matching current verification code and uses existing TLS owner.

# Durable Lessons
Stale session callbacks must not disconnect replacement sessions. Explicit local/peer Disconnect stops retry. Reconnect cannot restart a killed host. Root helper drops credentials before ART and exits with host; root grant failures do not prompt repeatedly. Wallpaper publication rechecks consent after async image loading. mDNS ADB ports change; current stable service avoids stale direct addresses. An adb server started inside the command sandbox gets "No route to host" for LAN devices: kill-server and restart it outside the sandbox. Library modules' drawables are overridden by same-named app resources (app ic_devices is an animated-vector; vectorResource on it crashes) — check app/src/main/res before naming new icons. Android 13+ WallpaperManager.getDrawable throws SecurityException without MANAGE_EXTERNAL_STORAGE (target>=34); never swallow it silently. android-smsmms bundles android.net.LinkProperties/LinkAddress/RouteInfo copies: referencing those types makes R8 keep/merge them and the app crashes at startup (IllegalAccessError on RouteInfo$1) — use java.net.NetworkInterface; check the release mapping has no android.net.LinkProperties line. If the phone reboots mid-install, the APK disappears ("missing metadata") and APatch drops the app's su grant; reinstalling the same signed APK keeps data/UID but root must be re-granted in APatch. The phone is in daily use: do not drive its UI (input swipe/tap) for verification; install/screencap only.

# Docs Index
docs/design/device-identity.md: SPKI identity, persistent key checks and local administrative recovery.
docs/fork-management.md: root boundary, API, persistent folder picker and signing.
docs/device-controls.md, docs/device-presentation.md: negotiated protocols.
docs/design/reconnection.md: retry/lifecycle scope, local agent document.
