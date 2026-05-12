package com.openfx.app.ui.tv.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                "OpenFX",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 快速存取列
        item {
            Text("快速存取", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(quickAccessItems) { item ->
                    TvQuickAccessCard(icon = item.icon, label = item.label, onClick = {
                        when (item.label) {
                            "內部儲存" -> onNavigateToFiles()
                            "手機連線" -> onNavigateToDevices()
                        }
                    })
                }
            }
        }

        // 最近使用
        item {
            Text("最近使用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recentFiles) { file ->
                    TvFileCard(name = file.name, type = file.type, size = file.size, onClick = { /* TODO */ })
                }
            }
        }

        // 已連線裝置
        item {
            Text("已連線裝置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp, top = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(connectedDevices) { device ->
                    TvDeviceCard(name = device.name, icon = device.icon, status = device.status, onClick = { /* TODO */ })
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvQuickAccessCard(icon: ImageVector, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.size(width = 180.dp, height = 120.dp)) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text(label)
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
                    "audio" -> Icons.Default.AudioFile; else -> Icons.Default.InsertDriveFile
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
private data class QuickAccessItem(val icon: ImageVector, val label: String)
private val quickAccessItems = listOf(
    QuickAccessItem(Icons.Default.Folder, "內部儲存"),
    QuickAccessItem(Icons.Default.Usb, "USB 裝置"),
    QuickAccessItem(Icons.Default.Lan, "區域網路"),
    QuickAccessItem(Icons.Default.PhoneAndroid, "手機連線"),
    QuickAccessItem(Icons.Default.Download, "下載"),
)
private data class RecentFile(val name: String, val type: String, val size: String)
private val recentFiles = listOf(
    RecentFile("Dune_Part2.mp4", "video", "3.2 GB"),
    RecentFile("vacation_photo_001.jpg", "image", "4.5 MB"),
    RecentFile("playlist.m3u8", "audio", "1.2 KB"),
    RecentFile("report_Q3.pdf", "document", "2.1 MB"),
)
private data class ConnectedDevice(val name: String, val icon: ImageVector, val status: String)
private val connectedDevices = listOf(
    ConnectedDevice("Charlie 的手機", Icons.Default.PhoneAndroid, "已連線"),
    ConnectedDevice("Galaxy Tab S9", Icons.Default.Tablet, "可連線"),
)
