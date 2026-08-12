package com.castle.sefirah.presentation.home.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sefirah.domain.model.BluetoothEndpointDescriptor
import sefirah.domain.model.BluetoothHeadsetDescriptor

class BluetoothHandoffUiPolicyTest {
    @Test
    fun `groups visible devices only by connection state`() {
        val sections = groupBluetoothHeadsets(
            headsets = listOf(
                headset("local", activeEndpointId = "phone"),
                headset("remote", activeEndpointId = "pc"),
                headset("saved"),
                headset("hidden", isVisible = false),
            ),
            headsetsOnly = false,
        )

        assertEquals(listOf("local", "remote"), sections.connected.map { it.id })
        assertEquals(listOf("saved"), sections.disconnected.map { it.id })
    }

    @Test
    fun `headset filter applies to both connection sections`() {
        val sections = groupBluetoothHeadsets(
            headsets = listOf(
                headset("headset", isHeadset = true, activeEndpointId = "phone"),
                headset("keyboard", isHeadset = false),
            ),
            headsetsOnly = true,
        )

        assertEquals(listOf("headset"), sections.connected.map { it.id })
        assertTrue(sections.disconnected.isEmpty())
    }

    @Test
    fun `other targets exclude this device and current connection`() {
        val headset = headset("headset", activeEndpointId = "pc-a")
        val targets = headset.otherTargetEndpoints(
            endpoints = listOf(
                BluetoothEndpointDescriptor("phone", "Phone"),
                BluetoothEndpointDescriptor("pc-a", "PC A"),
                BluetoothEndpointDescriptor("pc-b", "PC B"),
            ),
            localEndpointId = "phone",
        )

        assertEquals(listOf("pc-b"), targets.map { it.id })
    }

    @Test
    fun `endpoint support accepts explicit or legacy endpoint lists`() {
        assertTrue(headset("legacy").supportsEndpoint("phone"))
        assertTrue(headset("explicit", endpointIds = listOf("phone")).supportsEndpoint("phone"))
        assertFalse(headset("explicit", endpointIds = listOf("pc")).supportsEndpoint("phone"))
    }

    private fun headset(
        id: String,
        isVisible: Boolean = true,
        isHeadset: Boolean = true,
        endpointIds: List<String> = emptyList(),
        activeEndpointId: String? = null,
    ) = BluetoothHeadsetDescriptor(
        id = id,
        displayName = id,
        isVisible = isVisible,
        isHeadset = isHeadset,
        endpointIds = endpointIds,
        activeEndpointId = activeEndpointId,
    )
}
