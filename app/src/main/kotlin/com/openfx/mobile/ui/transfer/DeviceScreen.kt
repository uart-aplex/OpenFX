package com.openfx.mobile.ui.transfer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfx.core.sync.DeviceInfo
import com.openfx.core.sync.DeviceType
import com.openfx.core.sync.PairingMethod
import kotlin.math.cos
import kotlin.math.sin

// ===== 裝置配對主畫面 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen() {
    // TODO: 之後接 ViewModel，目前用假資料展示 UI
    var currentTab by remember { mutableIntStateOf(0) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }

    val nearbyDevices = remember {
        listOf(
            DeviceInfo("tv-1", "客廳 Android TV", DeviceType.TV, "192.168.1.120", isPaired = true, isOnline = true, lastSeen = System.currentTimeMillis()),
            DeviceInfo("tab-1", "Galaxy Tab S9", DeviceType.TABLET, "192.168.1.118", isPaired = false, isOnline = true),
            DeviceInfo("phone-1", "Pixel 8 Pro", DeviceType.PHONE, "192.168.1.112", isPaired = false, isOnline = true),
        )
    }

    val pairedDevices = remember {
        listOf(
            DeviceInfo("tv-1", "客廳 Android TV", DeviceType.TV, "192.168.1.120", isPaired = true, isOnline = true, lastSeen = System.currentTimeMillis()),
            DeviceInfo("tv-2", "臥室 Mi Box", DeviceType.TV, "192.168.1.125", isPaired = true, isOnline = false, lastSeen = System.currentTimeMillis() - 172800000),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 頂部 Tab 切換
        TabRow(selectedTabIndex = currentTab) {
            Tab(selected = currentTab == 0, onClick = { currentTab = 0 }) {
                Text("掃描附近", modifier = Modifier.padding(vertical = 12.dp))
            }
            Tab(selected = currentTab == 1, onClick = { currentTab = 1 }) {
                Text("已配對", modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        when (currentTab) {
            0 -> ScanScreen(
                devices = nearbyDevices,
                onDeviceClick = { device ->
                    if (device.isPaired) {
                        selectedDevice = device
                    } else {
                        selectedDevice = device
                        showPairingDialog = true
                    }
                }
            )
            1 -> PairedDeviceList(
                devices = pairedDevices,
                onDeviceClick = { selectedDevice = it },
                onAddNew = { currentTab = 0 }
            )
        }
    }

    // 配對對話框
    if (showPairingDialog && selectedDevice != null) {
        PairingDialog(
            device = selectedDevice!!,
            onDismiss = { showPairingDialog = false },
            onPaired = {
                showPairingDialog = false
                currentTab = 1
            }
        )
    }

    // 裝置控制面板
    if (selectedDevice != null && !showPairingDialog && selectedDevice!!.isPaired) {
        DeviceControlSheet(
            device = selectedDevice!!,
            onDismiss = { selectedDevice = null }
        )
    }
}

// ===== 掃描畫面 (雷達動畫 + 裝置列表) =====

@Composable
fun ScanScreen(
    devices: List<DeviceInfo>,
    onDeviceClick: (DeviceInfo) -> Unit
) {
    val sweepAngle = rememberInfiniteTransition(label = "radar")
    val angle by sweepAngle.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 雷達動畫區域
        item {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                // 雷達圓圈 + 掃描線
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val maxRadius = size.minDimension / 2

                    // 同心圓
                    for (i in 1..3) {
                        drawCircle(
                            color = Color.Gray.copy(alpha = 0.2f),
                            radius = maxRadius * i / 3,
                            center = center,
                            style = Stroke(width = 1f)
                        )
                    }

                    // 中心點
                    drawCircle(
                        color = Color(0xFF1976D2),
                        radius = 6f,
                        center = center
                    )

                    // 掃描線
                    val rad = Math.toRadians(angle.toDouble())
                    val endX = center.x + maxRadius * cos(rad).toFloat()
                    val endY = center.y + maxRadius * sin(rad).toFloat()
                    drawLine(
                        color = Color(0xFF1976D2).copy(alpha = 0.6f),
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round
                    )
                }

                // 偵測到的裝置數
                Text(
                    "${devices.size} 個裝置",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                )
            }
        }

        // 標題
        item {
            Text(
                "附近裝置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // 裝置列表
        items(devices) { device ->
            DeviceListItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
    }
}

// ===== 已配對裝置列表 =====

@Composable
fun PairedDeviceList(
    devices: List<DeviceInfo>,
    onDeviceClick: (DeviceInfo) -> Unit,
    onAddNew: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(devices) { device ->
            DeviceListItem(
                device = device,
                onClick = { onDeviceClick(device) }
            )
        }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                OutlinedButton(onClick = onAddNew) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("配對新裝置")
                }
            }
        }
    }
}

