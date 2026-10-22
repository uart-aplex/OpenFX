package com.openfx.mobile.ui.browser

import com.openfx.mobile.ui.network.NetworkServersDialog
import androidx.compose.material.icons.filled.Lan
import com.openfx.core.network.smb.SmbPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.openfx.mobile.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.openfx.app.platform.rememberAdaptiveLayoutInfo
import com.openfx.core.file.ArchiveOperations
import com.openfx.core.file.FileItem
import com.openfx.core.file.FileType
import com.openfx.core.file.StorageVolume
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import com.openfx.common.ui.FileIcon
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val layoutInfo = rememberAdaptiveLayoutInfo()
    val sendDevices by viewModel.sendDevices.collectAsState()
    val isScanningSendDevices by viewModel.isScanningSendDevices.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showBookmarks by remember { mutableStateOf(false) }
    var showNetwork by remember { mutableStateOf(false) }

    // 返回鍵處理：多選模式 → 取消選擇，否則 → 回上一層
    // 到頂 (canNavigateUp=false) 時不攔截，讓系統正常處理返回 (不會卡死)
    BackHandler(enabled = viewModel.isMultiSelectMode || viewModel.canNavigateUp) {
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
                    Text(stringResource(R.string.browser_selected_count, uiState.selectedFiles.size))
                } else {
                    Text("OpenFX", style = MaterialTheme.typography.titleMedium)
                }
            },
            navigationIcon = {
                if (viewModel.isMultiSelectMode) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.browser_cancel_select))
                    }
                } else if (viewModel.canNavigateUp) {
                    IconButton(onClick = { viewModel.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = {
                if (viewModel.isMultiSelectMode) {
                    IconButton(onClick = { viewModel.selectAll() }) {
                        Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.browser_select_all))
                    }
                } else {
                    IconButton(onClick = { viewModel.toggleBookmark(uiState.currentPath) }) {
                        Icon(
                            if (uiState.currentPath in bookmarks) Icons.Default.Star
                            else Icons.Default.StarBorder,
                            contentDescription = stringResource(R.string.action_bookmark)
                        )
                    }
                    IconButton(onClick = { viewModel.toggleSearchBar() }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                    }
                    IconButton(onClick = { viewModel.toggleShowHidden() }) {
                        Icon(
                            if (uiState.showHidden) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.hidden_files)
                        )
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
            volumes = uiState.storageVolumes,
            onNavigate = { viewModel.navigateTo(it) }
        )

        // ===== 類別籤列 (手機直向專用；橫向/平板用側邊欄) =====
        if (!layoutInfo.useDualPane) {
            CategoryChipRow(
                volumes = uiState.storageVolumes,
                currentPath = uiState.currentPath,
                onNavigate = { viewModel.navigateTo(it) },
                onNetwork = { showNetwork = true }
            )
        }

        // ===== 搜尋列 =====
        AnimatedVisibility(visible = uiState.showSearchBar) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.search(it) },
                placeholder = { Text(stringResource(R.string.search_files_hint), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        // ===== 錯誤提示 =====
        if (uiState.error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        uiState.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text(stringResource(R.string.close), fontSize = 12.sp)
                    }
                }
            }
        }

        // ===== 檢視模式切換列 =====
        ViewModeBar(
            viewMode = uiState.viewMode,
            sortBy = uiState.sortBy,
            showSortMenu = uiState.showSortMenu,
            onViewModeChange = { viewModel.setViewMode(it) },
            onSortChange = { viewModel.setSortBy(it) },
            onToggleSortMenu = { viewModel.toggleSortMenu() },
            onToggleSortOrder = { viewModel.toggleSortOrder() },
            sortAscending = uiState.sortAscending
        )

        // ===== 主內容區域 — 根據方向切換佈局 =====
        if (layoutInfo.useDualPane) {
            // ===== 橫向/平板：雙欄 (左側邊欄 + 右檔案區) =====
            Row(modifier = Modifier.weight(1f)) {
                // 側邊欄：儲存空間 + 快捷位置
                FileSidebar(
                    volumes = uiState.storageVolumes,
                    currentPath = uiState.currentPath,
                    bookmarks = bookmarks,
                    onNavigate = { viewModel.navigateTo(it) },
                    onNetwork = { showNetwork = true },
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

        // ===== 剪貼簿提示列 (複製/移動後顯示) =====
        AnimatedVisibility(visible = uiState.clipboard != null) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val clip = uiState.clipboard
                    Text(
                        text = run {
                            val count = clip?.paths?.size ?: 0
                            pluralStringResource(
                                if (clip?.mode == ClipboardMode.COPY) R.plurals.clipboard_copied
                                else R.plurals.clipboard_cut,
                                count,
                                count
                            )
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    TextButton(onClick = { viewModel.pasteClipboard() }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.paste))
                    }
                    TextButton(onClick = { viewModel.clearClipboard() }) {
                        Text(stringResource(R.string.cancel), fontSize = 12.sp)
                    }
                }
            }
        }

        // ===== 操作進度 =====
        if (uiState.isOperating && uiState.operationProgress != null) {
            LinearProgressIndicator(
                progress = { uiState.operationProgress!!.percent },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        RemoteFetchBar(uiState.remoteFetch)

        // ===== 底部操作列 =====
        AnimatedVisibility(
            visible = viewModel.isMultiSelectMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            ActionBar(
                onCopy = { viewModel.copySelected() },
                onMove = { viewModel.moveSelected() },
                onRename = { viewModel.showRenameDialog() },
                onDelete = { viewModel.showDeleteConfirm() },
                onShare = { viewModel.shareSelected() },
                onSend = { viewModel.openSendDialog() },
                singleSelected = uiState.selectedFiles.size == 1
            )
        }

        AnimatedVisibility(
            visible = !viewModel.isMultiSelectMode && uiState.clipboard == null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            QuickActionBar(
                onNewFolder = { viewModel.showNewFolderDialog() },
                onSelectAll = { viewModel.selectAll() },
                onProperties = { viewModel.showPropertiesDialog() },
                onBookmark = { showBookmarks = true }
            )
        }
    }

    // ===== 對話框 =====
    if (uiState.showNewFolderDialog) {
        InputDialog(
            title = stringResource(R.string.dialog_new_folder),
            placeholder = stringResource(R.string.dialog_folder_name_hint),
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
            title = stringResource(R.string.dialog_rename),
            placeholder = stringResource(R.string.dialog_rename_hint),
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
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(stringResource(R.string.dialog_delete_message, uiState.selectedFiles.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        viewModel.hideDeleteConfirm()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideDeleteConfirm() }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 屬性對話框
    if (uiState.showPropertiesDialog) {
        val path = uiState.currentPath
        val file = java.io.File(path)
        val dateFormat = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault())
        AlertDialog(
            onDismissRequest = { viewModel.hidePropertiesDialog() },
            title = { Text(stringResource(R.string.properties_folder_title)) },
            text = {
                val yes = stringResource(R.string.yes)
                val no = stringResource(R.string.no)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.properties_name, file.name), fontSize = 13.sp)
                    Text(stringResource(R.string.properties_path, path), fontSize = 13.sp)
                    Text(
                        pluralStringResource(R.plurals.properties_items, uiState.files.size, uiState.files.size),
                        fontSize = 13.sp
                    )
                    Text(
                        stringResource(
                            R.string.properties_modified,
                            dateFormat.format(java.util.Date(file.lastModified()))
                        ),
                        fontSize = 13.sp
                    )
                    Text(stringResource(R.string.properties_readable, if (file.canRead()) yes else no), fontSize = 13.sp)
                    Text(stringResource(R.string.properties_writable, if (file.canWrite()) yes else no), fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.hidePropertiesDialog() }) { Text(stringResource(R.string.close)) }
            }
        )
    }

    // 傳送到裝置對話框
    if (uiState.showSendDialog) {
        SendToDeviceDialog(
            devices = sendDevices,
            isScanning = isScanningSendDevices,
            onRescan = { viewModel.scanSendDevices() },
            onSelect = { viewModel.sendSelectedTo(it) },
            onDismiss = { viewModel.closeSendDialog() }
        )
    }

    // 網路位置 (SMB) 對話框
    if (showNetwork) {
        NetworkServersDialog(
            onOpen = { path ->
                showNetwork = false
                viewModel.navigateTo(path)
            },
            onDismiss = { showNetwork = false }
        )
    }

    // 書籤列表對話框
    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onNavigate = { viewModel.navigateTo(it) },
            onRemove = { viewModel.removeBookmark(it) },
            onDismiss = { showBookmarks = false }
        )
    }
}

