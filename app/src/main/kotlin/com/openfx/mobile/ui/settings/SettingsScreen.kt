package com.openfx.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.core.file.SortBy
import com.openfx.mobile.BuildConfig

/**
 * 設定頁 — 所有項目都是真實設定 (DataStore 持久化)
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val showHidden by viewModel.showHidden.collectAsState()
    val viewGrid by viewModel.viewGrid.collectAsState()
    val sortBy by viewModel.sortBy.collectAsState()
    val sortAscending by viewModel.sortAscending.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val transferNotify by viewModel.transferNotify.collectAsState()

    var showViewModeDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showDeviceNameDialog by remember { mutableStateOf(false) }

    LazyColumn {
        // ===== 檔案管理 =====
        item { SectionHeader("檔案管理") }
        item {
            ToggleSetting(
                "顯示隱藏檔案",
                "以 . 開頭的檔案和資料夾",
                showHidden
            ) { viewModel.setShowHidden(it) }
        }
        item {
            NavigationSetting(
                Icons.Default.GridView,
                "預設檢視模式",
                if (viewGrid) "網格" else "列表"
            ) { showViewModeDialog = true }
        }
        item {
            NavigationSetting(
                Icons.Default.Sort,
                "預設排序方式",
                "${sortByLabel(sortBy)} (${if (sortAscending) "升序" else "降序"})"
            ) { showSortDialog = true }
        }

        // ===== 裝置連線 =====
        item { SectionHeader("裝置連線") }
        item {
            NavigationSetting(
                Icons.Default.PhoneAndroid,
                "裝置名稱",
                deviceName
            ) { showDeviceNameDialog = true }
        }
        item {
            Text(
                "其他 OpenFX 裝置掃描時會看到這個名稱",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ===== 傳輸 =====
        item { SectionHeader("傳輸") }
        item {
            ToggleSetting(
                "傳輸完成通知",
                "接收完成時在通知顯示檔案資訊",
                transferNotify
            ) { viewModel.setTransferNotify(it) }
        }

        // ===== 關於 =====
        item { SectionHeader("關於") }
        item { NavigationSetting(Icons.Default.Info, "版本", BuildConfig.VERSION_NAME) {} }
        item { NavigationSetting(Icons.Default.Code, "原始碼", "github.com/openfx") {} }
        item { NavigationSetting(Icons.Default.Description, "授權條款", "Apache License 2.0") {} }

        item { Spacer(Modifier.height(32.dp)) }
    }

    // ===== 預設檢視模式選擇 =====
    if (showViewModeDialog) {
        AlertDialog(
            onDismissRequest = { showViewModeDialog = false },
            title = { Text("預設檢視模式") },
            text = {
                Column {
                    ChoiceRow("網格", selected = viewGrid) {
                        viewModel.setViewGrid(true)
                        showViewModeDialog = false
                    }
                    ChoiceRow("列表", selected = !viewGrid) {
                        viewModel.setViewGrid(false)
                        showViewModeDialog = false
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showViewModeDialog = false }) { Text("取消") }
            }
        )
    }

    // ===== 預設排序選擇 =====
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("預設排序方式") },
            text = {
                Column {
                    SortBy.entries.forEach { sort ->
                        ChoiceRow(sortByLabel(sort), selected = sortBy == sort) {
                            viewModel.setSortBy(sort)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    ChoiceRow("升序", selected = sortAscending) {
                        viewModel.setSortAscending(true)
                    }
                    ChoiceRow("降序", selected = !sortAscending) {
                        viewModel.setSortAscending(false)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) { Text("完成") }
            }
        )
    }

    // ===== 裝置名稱編輯 =====
    if (showDeviceNameDialog) {
        var name by remember { mutableStateOf(deviceName) }
        AlertDialog(
            onDismissRequest = { showDeviceNameDialog = false },
            title = { Text("裝置名稱") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("例如：Charlie 的手機") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.setDeviceName(name)
                        showDeviceNameDialog = false
                    }
                ) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceNameDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun sortByLabel(sortBy: SortBy): String = when (sortBy) {
    SortBy.NAME -> "名稱"
    SortBy.SIZE -> "大小"
    SortBy.DATE -> "日期"
    SortBy.TYPE -> "類型"
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Spacer(Modifier.padding(horizontal = 12.dp))
        }
        Text(label, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp)
    )
}

@Composable
fun ToggleSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, fontSize = 14.sp) },
        supportingContent = {
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
fun NavigationSetting(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = { Text(title, fontSize = 14.sp) },
        trailingContent = {
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    )
}
