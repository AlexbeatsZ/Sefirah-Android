package com.castle.sefirah.presentation.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castle.sefirah.R
import com.castle.sefirah.presentation.common.DeviceSelectionDialog
import com.castle.sefirah.presentation.common.DeviceSelectionOption
import sefirah.domain.model.BluetoothEndpointDescriptor
import sefirah.domain.model.BluetoothHandoffConfiguration
import sefirah.domain.model.BluetoothHandoffState
import sefirah.domain.model.BluetoothHeadsetDescriptor

private enum class BluetoothDeviceSection {
    SelectedConnected,
    OtherConnected,
    SavedDisconnected,
}

private data class OtherDeviceSelectionRequest(
    val headsetId: String,
    val headsetName: String,
    val options: List<DeviceSelectionOption>,
)

@Composable
fun HeadsetHandoffCard(
    configuration: BluetoothHandoffConfiguration,
    state: BluetoothHandoffState?,
    selectedEndpointId: String?,
    localEndpointId: String,
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
    var showVisibilityDialog by rememberSaveable { mutableStateOf(false) }
    var otherDeviceRequest by remember { mutableStateOf<OtherDeviceSelectionRequest?>(null) }

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
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.bluetooth_devices),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        endpointsById[selectedEndpointId]?.displayName
                            ?: stringResource(R.string.bluetooth_no_selected_device),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.bluetooth_refresh),
                        )
                    }
                }
                IconButton(onClick = { showVisibilityDialog = true }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.bluetooth_visible_devices),
                    )
                }
            }

            BluetoothSection(
                title = stringResource(R.string.bluetooth_connected_selected),
                headsets = selectedConnected,
                section = BluetoothDeviceSection.SelectedConnected,
                endpoints = configuration.endpoints,
                selectedEndpointId = selectedEndpointId,
                localEndpointId = localEndpointId,
                expandedHeadsetId = expandedHeadsetId,
                busy = busy,
                onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                onDisconnect = onDisconnect,
                onSwitch = onSwitch,
                onSelectOther = { headset, targets ->
                    otherDeviceRequest = OtherDeviceSelectionRequest(
                        headset.id,
                        headset.displayName,
                        targets.map { DeviceSelectionOption(it.id, it.displayName) },
                    )
                },
            )

            HorizontalDivider()

            BluetoothSection(
                title = stringResource(R.string.bluetooth_connected_other),
                headsets = otherConnected,
                section = BluetoothDeviceSection.OtherConnected,
                endpoints = configuration.endpoints,
                selectedEndpointId = selectedEndpointId,
                localEndpointId = localEndpointId,
                expandedHeadsetId = expandedHeadsetId,
                busy = busy,
                onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                onDisconnect = onDisconnect,
                onSwitch = onSwitch,
                onSelectOther = { headset, targets ->
                    otherDeviceRequest = OtherDeviceSelectionRequest(
                        headset.id,
                        headset.displayName,
                        targets.map { DeviceSelectionOption(it.id, it.displayName) },
                    )
                },
            )

            HorizontalDivider()

            BluetoothSection(
                title = stringResource(R.string.bluetooth_saved_disconnected),
                headsets = savedDisconnected,
                section = BluetoothDeviceSection.SavedDisconnected,
                endpoints = configuration.endpoints,
                selectedEndpointId = selectedEndpointId,
                localEndpointId = localEndpointId,
                expandedHeadsetId = expandedHeadsetId,
                busy = busy,
                onExpanded = { expandedHeadsetId = if (expandedHeadsetId == it) null else it },
                onDisconnect = onDisconnect,
                onSwitch = onSwitch,
                onSelectOther = { _, _ -> },
            )

            val statusText = when (state?.status) {
                "disconnecting" -> stringResource(R.string.bluetooth_status_disconnecting)
                "connecting" -> stringResource(R.string.bluetooth_status_connecting)
                "completed" -> stringResource(R.string.bluetooth_status_completed)
                "failed" -> stringResource(R.string.bluetooth_status_failed)
                else -> null
            }
            statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (showVisibilityDialog) {
        AlertDialog(
            onDismissRequest = { showVisibilityDialog = false },
            title = { Text(stringResource(R.string.bluetooth_visible_devices)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.bluetooth_visibility_description),
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
                TextButton(onClick = { showVisibilityDialog = false }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }

    otherDeviceRequest?.let { request ->
        DeviceSelectionDialog(
            title = stringResource(R.string.bluetooth_select_target, request.headsetName),
            options = request.options,
            cancelLabel = stringResource(R.string.cancel),
            onSelected = { target ->
                otherDeviceRequest = null
                onSwitch(request.headsetId, target.id)
            },
            onDismiss = { otherDeviceRequest = null },
        )
    }
}

@Composable
private fun BluetoothSection(
    title: String,
    headsets: List<BluetoothHeadsetDescriptor>,
    section: BluetoothDeviceSection,
    endpoints: List<BluetoothEndpointDescriptor>,
    selectedEndpointId: String?,
    localEndpointId: String,
    expandedHeadsetId: String?,
    busy: Boolean,
    onExpanded: (String) -> Unit,
    onDisconnect: (headsetId: String, endpointId: String) -> Unit,
    onSwitch: (headsetId: String, endpointId: String) -> Unit,
    onSelectOther: (BluetoothHeadsetDescriptor, List<BluetoothEndpointDescriptor>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (headsets.isEmpty()) {
            Text(
                stringResource(R.string.bluetooth_no_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        headsets.forEach { headset ->
            val sourceEndpointId = headset.activeEndpointId ?: selectedEndpointId ?: return@forEach
            val supportsLocal = headset.supportsEndpoint(localEndpointId)
            val supportsSelected = headset.supportsEndpoint(selectedEndpointId)
            val otherTargets = endpoints.filter { endpoint ->
                endpoint.id != sourceEndpointId &&
                    endpoint.id != localEndpointId &&
                    endpoint.id != selectedEndpointId &&
                    headset.supportsEndpoint(endpoint.id)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { onExpanded(headset.id) }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        headset.displayName,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                    val actionCount = when (section) {
                        BluetoothDeviceSection.SelectedConnected -> 3
                        BluetoothDeviceSection.OtherConnected -> 4
                        BluetoothDeviceSection.SavedDisconnected -> 1
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (section) {
                            BluetoothDeviceSection.SelectedConnected -> {
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_switch_to_this_device),
                                    enabled = !busy && sourceEndpointId != localEndpointId && supportsLocal,
                                    onClick = { onSwitch(headset.id, localEndpointId) },
                                )
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_switch_to_other_device),
                                    enabled = !busy && otherTargets.isNotEmpty(),
                                    onClick = { onSelectOther(headset, otherTargets) },
                                )
                                CompactActionButton(
                                    label = stringResource(R.string.disconnect),
                                    enabled = !busy,
                                    outlined = true,
                                    onClick = { onDisconnect(headset.id, sourceEndpointId) },
                                )
                            }

                            BluetoothDeviceSection.OtherConnected -> {
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_switch_to_this_device),
                                    enabled = !busy && sourceEndpointId != localEndpointId && supportsLocal,
                                    onClick = { onSwitch(headset.id, localEndpointId) },
                                )
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_switch_to_selected_device),
                                    enabled = !busy && selectedEndpointId != null &&
                                        sourceEndpointId != selectedEndpointId && supportsSelected,
                                    onClick = { selectedEndpointId?.let { onSwitch(headset.id, it) } },
                                )
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_switch_to_other_device),
                                    enabled = !busy && otherTargets.isNotEmpty(),
                                    onClick = { onSelectOther(headset, otherTargets) },
                                )
                                CompactActionButton(
                                    label = stringResource(R.string.disconnect),
                                    enabled = !busy,
                                    outlined = true,
                                    onClick = { onDisconnect(headset.id, sourceEndpointId) },
                                )
                            }

                            BluetoothDeviceSection.SavedDisconnected -> {
                                CompactActionButton(
                                    label = stringResource(R.string.bluetooth_connect_to_selected_device),
                                    enabled = !busy && selectedEndpointId != null && supportsSelected,
                                    onClick = { selectedEndpointId?.let { onSwitch(headset.id, it) } },
                                )
                            }
                        }
                        repeat(4 - actionCount) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun BluetoothHeadsetDescriptor.supportsEndpoint(endpointId: String?): Boolean =
    endpointId != null && (endpointIds.isEmpty() || endpointId in endpointIds)

@Composable
private fun RowScope.CompactActionButton(
    label: String,
    enabled: Boolean,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val modifier = Modifier
        .weight(1f)
        .heightIn(min = 40.dp)
    val contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)
    val labelSize = 12.sp
    val content: @Composable () -> Unit = {
        Text(
            text = label,
            fontSize = labelSize,
            letterSpacing = (-0.25).sp,
            maxLines = 1,
        )
    }

    if (outlined) {
        OutlinedButton(
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding,
            onClick = onClick,
            content = { content() },
        )
    } else {
        Button(
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding,
            onClick = onClick,
            content = { content() },
        )
    }
}
