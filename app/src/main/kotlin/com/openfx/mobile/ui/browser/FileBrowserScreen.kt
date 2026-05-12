package com.openfx.mobile.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.app.platform.rememberAdaptiveLayoutInfo
import com.openfx.core.file.FileItem
import com.openfx.core.file.FileType
import com.openfx.core.file.StorageVolume
import androidx.compose.foundation.horizontalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val layoutInfo = rememberAdaptiveLayoutInfo()

    // 返回鍵處理：多選模式 → 取消選擇，否則 → 回上一層
    BackHandler {
        if (viewModel.isMultiSelectMode) {
            viewModel.clearSelection()
        } else {
            viewModel.navigateUp()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 頂部工具列 =====
        TopAppBar(
            title = {
                if (viewModel.isMultiSelectMode) {
                    Text("已選取 ${uiState.selectedFiles.size} 項")
                } else {
                    Text("OpenFX", style = MaterialTheme.typography.titleMedium)
                }
            },
            navigationIcon = {
                if (viewModel.isMultiSelectMode) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = "取消選取")
                    }
                } else {
                    IconButton(onClick = { viewModel.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            actions = {
                if (viewModel.isMultiSelectMode) {
                    IconButton(onClick = { viewModel.selectAll() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = "全選")
                    }
                } else {
                    IconButton(onClick = { /* TODO: 搜尋 */ }) {
                        Icon(Icons.Default.Search, contentDescription = "搜尋")
                    }
                    IconButton(onClick = { /* TODO: 更多選項 */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        )

        // ===== 路徑導航列 =====
        BreadcrumbBar(
            path = uiState.currentPath,
            onNavigate = { viewModel.navigateTo(it) }
        )

        // ===== 檢視模式切換列 =====
        ViewModeBar(
            viewMode = uiState.viewMode,
            sortBy = uiState.sortBy,
            onViewModeChange = { viewModel.setViewMode(it) },
            onSortChange = { viewModel.setSortBy(it) }
        )

        // ===== 主內容區域 — 根據方向切換佈局 =====
        if (layoutInfo.useDualPane) {
            // ===== 橫向/平板：雙欄 (左側邊欄 + 右檔案區) =====
            Row(modifier = Modifier.weight(1f)) {
                // 側邊欄：儲存空間 + 快捷位置
                FileSidebar(
                    volumes = uiState.storageVolumes,
                    currentPath = uiState.currentPath,
                    onNavigate = { viewModel.navigateTo(it) },
                    modifier = Modifier.width(layoutInfo.sidebarWidth.dp)
                )

                // 分隔線
                VerticalDivider()

                // 檔案清單
                FileContentArea(
                    uiState = uiState,
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // ===== 直向 (手機常態)：單欄 =====
            FileContentArea(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier.weight(1f)
            )
        }

        // ===== 底部操作列 =====
        AnimatedVisibility(
            visible = viewModel.isMultiSelectMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            ActionBar(
                onCopy = { /* TODO */ },
                onMove = { /* TODO */ },
                onRename = { viewModel.showRenameDialog() },
                onDelete = { viewModel.showDeleteConfirm() },
                onShare = { /* TODO */ },
                singleSelected = uiState.selectedFiles.size == 1
            )
        }

        AnimatedVisibility(
            visible = !viewModel.isMultiSelectMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            QuickActionBar(
                onNewFolder = { viewModel.showNewFolderDialog() },
                onSelectAll = { viewModel.selectAll() }
            )
        }
    }

    // ===== 對話框 =====
    if (uiState.showNewFolderDialog) {
        InputDialog(
            title = "新增資料夾",
            placeholder = "資料夾名稱",
            onConfirm = { name ->
                viewModel.createFolder(name)
                viewModel.hideNewFolderDialog()
            },
            onDismiss = { viewModel.hideNewFolderDialog() }
        )
    }

    if (uiState.showRenameDialog && uiState.selectedFiles.size == 1) {
        val selectedPath = uiState.selectedFiles.first()
        val currentName = selectedPath.substringAfterLast('/')
        InputDialog(
            title = "重命名",
            placeholder = "新名稱",
            initialValue = currentName,
            onConfirm = { newName ->
                viewModel.rename(selectedPath, newName)
                viewModel.hideRenameDialog()
            },
            onDismiss = { viewModel.hideRenameDialog() }
        )
    }

    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.hideDeleteConfirm() },
            title = { Text("確認刪除") },
            text = { Text("確定要刪除選取的 ${uiState.selectedFiles.size} 個項目嗎？此操作無法復原。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        viewModel.hideDeleteConfirm()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDeleteConfirm() }) { Text("取消") }
            }
        )
    }
}

// ===== 路徑導航列 (類似 ES 的路徑列) =====
@Composable
fun BreadcrumbBar(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split("/").filter { it.isNotEmpty() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    Text(
                        "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    onClick = {
                        val targetPath = "/" + segments.take(index + 1).joinToString("/")
                        onNavigate(targetPath)
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = if (index == 0 && segment == "storage") "內部儲存"
                        else if (index == 1 && segment == "emulated") ""
                        else if (index == 2 && segment == "0") "內部儲存"
                        else segment,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberScrollState() = androidx.compose.foundation.rememberScrollState()

// ===== 檢視模式切換 =====
@Composable
fun ViewModeBar(
    viewMode: ViewMode,
    sortBy: com.openfx.core.file.SortBy,
    onViewModeChange: (ViewMode) -> Unit,
    onSortChange: (com.openfx.core.file.SortBy) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onViewModeChange(ViewMode.GRID) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.GridView,
                contentDescription = "網格",
                tint = if (viewMode == ViewMode.GRID)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = { onViewModeChange(ViewMode.LIST) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.ViewList,
                contentDescription = "列表",
                tint = if (viewMode == ViewMode.LIST)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = { /* TODO: 排序選單 */ }) {
            Text(
                text = "排序: ${
                    when (sortBy) {
                        com.openfx.core.file.SortBy.NAME -> "名稱"
                        com.openfx.core.file.SortBy.SIZE -> "大小"
                        com.openfx.core.file.SortBy.DATE -> "日期"
                        com.openfx.core.file.SortBy.TYPE -> "類型"
                    }
                } ▼",
                fontSize = 12.sp
            )
        }
    }
}

// ===== 網格檢視 =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGrid(
    files: List<FileItem>,
    selectedFiles: Set<String>,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 90.dp),
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.path }) { file ->
            val isSelected = file.path in selectedFiles
            Surface(
                modifier = Modifier
                    .padding(4.dp)
                    .combinedClickable(
                        onClick = { onFileClick(file) },
                        onLongClick = { onFileLongClick(file) }
                    ),
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                tonalElevation = if (isSelected) 4.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 選擇指示器
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "已選取",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.Start)
                        )
                    }

                    // 檔案圖示
                    Icon(
                        imageVector = file.fileType.icon,
                        contentDescription = null,
                        tint = file.fileType.color,
                        modifier = Modifier.size(44.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // 檔名
                    Text(
                        text = file.name,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )

                    // 檔案大小
                    if (!file.isDirectory) {
                        Text(
                            text = file.formattedSize,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ===== 列表檢視 =====
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileList(
    files: List<FileItem>,
    selectedFiles: Set<String>,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            val isSelected = file.path in selectedFiles
            ListItem(
                modifier = Modifier.combinedClickable(
                    onClick = { onFileClick(file) },
                    onLongClick = { onFileLongClick(file) }
                ),
                leadingContent = {
                    Icon(
                        imageVector = file.fileType.icon,
                        contentDescription = null,
                        tint = file.fileType.color,
                        modifier = Modifier.size(32.dp)
                    )
                },
                headlineContent = {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        text = "${file.formattedSize} · ${
                            java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(file.lastModified))
                        }",
                        fontSize = 11.sp
                    )
                },
                trailingContent = {
                    if (isSelected) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "已選取",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
            )
            HorizontalDivider()
        }
    }
}

// ===== 底部操作列 (多選模式) =====
@Composable
fun ActionBar(
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    singleSelected: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(Icons.Default.ContentCopy, "複製", onCopy)
            ActionButton(Icons.AutoMirrored.Filled.InsertDriveFile, "移動", onMove)
            if (singleSelected) {
                ActionButton(Icons.Default.Edit, "重命名", onRename)
            }
            ActionButton(Icons.Default.Delete, "刪除", onDelete)
            ActionButton(Icons.Default.Share, "分享", onShare)
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 10.sp)
    }
}

