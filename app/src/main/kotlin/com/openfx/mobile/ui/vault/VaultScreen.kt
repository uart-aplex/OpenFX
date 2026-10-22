package com.openfx.mobile.ui.vault

import androidx.compose.ui.platform.LocalResources
import com.openfx.common.util.hasDocumentPicker
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.openfx.common.ui.dpadFieldNavigation
import com.openfx.common.ui.nextFieldKeyboardActions
import com.openfx.core.vault.VaultEntry
import com.openfx.core.vault.VaultManager
import com.openfx.core.vault.VaultState
import com.openfx.mobile.R
import com.openfx.mobile.ui.browser.InputDialog
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onClose: () -> Unit,
    biometric: VaultBiometricUi? = null
) {
    val state by viewModel.vaultState.collectAsState()
    when (state) {
        VaultState.NOT_CREATED -> SetupContent(viewModel, onClose)
        VaultState.LOCKED -> UnlockContent(viewModel, onClose, biometric)
        VaultState.UNLOCKED -> BrowserContent(viewModel, onClose, biometric)
    }
}

/** 生物辨識相關的入口 (只有手機支援時才提供) */
interface VaultBiometricUi {
    val isEnabled: Boolean
    fun unlock(onKey: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun enable(onDone: (String?) -> Unit)
    fun disable()
}

// ===== 建立保險箱 =====

@Composable
private fun SetupContent(viewModel: VaultViewModel, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val tooShort = stringResource(R.string.vault_password_too_short, VaultManager.MIN_PASSWORD_LENGTH)
    val mismatch = stringResource(R.string.vault_password_mismatch)
    val nextActions = nextFieldKeyboardActions()
    val launchers = rememberBackupLaunchers(viewModel)
    val ui by viewModel.ui.collectAsState()

    fun submit() {
        error = when {
            password.length < VaultManager.MIN_PASSWORD_LENGTH -> tooShort
            password != confirm -> mismatch
            else -> null
        }
        if (error == null) viewModel.create(password.toCharArray()) { error = it }
    }

    CenteredColumn {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.vault_setup_intro), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.vault_setup_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        PasswordField(password, { password = it }, R.string.vault_new_password, ImeAction.Next, nextActions)
        PasswordField(confirm, { confirm = it }, R.string.vault_confirm_password, ImeAction.Done, KeyboardActions(onDone = { submit() }))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.cancel)) }
            Button(onClick = ::submit) { Text(stringResource(R.string.vault_create)) }
        }
        // 重新安裝後可從加密備份還原
        TextButton(onClick = launchers.restore) { Text(stringResource(R.string.vault_restore)) }
        ui.busy?.let {
            Text(it)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        (ui.error ?: ui.message)?.let { Text(it) }
    }
}

// ===== 解鎖 =====

@Composable
private fun UnlockContent(viewModel: VaultViewModel, onClose: () -> Unit, biometric: VaultBiometricUi?) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ui by viewModel.ui.collectAsState()
    val focus = remember { FocusRequester() }

    fun submit() {
        if (password.isEmpty() || ui.busy != null) return
        viewModel.unlock(password.toCharArray()) { error = it }
        password = ""
    }

    fun biometricUnlock() {
        biometric?.unlock(onKey = { key -> viewModel.unlockWithKey(key) { error = it } }, onError = { error = it })
    }

    // 有啟用指紋就直接跳出指紋提示
    LaunchedEffect(Unit) {
        if (biometric?.isEnabled == true) biometricUnlock() else runCatching { focus.requestFocus() }
    }

    CenteredColumn {
        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall)
        PasswordField(
            value = password,
            onChange = { password = it },
            labelRes = R.string.vault_password,
            imeAction = ImeAction.Done,
            actions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.focusRequester(focus)
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ui.busy?.let {
            Text(it)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            if (biometric?.isEnabled == true) {
                OutlinedButton(onClick = ::biometricUnlock) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                }
            }
            Button(onClick = ::submit, enabled = ui.busy == null) { Text(stringResource(R.string.vault_unlock)) }
        }
    }
}

