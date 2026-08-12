# Connection and Privileged Resource Bounds

This document defines the failure-containment rules for long-lived Android connections. These
rules are part of the protocol implementation even when peers use older, capability-compatible
messages.

## Connection lifecycle

- `NetworkManagerImpl` owns at most one Android binding registration for `NetworkService`.
  Repeated boot/activity starts may start the service again, but must not increment the binding
  reference count.
- Each `DeviceConnection` owns exactly one reader and one writer. The reader decodes into a
  bounded application-handler queue (64 frames and 8 MiB) that preserves per-device order.
  Heartbeats bypass that potentially slow queue and are the only concurrently dispatched frame.
  Producers serialize under a per-connection gate before enqueueing.
- The writer has a regular mailbox bounded to 64 frames and 8 MiB plus a reserved control lane
  bounded to eight frames and 64 KiB. Heartbeat and disconnect traffic use the control lane.
- Ordinary event producers use non-blocking enqueue. Mailbox overflow marks the connection
  unhealthy and closes it; silently dropping control messages would corrupt protocol state.
- Bulk snapshots use the acknowledged regular path. An explicit `Disconnect` uses the acknowledged
  control path. Both wait with a bounded timeout so producers receive backpressure and
  `Disconnect` reaches the peer before EOF when the socket remains healthy.
- The first write failure closes the socket and fails queued acknowledgements. Never create one
  coroutine per queued frame or retry every stale frame after a broken pipe.
- Service teardown rejects new connections and closes every accepted connection, including peers
  still waiting for pairing approval; paired-peer disconnect frames share one global deadline.
- `NetworkService` promotes itself to a connected-device foreground service once during creation.
  Connection-state changes update the existing notification and must not call `startForeground()`
  again, because HyperOS may reject background re-promotion and leave the service non-foreground.
- The service requests a partial wake lock while running and releases it during teardown. Liveness
  deadlines use `SystemClock.uptimeMillis()` so deep sleep itself does not age a connection.
  HyperOS may still disable an app wake lock while the screen is off; foreground service and
  `START_STICKY` do not override that OEM policy. The supported recovery path is automatic
  reconnect after the process can run again.

## Input and allocation limits

- Authentication lines are limited to 64 KiB before deserialization. Authenticated protocol lines
  are limited to 8 MiB. A line over its limit terminates that connection.
- At most eight inbound TLS handshakes may be pending, each with a five-second socket timeout.
  Pending sockets must be tracked and closed when the service stops.
- Clipboard text is opt-in per connected peer, limited to 256 KiB, and clips marked
  `EXTRA_IS_SENSITIVE` are never returned by the privileged bridge.
- Bitmap decoding uses encoded-length, dimension, and pixel limits. App and notification icons are
  downscaled, notification encoders are serialized per notification with a global concurrency
  ceiling, media thumbnails are cached and decoded once per render, and wallpaper encoding is
  reused until `ACTION_WALLPAPER_CHANGED`.
- File transfers have a global concurrency ceiling, an acknowledged control announcement, bounded
  accept/read handshakes, and 128-character control frames. Disconnect or service teardown cancels
  the affected jobs. Exceeding the ceiling rejects new work instead of creating more sockets.

## Shizuku boundary

- Shizuku owns ADB/root bootstrap. Sefirah may bind its typed UserService only after the Shizuku
  daemon is alive and the user has authorized the app; it cannot promote itself from a clean state.
- Binder death attempts to destroy the detached UserService before rebinding. Explicit
  `NetworkService` teardown removes the UserService and stops privileged SFTP, while the manager
  remains restartable in the same app process.
- Do not add arbitrary commands, arbitrary Binder transactions, or a generic shell/path API.

## Verification

- JVM regression suites cover mailbox capacity/order/acknowledgement, serialized inbound handling,
  idempotent service binding, bitmap sizing, thumbnail caching, and clipboard privacy boundaries.
- Run `gradlew test :app:assembleDebug` before deployment.
- Deploy to valued devices only with `adb install -r`. Verify one service binding, one privileged
  process when Shizuku is available, foreground-service state, preserved pairing data,
  bidirectional heartbeat after wake, and no repeated broken-pipe storm.
- Long-run acceptance requires dual-peer reconnect pressure plus repeated PSS, Java/native heap,
  Bitmap, thread, and file-descriptor samples. A single low-memory snapshot is not leak proof.
