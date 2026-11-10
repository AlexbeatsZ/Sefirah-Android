package com.castle.sefirah.presentation.home.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sefirah.common.R
import sefirah.common.util.iconResForAction
import sefirah.domain.model.ActionInfo
import sefirah.presentation.components.Button
import sefirah.presentation.components.TextButton


/** Remote Bluetooth state rendered as a toggle tile next to the desktop actions. */
data class BluetoothTileState(
    val enabled: Boolean?,
    val available: Boolean,
    val pending: Boolean,
    val notice: Int?,
)

private sealed interface ControlTile {
    data object Bluetooth : ControlTile
    data class Action(val action: ActionInfo) : ControlTile
}

@Composable
fun DeviceControlCard(
    actions: List<ActionInfo>,
    onActionClick: (ActionInfo) -> Unit,
    modifier: Modifier = Modifier,
    showLabels: Boolean = true,
    bluetooth: BluetoothTileState? = null,
    onBluetoothClick: () -> Unit = {},
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedAction by remember { mutableStateOf<ActionInfo?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var showUnavailable by remember(bluetooth?.available) { mutableStateOf(false) }

    if (showDialog && selectedAction != null) {
        ActionConfirmationDialog(
            actionName = selectedAction!!.actionName,
            onConfirm = {
                onActionClick(selectedAction!!)
                showDialog = false
                selectedAction = null
            },
            onDismiss = {
                showDialog = false
                selectedAction = null
            }
        )
    }

    val tiles = buildList {
        if (bluetooth != null) add(ControlTile.Bluetooth)
        actions.forEach { add(ControlTile.Action(it)) }
    }
    // Keep a single row for up to five tiles, balance six to eight tiles into two rows
    // (3x2 / 4x2), and only collapse longer lists behind the expand arrow.
    val columns = when {
        tiles.size <= 5 -> 5
        tiles.size == 6 -> 3
        tiles.size <= 8 -> 4
        else -> 5
    }
    val collapsible = tiles.size > 8
    val notice = bluetooth?.notice ?: if (showUnavailable) R.string.bluetooth_control_unavailable else null

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.animateContentSize()
        ) {
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = if (collapsible || notice != null) 0.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val itemsToShow = if (expanded || !collapsible) tiles else tiles.take(columns)
                itemsToShow.chunked(columns).forEach { rowTiles ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        for (i in 0 until columns) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                when (val tile = rowTiles.getOrNull(i)) {
                                    null -> Unit
                                    ControlTile.Bluetooth -> BluetoothControlButton(
                                        state = bluetooth!!,
                                        showLabel = showLabels,
                                        onClick = {
                                            if (bluetooth.available) onBluetoothClick()
                                            else showUnavailable = true
                                        },
                                    )
                                    is ControlTile.Action -> {
                                        val action = tile.action
                                        val customBitmap = remember(action.actionId, action.icon) {
                                            decodeIconIfCustom(action.icon)
                                        }
                                        DeviceControlButton(
                                            iconRes = if (customBitmap == null) iconResForAction(action.icon) else null,
                                            iconBitmap = customBitmap,
                                            name = action.actionName,
                                            showLabel = showLabels,
                                            onClick = {
                                                if (action.askForConfirmation) {
                                                    selectedAction = action
                                                    showDialog = true
                                                } else {
                                                    onActionClick(action)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (notice != null) {
                Text(
                    text = stringResource(notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (collapsible) 0.dp else 12.dp),
                )
            }

            if (collapsible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { expanded = !expanded }
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            if (expanded) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
                        ),
                        contentDescription = if (expanded) "Show less" else "Show more"
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothControlButton(
    state: BluetoothTileState,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.remote_bluetooth)
    val description = stringResource(
        when (state.enabled) {
            true -> R.string.bluetooth_turn_off
            false -> R.string.bluetooth_turn_on
            null -> R.string.remote_bluetooth
        }
    )
    DeviceControlButton(
        name = label,
        description = description,
        iconRes = R.drawable.ic_bluetooth,
        showLabel = showLabel,
        active = state.enabled == true,
        dimmed = !state.available || state.enabled == null || state.pending,
        onClick = if (state.pending) null else onClick,
    )
}

private fun decodeIconIfCustom(icon: String?): ImageBitmap? {
    // Built-in Fluent Names are short; custom icons arrive as base64.
    if (icon.isNullOrBlank() || icon.length < 64) return null
    return runCatching {
        val bytes = Base64.decode(icon, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

@Composable
fun DeviceControlButton(
    name: String,
    onClick: (() -> Unit)?,
    iconRes: Int? = null,
    iconBitmap: ImageBitmap? = null,
    showLabel: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    description: String = name,
    active: Boolean = false,
    dimmed: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tint = if (active) MaterialTheme.colorScheme.onPrimary else color
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.38f else 1f)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (active) Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
                .clickable(
                    enabled = onClick != null,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false, radius = 20.dp),
                    onClick = { onClick?.invoke() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconBitmap != null -> Image(
                    bitmap = iconBitmap,
                    contentDescription = description,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                else -> Icon(
                    painter = painterResource(iconRes ?: iconResForAction(null)),
                    contentDescription = description,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (showLabel) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = onClick != null,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onClick?.invoke() },
                    ),
            )
        }
    }
}

@Composable
fun TimerDialog(
    title: String,
    hours: String,
    minutes: String,
    seconds: String,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    onSecondsChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set timer for $title") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { onHoursChange(it.filter { char -> char.isDigit() }) },
                        label = { Text("Hours") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { onMinutesChange(it.filter { char -> char.isDigit() }) },
                        label = { Text("Minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { onSecondsChange(it.filter { char -> char.isDigit() }) },
                        label = { Text("Seconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ActionConfirmationDialog(
    actionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Action") },
        text = { Text("Are you sure you want to perform the action: $actionName?") },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
