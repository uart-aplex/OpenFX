package com.openfx.app.ui.tv.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.openfx.mobile.R

/**
 * TV 首頁 — Leanback 風格大卡片介面
 * 只在 PlatformDetector.isTv == true 時使用
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeScreen(
    onNavigateToFiles: () -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
) {
    val receiveFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        receiveFocusRequester.requestFocus()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 快速存取列
        item {
            Text("快速存取", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(quickAccessItems) { item ->
                    TvQuickAccessCard(
                        icon = item.icon,
                        label = item.label,
                        description = item.description,
                        accent = item.isReceive,
                        modifier = if (item.isReceive) Modifier.focusRequester(receiveFocusRequester) else Modifier,
                        onClick = {
                            when {
                                item.isReceive -> onNavigateToDevices()
                                item.label == "內部儲存" -> onNavigateToFiles()
                            }
                        }
                    )
                }
            }
        }

        item {
            Text("常用功能", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(fileManagerItems) { item ->
                    TvFileCard(name = item.name, type = item.type, size = item.description, onClick = {
                        if (item.opensFiles) onNavigateToFiles()
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvQuickAccessCard(
    icon: ImageVector,
    label: String,
    description: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.size(width = if (accent) 250.dp else 190.dp, height = 126.dp),
        colors = CardDefaults.colors(
            containerColor = if (accent) Color(0xFF0D6B4D) else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Color.White,
            focusedContainerColor = if (accent) Color(0xFF57E3A1) else Color(0xFFF4F7F5),
            focusedContentColor = Color(0xFF102018)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(8.dp))
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(description, fontSize = 12.sp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFileCard(name: String, type: String, size: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.size(width = 200.dp, height = 140.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(
                imageVector = when (type) {
                    "video" -> Icons.Default.VideoFile; "image" -> Icons.Default.Image
                    "audio" -> Icons.Default.AudioFile; else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null, modifier = Modifier.size(32.dp)
            )
            Column {
                Text(name, fontSize = 14.sp, maxLines = 1)
                Text(size, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDeviceCard(name: String, icon: ImageVector, status: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.size(width = 200.dp, height = 120.dp)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                Text(name, fontSize = 14.sp, maxLines = 1)
                Text(status, fontSize = 11.sp)
            }
        }
    }
}

// 假資料
private data class QuickAccessItem(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val isReceive: Boolean = false
)
private val quickAccessItems = listOf(
    QuickAccessItem(Icons.Default.PhoneAndroid, "手機傳檔到 TV", "開啟接收模式", isReceive = true),
    QuickAccessItem(Icons.Default.Folder, "內部儲存", "瀏覽 TV 檔案"),
    QuickAccessItem(Icons.Default.Usb, "USB 裝置", "瀏覽外接儲存"),
    QuickAccessItem(Icons.Default.Lan, "區域網路", "瀏覽網路裝置"),
    QuickAccessItem(Icons.Default.Download, "下載", "查看下載檔案"),
)
private data class FileManagerItem(
    val name: String,
    val type: String,
    val description: String,
    val opensFiles: Boolean = true
)
private val fileManagerItems = listOf(
    FileManagerItem("播放影片", "video", "選擇本機影片"),
    FileManagerItem("瀏覽照片", "image", "開啟圖片資料夾"),
    FileManagerItem("播放音樂", "audio", "選擇音訊檔案"),
    FileManagerItem("安裝 APK", "apk", "從 Download/OpenFX 開啟")
)
