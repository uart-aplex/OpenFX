package com.openfx.mobile.ui.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfx.core.sync.TransferDirection
import com.openfx.core.sync.TransferStatus
import com.openfx.core.sync.TransferTask

@Composable
fun TransferScreen() {
    // TODO: 接 ViewModel，目前假資料
    val activeTasks = remember {
        listOf(
            TransferTask("1", "Dune_Part2.mp4", 3_200_000_000, TransferDirection.SEND, "客廳 TV",
                TransferStatus.ACTIVE, 0.67f, 2_144_000_000, System.currentTimeMillis() - 120_000),
            TransferTask("2", "track_01.mp3", 8_500_000, TransferDirection.SEND, "臥室 Mi Box",
                TransferStatus.ACTIVE, 0.38f, 3_230_000, System.currentTimeMillis() - 5_000),
        )
    }

    val completedTasks = remember {
        listOf(
            TransferTask("3", "Inception.mkv", 4_500_000_000, TransferDirection.SEND, "客廳 TV",
                TransferStatus.COMPLETED, 1f, 4_500_000_000),
            TransferTask("4", "vacation_photos/", 245_000_000, TransferDirection.RECEIVE, "Galaxy Tab",
                TransferStatus.COMPLETED, 1f, 245_000_000),
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 傳輸中
        if (activeTasks.isNotEmpty()) {
            item {
                Text(
                    "傳輸中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                )
            }
            items(activeTasks) { task ->
                TransferTaskItem(task = task)
            }
        }

        // 已完成
        if (completedTasks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 16.dp, 16.dp, 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已完成",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { /* TODO: 清除歷史 */ }) {
                        Text("清除全部", fontSize = 12.sp)
                    }
                }
            }
            items(completedTasks) { task ->
                TransferTaskItem(task = task)
            }
        }

        // 空狀態
        if (activeTasks.isEmpty() && completedTasks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "目前沒有傳輸任務",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "在裝置頁面選擇裝置開始傳檔",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransferTaskItem(task: TransferTask) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = if (task.direction == TransferDirection.SEND)
                    Icons.Default.Upload else Icons.Default.Download,
                contentDescription = null,
                tint = if (task.status == TransferStatus.COMPLETED)
                    Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        headlineContent = {
            Text(task.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        },
        supportingContent = {
            Column {
                Text(
                    "${formatSize(task.fileSize)} · ${
                        if (task.direction == TransferDirection.SEND) "→" else "←"
                    } ${task.targetDevice}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (task.status == TransferStatus.ACTIVE) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        "${(task.progress * 100).toInt()}% · ${task.formattedSpeed}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
        trailingContent = {
            when (task.status) {
                TransferStatus.ACTIVE -> {
                    Row {
                        IconButton(onClick = { /* TODO: 暫停 */ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Pause, contentDescription = "暫停", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { /* TODO: 取消 */ }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "取消", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                TransferStatus.COMPLETED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "完成",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                }
                TransferStatus.PAUSED -> {
                    IconButton(onClick = { /* TODO: 恢復 */ }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "繼續", modifier = Modifier.size(18.dp))
                    }
                }
                else -> {}
            }
        }
    )
    HorizontalDivider()
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}
