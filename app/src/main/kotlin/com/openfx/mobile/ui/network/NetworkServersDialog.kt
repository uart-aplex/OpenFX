package com.openfx.mobile.ui.network

import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.input.ImeAction
import com.openfx.common.ui.dpadFieldNavigation
import com.openfx.common.ui.nextFieldKeyboardActions
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.core.network.smb.SmbAccessDeniedException
import com.openfx.core.network.smb.SmbAuthException
import com.openfx.core.network.smb.SmbPath
import com.openfx.core.network.smb.SmbServer
import com.openfx.mobile.R

/**
 * 網路位置對話框 — 列出已儲存的 SMB 伺服器，點選後在檔案瀏覽器開啟
 *
 * 手機與 TV 共用 (Material3 對話框可用 D-pad 操作)。
 */
@Composable
fun NetworkServersDialog(
    onOpen: (path: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: NetworkServersViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsState()
    // null = 清單畫面；非 null = 編輯表單 (id 空白代表新增)
    var editing by remember { mutableStateOf<SmbServer?>(null) }

    editing?.let { server ->
        SmbServerForm(
            initial = server,
            viewModel = viewModel,
            onSaved = { saved ->
                editing = null
                onOpen(saved.rootPath)
            },
            onCancel = {
                viewModel.clearTest()
                editing = null
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lan, contentDescription = null) },
        title = { Text(stringResource(R.string.network_title)) },
        text = {
            if (servers.isEmpty()) {
                Text(stringResource(R.string.network_empty_hint))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(servers, key = { it.id }) { server ->
                        ListItem(
                            headlineContent = { Text(server.displayName) },
                            supportingContent = { Text(server.rootPath) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { editing = server }) {
                                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.network_edit))
                                    }
                                    IconButton(onClick = { viewModel.delete(server) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { onOpen(server.rootPath) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.network_open, server.displayName)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { editing = SmbServer(id = "", host = "") }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.network_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    )
}

@Composable
private fun SmbServerForm(
    initial: SmbServer,
    viewModel: NetworkServersViewModel,
    onSaved: (SmbServer) -> Unit,
    onCancel: () -> Unit
) {
    val isNew = initial.id.isBlank()
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var share by remember { mutableStateOf(initial.share) }
    var username by remember { mutableStateOf(initial.username) }
    var domain by remember { mutableStateOf(initial.domain) }
    // 編輯時密碼欄留白 = 保留原密碼
    var password by remember { mutableStateOf("") }
    var passwordTouched by remember { mutableStateOf(isNew) }
    val test by viewModel.test.collectAsState()
    val nextActions = nextFieldKeyboardActions()

    LaunchedEffect(Unit) { viewModel.clearTest() }

    fun current(): SmbServer? {
        val cleanHost = host.trim().removePrefix(SmbPath.SCHEME).trim('/')
        if (cleanHost.isBlank()) return null
        return initial.copy(
            name = name.trim(),
            host = cleanHost,
            port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: SmbPath.DEFAULT_PORT,
            share = share.trim().trim('/', '\\'),
            username = username.trim(),
            domain = domain.trim()
        )
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(if (isNew) R.string.network_add else R.string.network_edit)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.network_host)) },
                    placeholder = { Text("192.168.0.10") },
                    singleLine = true,
                    // Ascii = IME_FLAG_FORCE_ASCII：中文 (注音/拼音) 鍵盤會自動切到英數
                    keyboardOptions = asciiKeyboard,
                    keyboardActions = nextActions,
                    modifier = Modifier.fillMaxWidth().dpadFieldNavigation()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.network_username)) },
                    placeholder = { Text(stringResource(R.string.network_username_hint)) },
                    singleLine = true,
                    keyboardOptions = asciiKeyboard,
                    keyboardActions = nextActions,
                    modifier = Modifier.fillMaxWidth().dpadFieldNavigation()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordTouched = true
                    },
                    label = { Text(stringResource(R.string.network_password)) },
                    placeholder = {
                        if (!isNew) Text(stringResource(R.string.network_password_keep))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    keyboardActions = nextActions,
                    modifier = Modifier.fillMaxWidth().dpadFieldNavigation()
                )

                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text(stringResource(R.string.network_share)) },
                    placeholder = { Text(stringResource(R.string.network_share_hint)) },
                    singleLine = true,
                    keyboardOptions = asciiKeyboard,
                    keyboardActions = nextActions,
                    modifier = Modifier.fillMaxWidth().dpadFieldNavigation()
                )
                // 測試連線找到的共享 — 用按鈕列而不是下拉選單，遙控器比較好選
                val shares = (test as? ConnectionTest.Success)?.shares.orEmpty()
                if (shares.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = share.isBlank(),
                            onClick = { share = "" },
                            label = { Text(stringResource(R.string.network_share_all)) }
                        )
                        shares.forEach { item ->
                            FilterChip(
                                selected = share.equals(item, ignoreCase = true),
                                onClick = { share = item },
                                label = { Text(item) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.network_display_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = nextActions,
                    modifier = Modifier.fillMaxWidth().dpadFieldNavigation()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.network_port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = nextActions,
                        modifier = Modifier.weight(1f).dpadFieldNavigation()
                    )
                    OutlinedTextField(
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text(stringResource(R.string.network_domain)) },
                        singleLine = true,
                        keyboardOptions = asciiKeyboard,
                        keyboardActions = nextActions,
                        modifier = Modifier.weight(1f).dpadFieldNavigation()
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 測試中不要停用按鈕：TV 上被停用的按鈕會失去焦點，焦點跳回表單最上方
                    OutlinedButton(
                        enabled = current() != null,
                        onClick = {
                            current()?.let { viewModel.testConnection(it, password) }
                        }
                    ) { Text(stringResource(R.string.network_test)) }
                    when (val result = test) {
                        ConnectionTest.Running -> CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(start = 12.dp)
                        )
                        is ConnectionTest.Success -> Text(
                            result.shares?.let { pluralStringResource(R.plurals.network_test_ok, it.size, it.size) }
                                ?: stringResource(R.string.network_test_ok_no_list),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        is ConnectionTest.Failure -> Text(
                            testErrorText(result.error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                        null -> Unit
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = current() != null,
                onClick = {
                    current()?.let { server ->
                        // 有測試過就記下伺服器能不能列出共享 (決定共享根目錄能否再往上)
                        val tested = (test as? ConnectionTest.Success)?.let { server.copy(listsShares = it.shares != null) }
                        viewModel.save(tested ?: server, if (passwordTouched) password else null, onSaved)
                    }
                }
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private val asciiKeyboard = KeyboardOptions(
    keyboardType = KeyboardType.Ascii,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Next
)

@Composable
private fun testErrorText(error: Throwable): String = when (error) {
    is SmbAuthException -> stringResource(R.string.smb_error_auth)
    is SmbAccessDeniedException -> stringResource(R.string.smb_error_access, error.target)
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException,
    is java.net.NoRouteToHostException -> stringResource(R.string.smb_error_unreachable, error.message.orEmpty())
    else -> error.message ?: error.javaClass.simpleName
}
