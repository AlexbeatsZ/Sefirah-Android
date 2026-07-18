package com.castle.sefirah.presentation.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sefirah.domain.model.BluetoothEndpointDescriptor
import sefirah.domain.model.BluetoothHandoffConfiguration
import sefirah.domain.model.BluetoothHandoffState
import sefirah.domain.model.BluetoothHeadsetDescriptor

private enum class BluetoothDeviceSection {
    SelectedConnected,
    OtherConnected,
    SavedDisconnected,
}

@Composable
fun HeadsetHandoffCard(
    configuration: BluetoothHandoffConfiguration,
    state: BluetoothHandoffState?,
    selectedEndpointId: String?,
    onRefresh: () -> Unit,
    onDisconnect: (headsetId: String, endpointId: String) -> Unit,
    onSwitch: (headsetId: String, endpointId: String) -> Unit,
    onVisibilityChanged: (headsetId: String, isVisible: Boolean) -> Unit,
) {
    val busy = state?.status in setOf("disconnecting", "connecting")
    val endpointsById = configuration.endpoints.associateBy { it.id }
    val visibleHeadsets = configuration.headsets.filter { it.isVisible }
    val selectedConnected = visibleHeadsets.filter { it.activeEndpointId == selectedEndpointId }
    val otherConnected = visibleHeadsets.filter {
        it.activeEndpointId != null && it.activeEndpointId != selectedEndpointId
    }
    val savedDisconnected = visibleHeadsets.filter {
        it.activeEndpointId == null && selectedEndpointId != null &&
            (it.endpointIds.isEmpty() || selectedEndpointId in it.endpointIds)
    }
    var expandedHeadsetId by rememberSaveable { mutableStateOf<String?>(null) }
    var showOtherDevices by rememberSaveable { mutableStateOf(true) }
    var showVisibilityDialog by rememberSaveable { mutableStateOf(false) }
    var switchRequest by remember { mutableStateOf<Pair<BluetoothHeadsetDescriptor, String>?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bluetooth devices", style = MaterialTheme.typography.titleMedium)
                    Text(
                        endpointsById[selectedEndpointId]?.displayName ?: "No device selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Bluetooth devices")
                    }
                }
                IconButton(onClick = { showVisibilityDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Visible Bluetooth devices")
                }
            }

            BluetoothSection(
                title = "Connected to selected device",
                headsets = selectedConnected,
                section = BluetoothDeviceSection.SelectedConnected,
                endpointsById = endpointsById,
                selectedEndpointId = selectedEndpointId,
                expandedHeadsetId = expandedHeadsetId,
                busy = busy,
                onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                onDisconnect = onDisconnect,
                onSwitch = { headset, sourceEndpointId -> switchRequest = headset to sourceEndpointId },
            )

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showOtherDevices = !showOtherDevices },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Connected to other devices",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                )
                Icon(
                    if (showOtherDevices) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (showOtherDevices) "Hide section" else "Show section",
                )
            }
            AnimatedVisibility(showOtherDevices) {
                BluetoothSection(
                    title = null,
                    headsets = otherConnected,
                    section = BluetoothDeviceSection.OtherConnected,
                    endpointsById = endpointsById,
                    selectedEndpointId = selectedEndpointId,
                    expandedHeadsetId = expandedHeadsetId,
                    busy = busy,
                    onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                    onDisconnect = onDisconnect,
                    onSwitch = { headset, _ ->
                        selectedEndpointId?.let { onSwitch(headset.id, it) }
                    },
                )
            }

            HorizontalDivider()

            BluetoothSection(
                title = "Saved on selected device",
                subtitle = "Not currently connected",
                headsets = savedDisconnected,
                section = BluetoothDeviceSection.SavedDisconnected,
                endpointsById = endpointsById,
                selectedEndpointId = selectedEndpointId,
                expandedHeadsetId = expandedHeadsetId,
                busy = busy,
                onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                onDisconnect = onDisconnect,
                onSwitch = { headset, _ ->
                    selectedEndpointId?.let { onSwitch(headset.id, it) }
                },
            )

            state?.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    switchRequest?.let { (headset, sourceEndpointId) ->
        val targets = configuration.endpoints.filter { endpoint ->
            endpoint.id != sourceEndpointId &&
                (headset.endpointIds.isEmpty() || endpoint.id in headset.endpointIds)
        }
        var selectedTargetId by remember(headset.id, sourceEndpointId) {
            mutableStateOf(targets.firstOrNull()?.id)
        }
        AlertDialog(
            onDismissRequest = { switchRequest = null },
            title = { Text("Switch ${headset.displayName}") },
            text = {
                Column {
                    targets.forEach { endpoint ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetId = endpoint.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedTargetId == endpoint.id,
                                onClick = { selectedTargetId = endpoint.id },
                            )
                            Text(endpoint.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedTargetId != null,
                    onClick = {
                        selectedTargetId?.let { onSwitch(headset.id, it) }
                        switchRequest = null
                    },
                ) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { switchRequest = null }) { Text("Cancel") }
            },
        )
    }

    if (showVisibilityDialog) {
        AlertDialog(
            onDismissRequest = { showVisibilityDialog = false },
            title = { Text("Visible Bluetooth devices") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose which saved devices appear on the Bluetooth card.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    configuration.headsets.forEach { headset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(headset.displayName, modifier = Modifier.weight(1f))
                            Switch(
                                checked = headset.isVisible,
                                onCheckedChange = { onVisibilityChanged(headset.id, it) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVisibilityDialog = false }) { Text("Done") }
            },
        )
    }
}

@Composable
private fun BluetoothSection(
    title: String?,
    headsets: List<BluetoothHeadsetDescriptor>,
    section: BluetoothDeviceSection,
    endpointsById: Map<String, BluetoothEndpointDescriptor>,
    selectedEndpointId: String?,
    expandedHeadsetId: String?,
    busy: Boolean,
    onExpanded: (String) -> Unit,
    onDisconnect: (headsetId: String, endpointId: String) -> Unit,
    onSwitch: (headset: BluetoothHeadsetDescriptor, sourceEndpointId: String) -> Unit,
    subtitle: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (headsets.isEmpty()) {
            Text(
                "No devices",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        headsets.forEach { headset ->
            val sourceEndpointId = headset.activeEndpointId ?: selectedEndpointId ?: return@forEach
            val endpointName = endpointsById[sourceEndpointId]?.displayName ?: "Another device"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { onExpanded(headset.id) }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(headset.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (section == BluetoothDeviceSection.SavedDisconnected) {
                                "Saved on $endpointName"
                            } else {
                                "Connected to $endpointName"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (expandedHeadsetId == headset.id) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(expandedHeadsetId == headset.id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (section != BluetoothDeviceSection.SavedDisconnected) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = { onDisconnect(headset.id, sourceEndpointId) },
                            ) { Text("Disconnect") }
                        }
                        Button(
                            enabled = !busy && selectedEndpointId != null,
                            onClick = { onSwitch(headset, sourceEndpointId) },
                        ) {
                            Text(
                                when (section) {
                                    BluetoothDeviceSection.SelectedConnected -> "Switch connection"
                                    BluetoothDeviceSection.OtherConnected -> "Switch here"
                                    BluetoothDeviceSection.SavedDisconnected -> "Connect here"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
