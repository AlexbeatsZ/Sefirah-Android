package sefirah.domain.model

object ProtocolCapabilities {
    const val BLUETOOTH_HANDOFF_V1 = "bluetooth-handoff-v1"
    const val CLIPBOARD_EVENT_V1 = "clipboard-event-v1"

    val local = listOf(
        BLUETOOTH_HANDOFF_V1,
        CLIPBOARD_EVENT_V1,
    )
}
