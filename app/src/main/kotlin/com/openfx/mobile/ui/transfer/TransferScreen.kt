package com.openfx.mobile.ui.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.core.sync.TransferDirection
import com.openfx.core.sync.TransferStatus
import com.openfx.core.sync.TransferTask

/**
 * 傳檔頁 — 顯示真實的傳輸任務 (來自 TransferManager Singleton)
 * 支援取消進行中的任務、清除歷史記錄
 */
@Composable
fun TransferScreen(viewModel: TransferViewModel = hiltViewModel()) {
    val activeTasks by viewModel.activeTasks.collectAsState()
    val history by viewModel.history.collectAsState()

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
            items(activeTasks, key = { "active-${it.id}" }) { task ->
                TransferTaskItem(
                    task = task,
                    onCancel = { viewModel.cancelTask(task.id) }
                )
            }
        }

        // 歷史記錄
        if (history.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 16.dp, 16.dp, 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "歷史記錄",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { viewModel.clearHistory() }) {
                        Text("清除全部", fontSize = 12.sp)
                    }
                }
            }
            items(history, key = { "history-${it.id}" }) { task ->
                TransferTaskItem(task = task, onCancel = null)
            }
        }

        // 空狀態
        if (activeTasks.isEmpty() && history.isEmpty()) {
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
fun TransferTaskItem(
    task: TransferTask,
    onCancel: (() -> Unit)?
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = if (task.direction == TransferDirection.SEND)
                    Icons.Default.Upload else Icons.Default.Download,
                contentDescription = null,
                tint = when (task.status) {
                    TransferStatus.COMPLETED -> Color(0xFF2E7D32)
                    TransferStatus.FAILED, TransferStatus.CANCELLED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
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

                when (task.status) {
                    TransferStatus.ACTIVE -> {
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

                    TransferStatus.FAILED -> Text(
                        "傳輸失敗",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    TransferStatus.CANCELLED -> Text(
                        "已取消",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    TransferStatus.COMPLETED -> Text(
                        "傳輸完成",
                        fontSize = 11.sp,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    else -> {}
                }
            }
        },
        trailingContent = {
            when (task.status) {
                TransferStatus.ACTIVE -> {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
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

                TransferStatus.FAILED -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = "失敗",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                TransferStatus.CANCELLED -> {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "已取消",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
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
