package com.castle.sefirah.presentation.home.components

import sefirah.domain.model.BluetoothEndpointDescriptor
import sefirah.domain.model.BluetoothHeadsetDescriptor

internal data class BluetoothHeadsetSections(
    val connected: List<BluetoothHeadsetDescriptor>,
    val disconnected: List<BluetoothHeadsetDescriptor>,
)

internal fun groupBluetoothHeadsets(
    headsets: List<BluetoothHeadsetDescriptor>,
    headsetsOnly: Boolean,
): BluetoothHeadsetSections {
    val visibleHeadsets = headsets.filter {
        it.isVisible && (!headsetsOnly || it.isHeadset)
    }
    val (connected, disconnected) = visibleHeadsets.partition { it.activeEndpointId != null }
    return BluetoothHeadsetSections(connected, disconnected)
}

internal fun BluetoothHeadsetDescriptor.otherTargetEndpoints(
    endpoints: List<BluetoothEndpointDescriptor>,
    localEndpointId: String,
): List<BluetoothEndpointDescriptor> = endpoints.filter { endpoint ->
    endpoint.id != localEndpointId && endpoint.id != activeEndpointId
}

internal fun BluetoothHeadsetDescriptor.supportsEndpoint(endpointId: String?): Boolean =
    endpointId != null && (endpointIds.isEmpty() || endpointId in endpointIds)
