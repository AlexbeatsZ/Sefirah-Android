package com.castle.sefirah.presentation.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sefirah.domain.model.BluetoothHandoffConfiguration
import sefirah.domain.model.BluetoothHandoffState

@Composable
fun HeadsetHandoffCard(
    configuration: BluetoothHandoffConfiguration,
    state: BluetoothHandoffState?,
    onSwitch: (headsetId: String, endpointId: String) -> Unit,
) {
    val busy = state?.status in setOf("disconnecting", "connecting")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Headphones, contentDescription = null)
                Text("Headset handoff", style = MaterialTheme.typography.titleMedium)
                if (busy) CircularProgressIndicator()
            }
            configuration.headsets.forEach { headset ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(headset.displayName, style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        configuration.endpoints.forEach { endpoint ->
                            AssistChip(
                                enabled = !busy && endpoint.id != headset.activeEndpointId,
                                onClick = { onSwitch(headset.id, endpoint.id) },
                                label = {
                                    Text(
                                        if (endpoint.id == headset.activeEndpointId) {
                                            "${endpoint.displayName} · connected"
                                        } else {
                                            endpoint.displayName
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
            state?.message?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