// ===== 路徑導航列 (類似 ES 的路徑列) =====

private data class Crumb(val label: String, val path: String)

/** 快捷位置 — 位置選單和橫向側邊欄共用 */
private val locationShortcuts = listOf(
    Triple(Icons.Default.Download, R.string.shortcut_downloads, "/storage/emulated/0/Download"),
    Triple(Icons.Default.Image, R.string.shortcut_images, "/storage/emulated/0/DCIM"),
    Triple(Icons.Default.VideoFile, R.string.shortcut_videos, "/storage/emulated/0/Movies"),
    Triple(Icons.Default.AudioFile, R.string.shortcut_music, "/storage/emulated/0/Music"),
    Triple(Icons.Default.Description, R.string.shortcut_documents, "/storage/emulated/0/Documents"),
)

/**
 * 把完整路徑切成「磁區名稱 › 子資料夾 › …」的導航段落。
 * /storage/emulated/0/Download → [內部儲存, Download]
 * (不再把 storage/emulated/0 顯示成三段奇怪標籤)
 */
private fun buildCrumbs(
    path: String,
    volumes: List<StorageVolume>,
    internalStorageName: String
): List<Crumb> {
    if (SmbPath.isSmb(path)) {
        val smb = runCatching { SmbPath.parse(path) }.getOrNull() ?: return listOf(Crumb(path, path))
        val crumbs = mutableListOf<Crumb>()
        var current: SmbPath? = smb
        while (current != null) {
            crumbs += Crumb(current.name, current.toString())
            current = current.parent()
        }
        return crumbs.reversed()
    }
    val allVolumes = volumes.ifEmpty {
        listOf(
            StorageVolume(
                internalStorageName,
                android.os.Environment.getExternalStorageDirectory().absolutePath,
                0, 0, false
            )
        )
    }
    val volume = allVolumes
        .filter { path == it.path || path.startsWith("${it.path}/") }
        .maxByOrNull { it.path.length }

    return if (volume != null) {
        val crumbs = mutableListOf(Crumb(volume.name, volume.path))
        var acc = volume.path
        path.removePrefix(volume.path).split("/").filter { it.isNotEmpty() }.forEach { segment ->
            acc = "$acc/$segment"
            crumbs += Crumb(segment, acc)
        }
        crumbs
    } else {
        val segments = path.split("/").filter { it.isNotEmpty() }
        segments.mapIndexed { index, segment ->
            Crumb(segment, "/" + segments.take(index + 1).joinToString("/"))
        }
    }
}

