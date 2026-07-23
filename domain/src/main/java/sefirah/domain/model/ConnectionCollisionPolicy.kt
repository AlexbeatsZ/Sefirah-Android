package sefirah.domain.model

enum class ConnectionDirection {
    Incoming,
    Outgoing,
}

object ConnectionCollisionPolicy {
    fun preferredDirection(localDeviceId: String, remoteDeviceId: String): ConnectionDirection =
        if (localDeviceId < remoteDeviceId) ConnectionDirection.Outgoing else ConnectionDirection.Incoming

    fun shouldAcceptCandidate(
        localDeviceId: String,
        remoteDeviceId: String,
        existingDirection: ConnectionDirection?,
        candidateDirection: ConnectionDirection,
    ): Boolean {
        if (existingDirection == null) return true
        if (existingDirection == candidateDirection) return true

        val preferred = preferredDirection(localDeviceId, remoteDeviceId)
        return existingDirection != preferred && candidateDirection == preferred
    }
}
