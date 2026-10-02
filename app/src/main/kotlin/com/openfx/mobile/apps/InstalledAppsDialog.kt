package com.openfx.mobile.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.common.util.drawableToImageBitmap
import com.openfx.core.file.FileItem
import com.openfx.mobile.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun InstalledAppsDialog(
    deviceName: String,
    preparing: Boolean,
    status: String?,
    onSend: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: InstalledAppsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val apps = remember(state.apps, query, showSystem) {
        state.apps.filter {
            (showSystem || !it.isSystem) &&
                (it.label.contains(query, true) || it.packageName.contains(query, true))
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f)
                .heightIn(max = 720.dp).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.small
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.apps_installed), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.apps_send_to, deviceName), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = !preparing,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text(stringResource(R.string.apps_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = showSystem,
                        onClick = { if (!preparing) showSystem = !showSystem },
                        label = { Text(stringResource(R.string.apps_show_system)) },
                        enabled = !preparing
                    )
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.apps_selected, selected.size), style = MaterialTheme.typography.labelMedium)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        state.error != null -> Column(Modifier.align(Alignment.Center)) {
                            Text(stringResource(state.error!!))
                            TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.apps_retry)) }
                        }
                        apps.isEmpty() -> Text(stringResource(R.string.apps_empty), Modifier.align(Alignment.Center))
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(apps, key = { it.packageName }) { app ->
                                InstalledAppRow(
                                    app = app,
                                    selected = app.packageName in selected,
                                    enabled = !preparing,
                                    onToggle = {
                                        selected = if (app.packageName in selected) selected - app.packageName
                                        else selected + app.packageName
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                if (preparing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(status ?: stringResource(R.string.apps_preparing), style = MaterialTheme.typography.bodySmall)
                } else if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSend(selected) }, enabled = selected.isNotEmpty() && !preparing && !state.loading) {
                        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.apps_send_count, selected.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(app: InstalledApp, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { drawableToImageBitmap(context.packageManager.getApplicationIcon(app.packageName)) }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) Image(icon!!, null, Modifier.size(40.dp))
        else Icon(Icons.Default.Android, null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = FileItem("", "", app.size, 0, false).formattedSize
            Text(
                "${app.version} · $size${if (app.isSplit) " · ${stringResource(R.string.apps_split)}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
    }
}
