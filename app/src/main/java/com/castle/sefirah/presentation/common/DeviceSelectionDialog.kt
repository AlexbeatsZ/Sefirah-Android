package com.castle.sefirah.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class DeviceSelectionOption(
    val id: String,
    val displayName: String,
    val isEnabled: Boolean = true,
    val supportingText: String? = null,
)

/**
 * App-styled device selector shared by file transfer and Bluetooth handoff.
 */
@Composable
fun DeviceSelectionDialog(
    title: String,
    options: List<DeviceSelectionOption>,
    cancelLabel: String,
    onSelected: (DeviceSelectionOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = option.isEnabled) { onSelected(option) }
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = option.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option.isEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        option.supportingText?.let { supportingText ->
                            Text(
                                text = supportingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelLabel)
            }
        },
    )
}
