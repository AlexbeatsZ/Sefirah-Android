# Sefirah Android Fork Instructions

## Repository and Branch Policy

- This is AlexbeatsZ's Android fork. Keep `origin` pointed at `AlexbeatsZ/Sefirah-Android` and `upstream` at `shrimqy/Sefirah-Android`.
- Keep protocol changes capability-gated and suitable for mixed-version Windows/Android peers.
- Preserve unrelated dirty work. Commit and push only files belonging to the current task.

## Data, Identity, and Upgrade Safety

- Preserve Room data, pairings, Android Keystore identity, settings, and Shizuku authorization across updates.
- Use explicit Room migrations; never enable destructive fallback for a valued installation.
- Use `adb install -r` for data-preserving physical-device deployment. Do not run installation-style instrumentation tests on a device whose pairing or Keystore identity must survive.
- The official signing identity cannot be silently replaced. Complete a one-time secure re-enrollment flow, then keep the fork signing key stable and out of Git.
- ADB wireless TLS ports are dynamic. Discover the current endpoint instead of persisting an IP:port pair.

## Privileged Boundary

- Android public APIs do not provide reliable headset connect/disconnect or background clipboard access on the target Xiaomi devices (currently Android 15-16). Keep privileged actions behind the Shizuku UserService and explicit capability probes.
- Never embed or expose arbitrary shell execution. Every privileged action must be narrowly typed, allowlisted, bounded, and reported with a precise failure.
- Hidden Bluetooth calls are asynchronous. Execute them on bounded workers, wait for the requested state, cap the Binder call, and remove/rebind a stale UserService after timeout.
- Shizuku is an explicit device dependency. Its configured Android 13+ ADB-mode auto-start may recover after reboot, but Sefirah cannot bootstrap a dead Shizuku or silently obtain shell/root on a clean non-root device.
- Keep Sefirah minSdk compatibility when selecting Shizuku artifacts; verify before raising dependency versions.

## Bluetooth and UI Contract

- Report all bonded Bluetooth devices with normalized addresses and category/capability metadata. Headset-only is an optional filter, not the default catalog.
- The selected PC controls grouping and actions. Keep connected-to-selected, connected-to-other, and saved-disconnected states semantically distinct.
- Reuse the app's device-selection component for handoff targets. Visible labels and accessibility descriptions must remain complete in `values`, `values-zh`, `values-zh-rCN`, and `values-zh-rTW` as applicable.
- An accepted connect/disconnect call is not final success. Verify state within bounded time and return actionable timeout errors.

## Remote Storage Security

- Do not treat whole-volume export as the final model. Move toward capability-gated selected shares such as Download, QQ, and WeChat while retaining protocol compatibility during migration.
- Reject arbitrary SFTP public keys. Bind authentication to paired desktop identity.
- Canonicalize and authorize every read, write, rename, and delete path against the selected-share allowlist; prevent traversal and symlink escape.
- Android all-files access does not grant reliable access to another app's private `Android/data` tree. Keep any required privileged filesystem work inside the Shizuku boundary and expose only approved paths.
- Preserve the bounded disconnect grace period needed to avoid credential churn during short HyperOS reconnects.

## Build and Verification

- Use the checked-in Gradle Wrapper and the configured Android SDK/JDK. Do not add machine-specific proxy settings to tracked files.
- Run the smallest relevant unit tests, then `gradlew test :app:assembleDebug` (or the current equivalent) for affected changes.
- Bluetooth changes require timeout/rebind regression tests and physical validation when both Xiaomi devices and QCY headsets are available.
- Security changes require negative tests for unknown keys, traversal, symlink escape, and operations outside selected shares.

## Design References

- [`docs/design/connection-resource-bounds.md`](docs/design/connection-resource-bounds.md): connection backpressure, frame/handshake/allocation ceilings, clipboard privacy, and privileged-service lifecycle. Read it before changing network queues, image snapshots, clipboard polling, service binding, or Shizuku teardown.
- [`docs/design/bluetooth-handoff-ui.md`](docs/design/bluetooth-handoff-ui.md): Bluetooth catalog grouping, labels, endpoint choices, legacy compatibility, and action semantics. Read it before changing the handoff card or its UI policy.

