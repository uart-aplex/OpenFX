package com.openfx.mobile.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openfx.core.file.ArchiveEntry
import com.openfx.core.file.ArchiveOperations
import com.openfx.core.file.FileItem
import kotlinx.coroutines.launch

/**
 * 壓縮檔內容對話框 — 列出內含項目，可全部解壓到目前資料夾
 */
@Composable
fun ArchiveDialog(
    file: FileItem,
    currentDir: String,
    onExtracted: (message: String) -> Unit,
    onDismiss: () -> Unit
) {
    var entries by remember { mutableStateOf<List<ArchiveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractedCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file.path) {
        ArchiveOperations.listEntries(file.path)
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "無法讀取壓縮檔" }
    }

    Dialog(onDismissRequest = { if (!extracting) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "壓縮檔內容",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                when {
                    error != null -> {
                        Text(
                            error!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }

                    entries == null -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text("讀取中…", fontSize = 13.sp)
                        }
                    }

                    entries!!.isEmpty() -> {
                        Text("壓縮檔是空的", fontSize = 13.sp)
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(entries!!, key = { it.name }) { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (entry.isDirectory) Icons.Default.Folder
                                        else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (entry.isDirectory) {
                                            MaterialTheme.colorScheme.primary
                                        } else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        entry.name,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!entry.isDirectory && entry.size > 0) {
                                        Text(
                                            formatArchiveSize(entry.size),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "共 ${entries!!.count { !it.isDirectory }} 個檔案",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                if (extracting) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("解壓中…已處理 $extractedCount 個檔案", fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        enabled = !extracting,
                        onClick = onDismiss
                    ) { Text("關閉") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !extracting && entries != null && error == null,
                        onClick = {
                            val destDirName = file.name
                                .replace(Regex("\\.(zip|rar|7z|tar\\.gz|tgz|tar\\.bz2|tbz2|tar)$", RegexOption.IGNORE_CASE), "")
                                .ifBlank { "extracted" }
                            val dest = "$currentDir/$destDirName"
                            extracting = true
                            scope.launch {
                                ArchiveOperations.extractAll(file.path, dest) { done ->
                                    extractedCount = done
                                }
                                    .onSuccess { count ->
                                        onExtracted("已解壓 $count 個檔案到 $destDirName/")
                                    }
                                    .onFailure { e ->
                                        extracting = false
                                        error = e.message ?: "解壓失敗"
                                    }
                            }
                        }
                    ) {
                        Text(
                            "全部解壓",
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private fun formatArchiveSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}
