package com.castle.sefirah.presentation.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.castle.sefirah.navigation.Graph
import com.castle.sefirah.navigation.SettingsRouteScreen
import com.castle.sefirah.presentation.common.components.DevicePreview
import com.castle.sefirah.presentation.settings.components.SwitchPreferenceWidget
import com.castle.sefirah.presentation.settings.components.TextPreferenceWidget
import sefirah.common.R
import sefirah.domain.model.DevicePresentation

/** Entry row on the main settings list; the full editor lives on its own screen. */
@Composable
fun DevicePresentationEntry(viewModel: SettingsViewModel, rootNavController: NavHostController) {
    TextPreferenceWidget(
        title = stringResource(R.string.device_presentation_title),
        subtitle = presentationSummary(viewModel.presentation),
        icon = ImageVector.vectorResource(R.drawable.ic_device_frame),
        onPreferenceClick = { rootNavController.navigate(SettingsRouteScreen.PresentationScreen.route) },
    )
}

@Composable
fun DevicePresentationScreen(rootNavController: NavHostController) {
    val backStackState = rootNavController.currentBackStackEntryAsState().value
    val backStackEntry = remember(backStackState) { rootNavController.getBackStackEntry(Graph.MainScreenGraph) }
    val viewModel: SettingsViewModel = hiltViewModel(backStackEntry)
    val current = viewModel.presentation
    val preview = viewModel.presentationPreview
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) viewModel.selectPresentationImage(it)
    }
    val context = LocalContext.current
    val grantAccess = {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null)))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPresentation() }
    var openChoice by remember { mutableStateOf<PresentationField?>(null) }
    var showSourceDialog by remember { mutableStateOf(false) }

    openChoice?.let { field ->
        ChoiceDialog(
            title = stringResource(field.title),
            choices = field.choices,
            selected = field.value(current),
            label = { presentationLabel(it) },
            onSelect = { viewModel.updatePresentation(field.update(current, it)); openChoice = null },
            onDismiss = { openChoice = null },
        )
    }
    if (showSourceDialog) {
        ChoiceDialog(
            title = stringResource(R.string.presentation_image_source),
            choices = listOf("wallpaper", "userSelected"),
            selected = preview?.wallpaperSource ?: "none",
            label = { sourceLabel(it) },
            onSelect = {
                showSourceDialog = false
                if (it == "wallpaper") viewModel.useSystemWallpaper() else picker.launch("image/*")
            },
            onDismiss = { showSourceDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                title = { Text(stringResource(R.string.device_presentation_title)) },
                navigationIcon = {
                    IconButton(onClick = { rootNavController.navigateUp() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(150.dp), contentAlignment = Alignment.Center) {
                            DevicePreview(preview ?: current, preview?.wallpaper, scale = 2.2f)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(presentationSummary(current), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.device_presentation_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.presentation_section_frame)) }
            PresentationField.entries.forEach { field ->
                item {
                    TextPreferenceWidget(
                        title = stringResource(field.title),
                        subtitle = presentationLabel(field.value(current)),
                        icon = ImageVector.vectorResource(field.icon),
                        onPreferenceClick = { openChoice = field },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.presentation_section_wallpaper)) }
            item {
                SwitchPreferenceWidget(
                    title = stringResource(R.string.share_wallpaper_preview),
                    subtitle = stringResource(R.string.share_wallpaper_preview_subtitle),
                    icon = ImageVector.vectorResource(R.drawable.ic_wallpaper),
                    checked = viewModel.shareWallpaper,
                    onCheckedChanged = {
                        viewModel.shareWallpaper(it)
                        if (it && viewModel.wallpaperAccessMissing) grantAccess()
                    },
                )
            }
            if (viewModel.shareWallpaper && viewModel.wallpaperAccessMissing) item {
                TextPreferenceWidget(
                    title = stringResource(R.string.storage_access_preference),
                    subtitle = stringResource(R.string.presentation_wallpaper_access),
                    icon = ImageVector.vectorResource(R.drawable.ic_folder),
                    onPreferenceClick = { grantAccess() },
                )
            }
            item {
                val enabled = viewModel.shareWallpaper
                TextPreferenceWidget(
                    modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
                    title = stringResource(R.string.presentation_image_source),
                    subtitle = sourceLabel(preview?.wallpaperSource ?: "none"),
                    icon = ImageVector.vectorResource(R.drawable.ic_image),
                    onPreferenceClick = if (enabled) ({ showSourceDialog = true }) else null,
                )
            }
        }
    }
}

private enum class PresentationField(
    val icon: Int,
    val title: Int,
    val choices: List<String>,
    val value: (DevicePresentation) -> String,
    val update: (DevicePresentation, String) -> DevicePresentation,
) {
    Kind(R.drawable.ic_device_frame, R.string.device_presentation_kind, DevicePresentation.kinds, { it.kind }, { p, v -> p.copy(kind = v) }),
    Orientation(R.drawable.ic_screen_rotation, R.string.device_presentation_orientation, DevicePresentation.orientations, { it.orientation }, { p, v -> p.copy(orientation = v) }),
    Ratio(R.drawable.ic_aspect_ratio, R.string.device_presentation_ratio, DevicePresentation.ratios, { it.aspectRatio }, { p, v -> p.copy(aspectRatio = v) }),
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: String,
    label: @Composable (String) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { choice ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = choice == selected, role = Role.RadioButton) { onSelect(choice) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Text(label(choice), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun presentationSummary(value: DevicePresentation): String = listOf(
    presentationLabel(value.kind), presentationLabel(value.orientation), value.aspectRatio
).joinToString(" · ")

@Composable
private fun sourceLabel(value: String): String = when (value) {
    "wallpaper" -> stringResource(R.string.presentation_source_wallpaper)
    "userSelected" -> stringResource(R.string.presentation_source_custom)
    else -> stringResource(R.string.presentation_source_none)
}

@Composable
private fun presentationLabel(value: String): String = when (value) {
    "unknown" -> stringResource(R.string.presentation_unknown)
    "phone" -> stringResource(R.string.presentation_phone)
    "tablet" -> stringResource(R.string.presentation_tablet)
    "computer" -> stringResource(R.string.presentation_computer)
    "portrait" -> stringResource(R.string.presentation_portrait)
    "landscape" -> stringResource(R.string.presentation_landscape)
    else -> value
}