## Active Work

- Implement secure first-time re-enrollment and fixed-signing documentation.
- Replace whole-volume SFTP exposure with capability-gated selected shares.
- Authenticate SFTP using the paired desktop identity and enforce canonical path confinement for every operation.
- Repeat multi-device/headset validation after the re-enrollment path is ready.
- Run a multi-day dual-peer reconnect soak after the bounded connection changes and compare PSS, Bitmap, thread, and file-descriptor baselines.
- Improve the failed Shizuku cold-boot recovery UX without copying ADB credentials or general privilege bootstrap into Sefirah.

## Current State

Android versionCode 52 allows sensitive clipboard clips (`EXTRA_IS_SENSITIVE`, such as verification codes)
to be synchronized to paired devices, while preserving the 256 KiB size boundary. Android versionCode 51
fixed concurrent feature-recipient iteration, serialized feature lifecycle hooks, and improved privileged
bridge binding resilience. Redmi K70 was updated to code 52 on 2026-09-09 preserving data and pairings;
bidirectional clipboard and sensitive-clip sync (e.g. "040006") passed verification. Sefirah Accessibility
remains disabled.

The feature branch contains the Shizuku clipboard/Bluetooth bridge, Bluetooth catalog and handoff UI, bounded privileged-call recovery, localization, and remote-storage groundwork. Privileged readiness is single-flight, and Bluetooth requests fail promptly with `privileged_bridge_unavailable` when the Shizuku binder is absent instead of starting overlapping retry loops. The fork has no GitHub release polling, automatic update prompt, or update screen; runtime repository links target the AlexbeatsZ forks. It carries selected upstream v3.0.1 transfer/contact fixes while retaining the fork's bounded transfer ownership and capability-gated mixed-version protocol. Installed APK versions, device connectivity, Shizuku process state, and hardware state are volatile; inspect them before deployment or physical testing. Keep completed evidence in Git history and tests rather than chronological task boards.

## Durable Lessons

- A notification callback can crash the entire application when it iterates a feature's mutable
  device set concurrently with reconnect processing. Fix the shared collection/lifecycle and
  capture recipients for asynchronous work, rather than catching the exception in one callback.
- Two concurrent Shizuku daemons caused `unable to find token` in the manager and repeated
  UserService exits. A single supported daemon restart restored the bridge; repeating app-level
  binds cannot reconcile separate Shizuku token registries.

- A fire-and-forget coroutine per socket frame turns one broken pipe into a delayed serialization, logging, and memory storm. Keep one bounded writer and fail the whole connection on its first write error.
- A frame-count limit is insufficient when messages contain Base64 images. Serialize under a per-connection gate and bound both frame count and aggregate queued characters.
- Reusing the same `ServiceConnection` does not make repeated `bindService` calls idempotent; each successful bind adds a reference that must be balanced or it can keep `NetworkService` alive after Stop.
- Promote `NetworkService` to foreground once during creation. On HyperOS, calling `startForeground()` again from background connection-state updates can be rejected and leave the service non-foreground; update the existing notification with `notify()` instead.
- OOM crash stacks often identify the allocation victim, not the retained owner. Correlate heap/Bitmap/thread samples with reconnect and queue logs before blaming SSHD or the platform.
- File-transfer progress notifications must be owned by the individual transfer, throttled by
  monotonic time, and cancelled from that transfer's lifecycle so one cancellation cannot leave
  stale notifications or disturb another device's transfer.
- A missing Shizuku binder is a dependency failure, not a reason for every concurrent catalog
  refresh to run its own bind timeout. Check binder liveness first, coalesce readiness work, and
  return a stable capability error that the coordinator can keep visible and non-actionable.
