# Remote device controls v1

`DeviceInfo.controls` is an optional extension. It has `version: 1`, `ringerControl`,
`bluetoothControl` (`direct`, `local`, `denied`, `unsupported`), and nullable
`bluetoothEnabled`. Missing state means unknown, not off.

Only send `DeviceControlRequest` and `DeviceControlResult` after receiving version 1.
Requests contain a UUID `requestId`, `control` (`ringer` or `bluetooth`) and integer
`value` (ringer 0/1/2; Bluetooth 0/1). The target validates the paired connection and
its own per-peer opt-in. UUIDs are deduplicated with a bounded cache. Requests are
never queued across reconnects. Results are `applied`, `denied`, `unsupported`,
`invalid`, `local` or `unconfirmed`; `local` requires an explicit user interaction
on the target and is not success. Observe actual state before changing the UI.

Changing the ringer mode is a one-shot request. The desktop sends commands only
on button activation. Connecting, updating the UI and editing the setting locally
on Android never replay a previous selection. The desktop displays the state
reported by the target.

`RingerModeState`, `DndState` and `AudioStreamState` remain compatible with
existing desktops. Android sends them as status only to desktop peers (identified
by their UUID device IDs). Android accepts these legacy messages as commands only
from desktops with the relevant per-peer opt-in. This also prevents an older
Android peer from applying another Android's status during a mixed-version rollout.

Legacy Android DND rules survive an app update. On the K70 on 2026-11-24,
the system restored `implicit_com.castle.sefirah` with `STATE_TRUE` from its
notification policy at boot (`readXml`), although the earlier rollout had
verified Zen OFF. The loaded rule retained an activation timestamp from
2026-11-20. A global Zen OFF observation alone does not establish that an
app-owned rule's persisted condition is inactive.

For recovery from this legacy state, inspect both the live automatic rule and
the persisted notification policy. Remove only `implicit_com.castle.sefirah`
through the notification service's automatic-rule API, using an authorized
privileged caller when necessary. Do not edit the policy XML or remove other
apps' rules. The K70 cleanup verified the rule absent in both places, Zen OFF,
and the sleeping/driving rules preserved. No reboot was performed. This is
device-state cleanup; explicit desktop DND control remains available and can
create the app's implicit rule again when requested.

New Bluetooth control defaults to denied. Android never invokes the deprecated
enable/disable APIs.
When a shell-UID worker (root launcher or Shizuku) is alive, Android declares
`direct` and the worker runs `cmd bluetooth_manager enable|disable` (the same
path as adb shell); `applied` is reported only after the adapter reaches the
requested state. Without that worker it declares `local` and presents a local
confirmation before the system UI. Capabilities are republished when the worker
status changes. macOS declares `direct` (when allowed) and switches power through IOBluetooth's
IOBluetoothPreferenceGet/SetControllerPowerState (no public API; same calls as
blueutil). The bundle declares NSBluetoothAlwaysUsageDescription, otherwise TCC
kills the process on first use; the state is polled every 5 s to republish
changes. Linux declares nothing. No privileged service or new OS tool is installed.

Device deployment, physical Bluetooth transitions and UI acceptance are separate
from compilation and protocol regression checks.

DeviceInfo extensions are partial updates: absent phoneNumbers keeps the existing
list, while [] explicitly clears it. Capability and presentation publishers reuse
the authenticated connection and existing settings owners; neither creates a new
transport or device identity.

The initial capability declaration travels in the existing full DeviceInfo packet.
Later capability-only updates are sent only to v1 peers, because legacy receivers
may treat an omitted avatar or phone-number list as a replacement.