// ===== 瀏覽 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContent(viewModel: VaultViewModel, onClose: () -> Unit, biometric: VaultBiometricUi?) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var showAddMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VaultEntry?>(null) }
    var deleting by remember { mutableStateOf<VaultEntry?>(null) }
    var pendingCapture by remember { mutableStateOf<File?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val launchers = rememberBackupLaunchers(viewModel)
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val hasPicker = remember { hasDocumentPicker(context) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris)
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingCapture
        pendingCapture = null
        if (file != null) {
            if (saved) viewModel.importCapturedPhoto(file) else file.delete()
        }
    }

    BackHandler {
        when {
            ui.preview != null -> viewModel.closePreview()
            !viewModel.up() -> onClose()
        }
    }

    LaunchedEffect(ui.message, ui.error) {
        val text = ui.error ?: ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    // 交給外部 App 開啟解密後的暫存檔
    LaunchedEffect(ui.openFile) {
        val file = ui.openFile ?: return@LaunchedEffect
        viewModel.consumeOpenFile()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            snackbar.showSnackbar(resources.getString(R.string.error_no_app_to_open))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.path.lastOrNull()?.name ?: stringResource(R.string.vault_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.up()) onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.vault_add))
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_add_files)) },
                                leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    // TV 沒有系統檔案選擇器 → 提示改從檔案頁加密
                                    if (hasPicker) pickFiles.launch(arrayOf("*/*"))
                                    else viewModel.showMessage(resources.getString(R.string.vault_tv_add_hint))
                                }
                            )
                            if (hasCamera) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.vault_take_photo)) },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAddMenu = false
                                        val file = viewModel.newCaptureFile()
                                        pendingCapture = file
                                        takePhoto.launch(
                                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dialog_new_folder)) },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                                onClick = {
                                    showAddMenu = false
                                    showNewFolder = true
                                }
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.lock() }) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.vault_lock))
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_change_password)) },
                                onClick = {
                                    showMoreMenu = false
                                    showChangePassword = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_backup)) },
                                onClick = {
                                    showMoreMenu = false
                                    launchers.backup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.vault_restore)) },
                                onClick = {
                                    showMoreMenu = false
                                    confirmRestore = true
                                }
                            )
                            if (biometric != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (biometric.isEnabled) R.string.vault_biometric_disable
                                                else R.string.vault_biometric_enable
                                            )
                                        )
                                    },
                                    onClick = {
                                        showMoreMenu = false
                                        if (biometric.isEnabled) {
                                            biometric.disable()
                                        } else {
                                            biometric.enable { error ->
                                                // 結果用 snackbar 告知
                                                viewModel.showMessage(
                                                    error ?: resources.getString(R.string.vault_biometric_enabled)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            ui.busy?.let {
                Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (ui.entries.isEmpty()) {
                Text(
                    stringResource(R.string.vault_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(ui.entries, key = { it.id }) { entry ->
                    VaultEntryRow(
                        entry = entry,
                        onOpen = { viewModel.view(entry) },
                        onExport = { viewModel.export(entry) },
                        onRename = { renaming = entry },
                        onDelete = { deleting = entry }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNewFolder) {
        InputDialog(
            title = stringResource(R.string.dialog_new_folder),
            placeholder = stringResource(R.string.dialog_folder_name_hint),
            onConfirm = { name ->
                showNewFolder = false
                viewModel.createFolder(name)
            },
            onDismiss = { showNewFolder = false }
        )
    }
    renaming?.let { entry ->
        InputDialog(
            title = stringResource(R.string.dialog_rename),
            placeholder = stringResource(R.string.dialog_rename_hint),
            initialValue = entry.name,
            onConfirm = { name ->
                renaming = null
                viewModel.rename(entry, name)
            },
            onDismiss = { renaming = null }
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.vault_delete_message, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(entry)
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.vault_restore)) },
            text = { Text(stringResource(R.string.vault_restore_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    launchers.restore()
                }) { Text(stringResource(R.string.vault_restore)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (showChangePassword) {
        ChangePasswordDialog(
            onSubmit = { old, new, onDone -> viewModel.changePassword(old, new, onDone) },
            onDismiss = { showChangePassword = false }
        )
    }
    ui.preview?.let { bitmap ->
        Dialog(
            onDismissRequest = viewModel::closePreview,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = ui.previewName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = viewModel::closePreview,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun VaultEntryRow(
    entry: VaultEntry,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val detail = if (entry.isDirectory) {
        stringResource(R.string.folder)
    } else {
        formatSize(entry.size) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(entry.added))
    }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            Icon(
                when {
                    entry.isDirectory -> Icons.Default.Folder
                    VaultViewModel.isImage(entry.name) -> Icons.Default.Image
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.vault_export)) }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onSubmit: (CharArray, CharArray, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val tooShort = stringResource(R.string.vault_password_too_short, VaultManager.MIN_PASSWORD_LENGTH)
    val mismatch = stringResource(R.string.vault_password_mismatch)
    val nextActions = nextFieldKeyboardActions()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.vault_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(old, { old = it }, R.string.vault_current_password, ImeAction.Next, nextActions)
                PasswordField(new, { new = it }, R.string.vault_new_password, ImeAction.Next, nextActions)
                PasswordField(confirm, { confirm = it }, R.string.vault_confirm_password, ImeAction.Done, KeyboardActions())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                error = when {
                    new.length < VaultManager.MIN_PASSWORD_LENGTH -> tooShort
                    new != confirm -> mismatch
                    else -> null
                }
                if (error == null) {
                    busy = true
                    onSubmit(old.toCharArray(), new.toCharArray()) { result ->
                        busy = false
                        if (result == null) onDismiss() else error = result
                    }
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    labelRes: Int,
    imeAction: ImeAction,
    actions: KeyboardActions,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = actions,
        modifier = modifier.fillMaxWidth().dpadFieldNavigation()
    )
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024))
    else -> "%.2f GB".format(size / (1024.0 * 1024 * 1024))
}

/** 備份/還原用的檔案選擇器；TV 常沒有系統檔案選擇器，失敗時退回 Download/OpenFX */
private class BackupLaunchers(
    val backup: () -> Unit,
    val restore: () -> Unit
)

@Composable
private fun rememberBackupLaunchers(viewModel: VaultViewModel): BackupLaunchers {
    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(viewModel::backupTo) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreFrom)
    }
    val context = LocalContext.current
    val picker = remember { hasDocumentPicker(context) }
    return remember(viewModel, picker) {
        BackupLaunchers(
            backup = {
                if (!picker) {
                    viewModel.backupToDownloads()
                } else try {
                    create.launch(viewModel.backupFileName())
                } catch (_: ActivityNotFoundException) {
                    viewModel.backupToDownloads()
                }
            },
            restore = {
                if (!picker) {
                    viewModel.restoreLatestFromDownloads()
                } else try {
                    open.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    viewModel.restoreLatestFromDownloads()
                }
            }
        )
    }
}