// ===== 底部快捷列 (非選擇模式) =====
@Composable
fun QuickActionBar(onNewFolder: () -> Unit, onSelectAll: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(Icons.Default.CreateNewFolder, "新增", onNewFolder)
            ActionButton(Icons.Default.SelectAll, "全選", onSelectAll)
            ActionButton(Icons.Default.BookmarkBorder, "書籤") { /* TODO */ }
            ActionButton(Icons.Default.Info, "屬性") { /* TODO */ }
        }
    }
}

// ===== 輸入對話框 =====
@Composable
fun InputDialog(
    title: String,
    placeholder: String,
    initialValue: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("確認") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ===== 檔案內容區域 (被橫向/直向共用) =====
@Composable
fun FileContentArea(
    uiState: BrowserUiState,
    viewModel: FileBrowserViewModel,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.files.isEmpty()) {
            Text(
                text = "此資料夾為空",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            when (uiState.viewMode) {
                ViewMode.GRID -> FileGrid(
                    files = uiState.files,
                    selectedFiles = uiState.selectedFiles,
                    onFileClick = { file ->
                        if (viewModel.isMultiSelectMode) {
                            viewModel.toggleSelection(file.path)
                        } else if (file.isDirectory) {
                            viewModel.navigateTo(file.path)
                        } else {
                            // TODO: 開啟檔案
                        }
                    },
                    onFileLongClick = { viewModel.toggleSelection(it.path) }
                )
                ViewMode.LIST -> FileList(
                    files = uiState.files,
                    selectedFiles = uiState.selectedFiles,
                    onFileClick = { file ->
                        if (viewModel.isMultiSelectMode) {
                            viewModel.toggleSelection(file.path)
                        } else if (file.isDirectory) {
                            viewModel.navigateTo(file.path)
                        } else {
                            // TODO: 開啟檔案
                        }
                    },
                    onFileLongClick = { viewModel.toggleSelection(it.path) }
                )
            }
        }
    }
}

