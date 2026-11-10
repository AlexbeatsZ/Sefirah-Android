# Personal fork: root clipboard and local management

Version 3.2.0.1, protocol baseline 3.2.0. Root clipboard is opt-in and independent of network reconnection. Existing Shizuku/ADB paths remain available.

## Root clipboard

Only an enabled clipboard feature with a connected eligible peer can request root. A bounded su process runs the bundled native starter, which drops all user/group IDs to 2000, removes supplementary groups and capabilities and sets no_new_privs before ART loads. The remaining SELinux domain is supplied by the root implementation; a shell UID alone does not prove a shell SELinux domain. Do not disable SELinux to make it work. On 2026-11-10 the PixelOS/APatch K70 registered worker version 2, reported Ready and passed real phone-to-Mac and Mac-to-phone clipboard checks. Its process credentials matched these restrictions; its SELinux domain remained u:r:magisk:s0.

Each root launch has a single-use in-memory token. Revoking the option invalidates pending launches, detaches the worker and requests its exit. The worker exits when the host dies. Grant failure falls back without repeated prompts; a registered worker can restart once, then requires an option toggle or a new feature session. Binder callbacks are accepted only from the registered UID/PID; the provider accepts only shell/root registrations. Worker version 2 replaces old payloads. This helper monitors clipboard; it is not a daemon that resurrects the whole application.

## Management API v1

Local ContentProvider `content://com.castle.sefirah.management`, guarded by the platform DUMP permission and an explicit calling UID check for shell/root. Use `adb shell content call --uri content://com.castle.sefirah.management --method status`. Results are JSON inside Bundle.result, with ok/data or ok/error. No private keys are returned.

Methods: status, start, stop, connect, pair, approve, disconnect, options. Pass JSON through --arg for connect/pair/approve/options. connect accepts deviceId and, for a new peer, addresses and port. pair/approve require deviceId and the current verificationCode from status. approve also requires a matching pending request. disconnect takes a device ID directly. options accepts clipboard, rootClipboard and onboardingComplete=true. Start the app/service before connect and wait for a nonempty status. The API reuses existing TLS certificate verification and pairing owners.

The root setting does not grant root itself. Allow Sefirah in APatch/KernelSU/Magisk using their ordinary authorization UI.

Status also returns rootClipboardStatus and storageLocation. The shell/root-only StoragePickerActivity opens the system document-tree picker at Download/sefirah; the user must confirm the persistent URI permission. The K70 has a newly created Download/sefirah directory and a persisted read/write grant. Force-stop followed by explicit API start preserved its ID, three desktop pairings and folder grant, and restored the root worker. This does not establish recovery from a system low-memory kill or overnight Doze behavior.

## Fork integration

The branch includes Android reconnect PR #91, device controls #92 and presentation #93, paired with desktop #317/#318. Sharing and remote-control consent defaults remain off. Agent logs and AGENTS.md remain local; this design document is part of the product source.

Build with ./gradlew :app:assembleRelease. Run uv run --no-project tools/sign_release.py with the existing .private/Sefirah-release.p12 and .private/release-signing.json. The private directory must remain excluded from Git and protected by user-only filesystem permissions. Losing or replacing this signing identity prevents in-place upgrades. Release APKs are nondebuggable and minified. Update the existing installation with adb install --no-incremental -r, preserving its identity and grants.
