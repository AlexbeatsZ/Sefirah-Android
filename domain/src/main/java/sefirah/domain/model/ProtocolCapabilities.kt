package sefirah.domain.model

object ProtocolCapabilities {
    const val BLUETOOTH_HANDOFF_V1 = "bluetooth-handoff-v1"
    const val CLIPBOARD_EVENT_V1 = "clipboard-event-v1"
    const val REMOTE_ACTIONS_CONTROLLER_V1 = "remote-actions-controller-v1"
    const val CONNECTION_HEARTBEAT_V1 = "connection-heartbeat-v1"

    val local = listOf(
        BLUETOOTH_HANDOFF_V1,
        CLIPBOARD_EVENT_V1,
        REMOTE_ACTIONS_CONTROLLER_V1,
        CONNECTION_HEARTBEAT_V1,
    )
}
