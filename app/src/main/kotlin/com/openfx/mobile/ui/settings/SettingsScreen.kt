package com.openfx.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen() {
    var showHidden by remember { mutableStateOf(false) }
    var wifiDirectPriority by remember { mutableStateOf(true) }
    var encrypted by remember { mutableStateOf(true) }
    var requireConfirm by remember { mutableStateOf(true) }
    var transferNotify by remember { mutableStateOf(true) }
    var keepTimestamp by remember { mutableStateOf(true) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 檔案管理
        item { SectionHeader("檔案管理") }
        item { ToggleSetting("顯示隱藏檔案", "以 . 開頭的檔案和資料夾", showHidden) { showHidden = it } }
        item { NavigationSetting(Icons.Default.Folder, "預設儲存路徑", "/sdcard/OpenFX/") {} }
        item { NavigationSetting(Icons.Default.GridView, "預設檢視模式", "網格") {} }
        item { NavigationSetting(Icons.Default.Sort, "預設排序方式", "名稱 (升序)") {} }

        // 裝置連線
        item { SectionHeader("裝置連線") }
        item { NavigationSetting(Icons.Default.PhoneAndroid, "裝置名稱", "Charlie 的手機") {} }
        item { ToggleSetting("Wi-Fi Direct 優先", "直連速度更快，不需要 AP", wifiDirectPriority) { wifiDirectPriority = it } }
        item { ToggleSetting("配對時需要確認", "新裝置連線前需要手動核可", requireConfirm) { requireConfirm = it } }
        item { NavigationSetting(Icons.Default.Timer, "自動斷線逾時", "5 分鐘") {} }

        // 安全性
        item { SectionHeader("安全性") }
        item { ToggleSetting("加密傳輸", "使用 AES-256 加密所有 P2P 傳輸", encrypted) { encrypted = it } }

        // 傳輸
        item { SectionHeader("傳輸") }
        item { NavigationSetting(Icons.Default.Speed, "同時傳輸數量", "2") {} }
        item { NavigationSetting(Icons.Default.Memory, "緩衝區大小", "8 KB") {} }
        item { ToggleSetting("傳輸完成通知", "傳輸結束時發送通知", transferNotify) { transferNotify = it } }
        item { ToggleSetting("保留時間戳記", "複製時保留原始修改時間", keepTimestamp) { keepTimestamp = it } }

        // 關於
        item { SectionHeader("關於") }
        item { NavigationSetting(Icons.Default.Info, "版本", "0.1.0 (Alpha)") {} }
        item { NavigationSetting(Icons.Default.Code, "原始碼", "github.com/openfx") {} }
        item { NavigationSetting(Icons.Default.Description, "授權條款", "Apache License 2.0") {} }

        item { Spacer(Modifier.height(32.dp)) }
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