// ===== 側邊欄 (橫向時顯示) =====
/**
 * 類似 ES File Explorer 的左側面板
 * 顯示：儲存空間 + 常用位置 + 書籤
 *
 * 只在橫向和平板上顯示 (由 AdaptiveLayoutInfo.useDualPane 控制)
 */
@Composable
fun FileSidebar(
    volumes: List<StorageVolume>,
    currentPath: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
    ) {
        // 儲存空間
        item {
            Text(
                "儲存空間",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        items(volumes.ifEmpty {
            listOf(
                StorageVolume("內部儲存", "/storage/emulated/0", 64_000_000_000, 32_000_000_000, false)
            )
        }) { volume ->
            SidebarStorageItem(
                name = volume.name,
                usagePercent = volume.usagePercent,
                freeText = "${volume.freeBytes / (1024 * 1024 * 1024)} GB 可用",
                isSelected = currentPath.startsWith(volume.path),
                onClick = { onNavigate(volume.path) }
            )
        }

        // 快捷位置
        item {
            Text(
                "快捷位置",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        val shortcuts = listOf(
            Triple(Icons.Default.Download, "下載", "/storage/emulated/0/Download"),
            Triple(Icons.Default.Image, "圖片", "/storage/emulated/0/DCIM"),
            Triple(Icons.Default.VideoFile, "影片", "/storage/emulated/0/Movies"),
            Triple(Icons.Default.AudioFile, "音樂", "/storage/emulated/0/Music"),
            Triple(Icons.Default.Description, "文件", "/storage/emulated/0/Documents"),
        )

        items(shortcuts.size) { index ->
            val (icon, label, path) = shortcuts[index]
            SidebarNavItem(
                icon = icon,
                label = label,
                isSelected = currentPath == path,
                onClick = { onNavigate(path) }
            )
        }

        // 書籤 (TODO)
        item {
            Text(
                "書籤",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item {
            Text(
                "尚無書籤",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun SidebarStorageItem(
    name: String,
    usagePercent: Float,
    freeText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { usagePercent },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                freeText,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 13.sp)
        }
    }
}

// ===== FileType 圖示和顏色對應 =====
val FileType.icon: ImageVector
    get() = when (this) {
        FileType.FOLDER -> Icons.Default.Folder
        FileType.IMAGE -> Icons.Default.Image
        FileType.VIDEO -> Icons.Default.VideoFile
        FileType.AUDIO -> Icons.Default.AudioFile
        FileType.DOCUMENT -> Icons.AutoMirrored.Filled.InsertDriveFile
        FileType.ARCHIVE -> Icons.Default.FolderZip
        FileType.APK -> Icons.Default.Android
        FileType.CODE -> Icons.Default.Code
        FileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

val FileType.color: Color
    get() = when (this) {
        FileType.FOLDER -> Color(0xFFF9A825)
        FileType.IMAGE -> Color(0xFF43A047)
        FileType.VIDEO -> Color(0xFFE53935)
        FileType.AUDIO -> Color(0xFF7B1FA2)
        FileType.DOCUMENT -> Color(0xFF1565C0)
        FileType.ARCHIVE -> Color(0xFFFF8F00)
        FileType.APK -> Color(0xFF00897B)
        FileType.CODE -> Color(0xFF546E7A)
        FileType.OTHER -> Color(0xFF757575)
    }
