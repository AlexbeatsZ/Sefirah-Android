# Personal fork: root clipboard and local management

Version 3.2.0.1, protocol baseline 3.2.0. Root clipboard is opt-in and independent of network reconnection. Existing Shizuku/ADB paths remain available.

## Root clipboard

Only an enabled clipboard feature with a connected eligible peer can request root. A bounded su process runs the bundled native starter, which drops all user/group IDs to 2000, removes supplementary groups and capabilities and sets no_new_privs before ART loads. The remaining SELinux domain is supplied by the root implementation; a shell UID alone does not prove a shell SELinux domain. Do not disable SELinux to make it work. APatch on the actual phone still requires runtime acceptance.

Each root launch has a single-use in-memory token. Revoking the option invalidates pending launches, detaches the worker and requests its exit. The worker exits when the host dies. Grant failure falls back without repeated prompts; a registered worker can restart once, then requires an option toggle or a new feature session. Binder callbacks are accepted only from the registered UID/PID; the provider accepts only shell/root registrations. Worker version 2 replaces old payloads. This helper monitors clipboard; it is not a daemon that resurrects the whole application.

## Management API v1

Local ContentProvider `content://com.castle.sefirah.management`, guarded by the platform DUMP permission and an explicit calling UID check for shell/root. Use `adb shell content call --uri content://com.castle.sefirah.management --method status`. Results are JSON inside Bundle.result, with ok/data or ok/error. No private keys are returned.

Methods: status, start, stop, connect, pair, approve, disconnect, options. Pass JSON through --arg for connect/pair/approve/options. connect accepts deviceId and, for a new peer, addresses and port. pair/approve require deviceId and the current verificationCode from status. approve also requires a matching pending request. disconnect takes a device ID directly. options accepts clipboard, rootClipboard and onboardingComplete=true. Start the app/service before connect and wait for a nonempty status. The API reuses existing TLS certificate verification and pairing owners.

The root setting does not grant root itself. Allow Sefirah in APatch/KernelSU/Magisk using their ordinary authorization UI.

## Fork integration

The branch starts with Android reconnect PR #91. Device controls #92 and presentation #93 are reviewed separately and integrated after this change. Agent logs and AGENTS.md remain local; this design document is part of the product source.