@Composable
fun BreadcrumbBar(
    path: String,
    volumes: List<StorageVolume>,
    onNavigate: (String) -> Unit
) {
    val internalStorageName = stringResource(R.string.browser_internal_storage)
    val crumbs = remember(path, volumes, internalStorageName) {
        buildCrumbs(path, volumes, internalStorageName)
    }
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
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Text(
                        "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        fontSize = 12.sp
                    )
                }
                TextButton(
                    onClick = { onNavigate(crumb.path) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        text = crumb.label,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ===== 類別籤列 (手機直向：儲存空間 + 快捷位置,橫向滑動) =====
@Composable
fun CategoryChipRow(
    volumes: List<StorageVolume>,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onNetwork: () -> Unit = {}
) {
    val selectedShortcut = locationShortcuts.firstOrNull { (_, _, path) ->
        currentPath == path || currentPath.startsWith("$path/")
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        // 儲存空間 (內部儲存 / SD 卡)
        items(volumes.size) { index ->
            val volume = volumes[index]
            val isSelected = currentPath == volume.path ||
                (currentPath.startsWith("${volume.path}/") && selectedShortcut == null)
            FilterChip(
                selected = isSelected,
                onClick = { onNavigate(volume.path) },
                label = { Text(volume.name, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        // 快捷位置 (下載 / 圖片 / 影片 / 音樂 / 文件)
        items(locationShortcuts.size) { index ->
            val (icon, label, path) = locationShortcuts[index]
            FilterChip(
                selected = currentPath == path || currentPath.startsWith("$path/"),
                onClick = { onNavigate(path) },
                label = { Text(stringResource(label), fontSize = 12.sp) },
                leadingIcon = {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }

        // 網路位置 (NAS / 電腦共享)
        item {
            FilterChip(
                selected = SmbPath.isSmb(currentPath),
                onClick = onNetwork,
                label = { Text(stringResource(R.string.network_chip), fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
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
    showSortMenu: Boolean,
    onViewModeChange: (ViewMode) -> Unit,
    onSortChange: (com.openfx.core.file.SortBy) -> Unit,
    onToggleSortMenu: () -> Unit,
    onToggleSortOrder: () -> Unit,
    sortAscending: Boolean
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
                contentDescription = stringResource(R.string.view_grid),
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
                contentDescription = stringResource(R.string.view_list),
                tint = if (viewMode == ViewMode.LIST)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.weight(1f))

        // 排序按鈕 + 下拉選單
        Box {
            TextButton(onClick = onToggleSortMenu) {
                Text(
                    text = "${stringResource(R.string.sort_prefix)} ${sortLabel(sortBy)} ${if (sortAscending) "↑" else "↓"}",
                    fontSize = 12.sp
                )
            }
            DropdownMenu(expanded = showSortMenu, onDismissRequest = onToggleSortMenu) {
                com.openfx.core.file.SortBy.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = {
                            Text(sortLabel(sort))
                        },
                        onClick = { onSortChange(sort) },
                        leadingIcon = {
                            if (sort == sortBy) Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (sortAscending) R.string.sort_switch_to_desc else R.string.sort_switch_to_asc
                            )
                        )
                    },
                    onClick = {
                        onToggleSortOrder()
                        onToggleSortMenu()
                    },
                    leadingIcon = {
                        Icon(
                            if (sortAscending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
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
                            contentDescription = stringResource(R.string.selected),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .align(Alignment.Start)
                        )
                    }

                    // 檔案圖示 / 圖片縮圖
                    if ((file.fileType == FileType.IMAGE || file.fileType == FileType.VIDEO) && !SmbPath.isSmb(file.path)) {
                        AsyncImage(
                            model = File(file.path),
                            contentDescription = file.name,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileIcon(
                            file = file,
                            modifier = Modifier.size(44.dp)
                        )
                    }

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
                    if ((file.fileType == FileType.IMAGE || file.fileType == FileType.VIDEO) && !SmbPath.isSmb(file.path)) {
                        AsyncImage(
                            model = File(file.path),
                            contentDescription = file.name,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        FileIcon(
                            file = file,
                            modifier = Modifier.size(32.dp)
                        )
                    }
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
                            contentDescription = stringResource(R.string.selected),
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
    onSend: () -> Unit,
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
            ActionButton(Icons.Default.ContentCopy, stringResource(R.string.action_copy), onCopy)
            ActionButton(Icons.AutoMirrored.Filled.InsertDriveFile, stringResource(R.string.action_move), onMove)
            if (singleSelected) {
                ActionButton(Icons.Default.Edit, stringResource(R.string.action_rename), onRename)
            }
            ActionButton(Icons.Default.Send, stringResource(R.string.send), onSend)
            ActionButton(Icons.Default.Delete, stringResource(R.string.delete), onDelete)
            ActionButton(Icons.Default.Share, stringResource(R.string.action_share), onShare)
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
fun QuickActionBar(
    onNewFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onProperties: () -> Unit = {},
    onBookmark: () -> Unit = {}
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ActionButton(Icons.Default.CreateNewFolder, stringResource(R.string.action_new_folder), onNewFolder)
            ActionButton(Icons.Default.SelectAll, stringResource(R.string.browser_select_all), onSelectAll)
            ActionButton(Icons.Default.BookmarkBorder, stringResource(R.string.action_bookmark), onBookmark)
            ActionButton(Icons.Default.Info, stringResource(R.string.action_properties), onProperties)
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
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var audioPlaylist by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var audioIndex by remember { mutableStateOf<Int?>(null) }
    var apkInstallTarget by remember { mutableStateOf<FileItem?>(null) }
    var archiveTarget by remember { mutableStateOf<FileItem?>(null) }

    val onFileClick: (FileItem) -> Unit = { file ->
        when {
            viewModel.isMultiSelectMode -> viewModel.toggleSelection(file.path)
            file.isDirectory -> viewModel.navigateTo(file.path)
            // 音樂可直接串流 (smb:// 也行)
            file.fileType == FileType.AUDIO -> {
                audioPlaylist = uiState.files.filter { !it.isDirectory && it.fileType == FileType.AUDIO }
                audioIndex = audioPlaylist.indexOfFirst { it.path == file.path }.takeIf { it >= 0 }
            }
            // 以下需要本機檔案：網路檔案會先下載到快取
            file.fileType == FileType.IMAGE ->
                viewModel.withLocalCopy(file) { previewImagePath = it.path }
            file.fileType == FileType.APK ->
                viewModel.withLocalCopy(file) { apkInstallTarget = it }
            file.fileType == FileType.ARCHIVE && ArchiveOperations.isSupported(file.path) ->
                viewModel.withLocalCopy(file) { archiveTarget = it }
            else -> viewModel.openFile(file)
        }
    }

    Box(modifier = modifier) {
        if (uiState.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (uiState.files.isEmpty()) {
            Text(
                text = stringResource(R.string.browser_folder_empty),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            when (uiState.viewMode) {
                ViewMode.GRID -> FileGrid(
                    files = uiState.files,
                    selectedFiles = uiState.selectedFiles,
                    onFileClick = onFileClick,
                    onFileLongClick = { viewModel.toggleSelection(it.path) }
                )
                ViewMode.LIST -> FileList(
                    files = uiState.files,
                    selectedFiles = uiState.selectedFiles,
                    onFileClick = onFileClick,
                    onFileLongClick = { viewModel.toggleSelection(it.path) }
                )
            }
        }
    }
    previewImagePath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            onDismiss = { previewImagePath = null }
        )
    }
    audioIndex?.let { index ->
        AudioPlayerDialog(
            playlist = audioPlaylist,
            currentIndex = index,
            onIndexChange = { audioIndex = it },
            onDismiss = { audioIndex = null }
        )
    }
    apkInstallTarget?.let { target ->
        ApkInstallDialog(
            file = target,
            onInstall = { viewModel.installApk(target) },
            onDismiss = { apkInstallTarget = null }
        )
    }
    archiveTarget?.let { target ->
        ArchiveDialog(
            file = target,
            currentDir = uiState.currentPath,
            onExtracted = { message ->
                archiveTarget = null
                viewModel.showInfo(message)
                viewModel.navigateTo(uiState.currentPath)
            },
            onDismiss = { archiveTarget = null }
        )
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
    bookmarks: Set<String>,
    onNavigate: (String) -> Unit,
    onNetwork: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // LazyColumn 的 builder 不是 composable，預設磁區名稱先在外面取好
    val internalStorageName = stringResource(R.string.browser_internal_storage)
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp)
    ) {
        // 儲存空間
        item {
            Text(
                stringResource(R.string.sidebar_storage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        items(volumes.ifEmpty {
            listOf(
                StorageVolume(
                    internalStorageName,
                    "/storage/emulated/0", 64_000_000_000, 32_000_000_000, false
                )
            )
        }) { volume ->
            SidebarStorageItem(
                name = volume.name,
                usagePercent = volume.usagePercent,
                freeText = stringResource(R.string.sidebar_free_space, volume.freeBytes / (1024 * 1024 * 1024)),
                isSelected = currentPath.startsWith(volume.path),
                onClick = { onNavigate(volume.path) }
            )
        }

        // 快捷位置
        item {
            Text(
                stringResource(R.string.sidebar_shortcuts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        items(locationShortcuts.size) { index ->
            val (icon, label, path) = locationShortcuts[index]
            SidebarNavItem(
                icon = icon,
                label = stringResource(label),
                isSelected = currentPath == path,
                onClick = { onNavigate(path) }
            )
        }

        // 網路位置
        item {
            SidebarNavItem(
                icon = Icons.Default.Lan,
                label = stringResource(R.string.network_title),
                isSelected = SmbPath.isSmb(currentPath),
                onClick = onNetwork
            )
        }

        // 書籤
        item {
            Text(
                stringResource(R.string.sidebar_bookmarks),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (bookmarks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.sidebar_no_bookmarks),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        } else {
            items(bookmarks.sorted().size) { index ->
                val path = bookmarks.sorted()[index]
                SidebarNavItem(
                    icon = Icons.Default.Star,
                    label = path.substringAfterLast('/').ifBlank { path },
                    isSelected = currentPath == path,
                    onClick = { onNavigate(path) }
                )
            }
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

@Composable
fun ImagePreviewDialog(
    imagePath: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onDismiss() }
        ) {
            AsyncImage(
                model = java.io.File(imagePath),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentScale = ContentScale.Fit
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }
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

@Composable
private fun sortLabel(sortBy: com.openfx.core.file.SortBy): String = stringResource(
    when (sortBy) {
        com.openfx.core.file.SortBy.NAME -> R.string.sort_name
        com.openfx.core.file.SortBy.SIZE -> R.string.sort_size
        com.openfx.core.file.SortBy.DATE -> R.string.sort_date
        com.openfx.core.file.SortBy.TYPE -> R.string.sort_type
    }
)

/** 開啟網路檔案前下載到快取的進度條 */
@Composable
fun RemoteFetchBar(fetch: RemoteFetch?) {
    fetch ?: return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.smb_downloading, fetch.name),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (fetch.total > 0) {
            LinearProgressIndicator(progress = { fetch.percent }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}