// ===== 單一裝置項目 =====

@Composable
fun DeviceListItem(device: DeviceInfo, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (device.isOnline)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = device.type.icon,
                        contentDescription = null,
                        tint = if (device.isOnline)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        headlineContent = { Text(device.name) },
        supportingContent = {
            Text(
                "${device.ipAddress} · Wi-Fi Direct",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when {
                    device.isOnline && device.isPaired -> Color(0xFFE8F5E9)
                    device.isPaired -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color(0xFFFFF3E0)
                }
            ) {
                Text(
                    text = when {
                        device.isOnline && device.isPaired -> "線上"
                        device.isPaired -> "離線"
                        else -> "未配對"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        device.isOnline && device.isPaired -> Color(0xFF2E7D32)
                        device.isPaired -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Color(0xFFE65100)
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    )
    HorizontalDivider()
}

// ===== 配對對話框 =====

@Composable
fun PairingDialog(
    device: DeviceInfo,
    onDismiss: () -> Unit,
    onPaired: () -> Unit
) {
    var pairMethod by remember { mutableIntStateOf(0) } // 0=QR, 1=PIN
    var isConnecting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isConnecting) onDismiss() },
        title = { Text("配對 ${device.name}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 方法切換
                TabRow(selectedTabIndex = pairMethod) {
                    Tab(selected = pairMethod == 0, onClick = { pairMethod = 0 }) {
                        Text("QR Code", modifier = Modifier.padding(8.dp), fontSize = 13.sp)
                    }
                    Tab(selected = pairMethod == 1, onClick = { pairMethod = 1 }) {
                        Text("PIN 碼", modifier = Modifier.padding(8.dp), fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (isConnecting) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("正在建立連線...", fontSize = 13.sp)
                } else if (pairMethod == 0) {
                    // QR Code 佔位框
                    Surface(
                        modifier = Modifier.size(160.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(100.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "請在 ${device.name} 上開啟 OpenFX\n選擇「配對手機」掃描此 QR Code",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                } else {
                    // PIN 碼
                    Text(
                        "8 4 7 2",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "請在 ${device.name} 上輸入此 PIN 碼",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isConnecting = true
                    // TODO: 實際配對邏輯，這裡模擬延遲
                },
                enabled = !isConnecting
            ) { Text("開始配對") }
        },
        dismissButton = {
            if (!isConnecting) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ===== 裝置控制面板 (BottomSheet 風格) =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceControlSheet(
    device: DeviceInfo,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 裝置資訊頭
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            device.type.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(device.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${device.ipAddress} · 已連線",
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 操作按鈕 2x2 網格
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton(
                    icon = Icons.Default.Upload,
                    label = "傳檔到 TV",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO */ }
                )
                ControlButton(
                    icon = Icons.Default.Download,
                    label = "從 TV 下載",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO */ }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ControlButton(
                    icon = Icons.Default.Folder,
                    label = "瀏覽 TV 檔案",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO */ }
                )
                ControlButton(
                    icon = Icons.Default.PlayArrow,
                    label = "在 TV 播放",
                    modifier = Modifier.weight(1f),
                    onClick = { /* TODO */ }
                )
            }

            Spacer(Modifier.height(20.dp))

            // 快速同步捷徑
            Text(
                "快速操作",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            QuickSyncItem(Icons.Default.Image, "同步相簿到 TV", "DCIM → TV:/Photos/手機相簿")
            QuickSyncItem(Icons.Default.VideoFile, "同步影片到 TV", "Movies → TV:/Movies/")
            QuickSyncItem(Icons.Default.Android, "安裝 APK 到 TV", "選擇 APK 檔案 sideload 到 TV")

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
fun QuickSyncItem(icon: ImageVector, title: String, subtitle: String) {
    ListItem(
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text(title, fontSize = 14.sp) },
        supportingContent = {
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.clickable { /* TODO */ }
    )
}

// ===== DeviceType 圖示對應 =====
val DeviceType.icon: ImageVector
    get() = when (this) {
        DeviceType.TV -> Icons.Default.Tv
        DeviceType.TABLET -> Icons.Default.Tablet
        DeviceType.PHONE -> Icons.Default.PhoneAndroid
        DeviceType.UNKNOWN -> Icons.Default.Devices
    }
