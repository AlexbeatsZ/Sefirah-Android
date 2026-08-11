package sefirah.domain.model

/** Hard resource limits for the newline-delimited control protocol. */
object ProtocolLimits {
    const val AUTHENTICATION_FRAME_MAX_CHARS = 64 * 1024
    const val MESSAGE_FRAME_MAX_CHARS = 8 * 1024 * 1024
    const val OUTGOING_MESSAGE_CAPACITY = 64
    const val OUTGOING_MESSAGE_MAX_QUEUED_CHARS = MESSAGE_FRAME_MAX_CHARS
}
