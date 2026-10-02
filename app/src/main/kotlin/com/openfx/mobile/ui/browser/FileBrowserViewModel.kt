package com.openfx.mobile.ui.browser

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.file.*
import com.openfx.core.settings.SettingsRepository
import com.openfx.core.sync.TransferManager
import com.openfx.core.sync.http.LanDeviceScanner
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.mobile.ui.media.VideoPlayerActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 剪貼簿操作模式
 */
enum class ClipboardMode { COPY, MOVE }

data class ClipboardData(
    val mode: ClipboardMode,
    val paths: List<String>
)

data class BrowserUiState(
    val currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
    val files: List<FileItem> = emptyList(),
    val selectedFiles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val showHidden: Boolean = false,
    val storageVolumes: List<StorageVolume> = emptyList(),
    val pathHistory: List<String> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showNewFolderDialog: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val showSortMenu: Boolean = false,
    val showSearchBar: Boolean = false,
    val showPropertiesDialog: Boolean = false,
    // 傳送到裝置對話框
    val showSendDialog: Boolean = false,
    // 剪貼簿 (複製/移動用)
    val clipboard: ClipboardData? = null,
    // 操作進度
    val operationProgress: TransferProgress? = null,
    val isOperating: Boolean = false,
)

enum class ViewMode { GRID, LIST }

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileOps: FileOperations,
    private val transferManager: TransferManager,
    private val settings: SettingsRepository,
    private val bookmarkRepo: BookmarkRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<Set<String>> = bookmarkRepo.bookmarks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        loadStorageVolumes()
        observeSettings()
        navigateTo(Environment.getExternalStorageDirectory().absolutePath)
    }

    /** 設定頁的預設值即時套用到瀏覽器 (值有變才 refresh,避免迴圈) */
    private fun observeSettings() {
        viewModelScope.launch {
            settings.showHiddenFiles.collect { value ->
                if (_uiState.value.showHidden != value) {
                    _uiState.update { it.copy(showHidden = value) }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            settings.defaultViewGrid.collect { grid ->
                val mode = if (grid) ViewMode.GRID else ViewMode.LIST
                if (_uiState.value.viewMode != mode) {
                    _uiState.update { it.copy(viewMode = mode) }
                }
            }
        }
        viewModelScope.launch {
            settings.sortBy.collect { value ->
                if (_uiState.value.sortBy != value) {
                    _uiState.update { it.copy(sortBy = value) }
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            settings.sortAscending.collect { value ->
                if (_uiState.value.sortAscending != value) {
                    _uiState.update { it.copy(sortAscending = value) }
                    refresh()
                }
            }
        }
    }

    // ===== 書籤 =====

    fun toggleBookmark(path: String) {
        viewModelScope.launch { bookmarkRepo.toggle(path) }
    }

    fun removeBookmark(path: String) {
        viewModelScope.launch { bookmarkRepo.remove(path) }
    }

    /** 狀態列訊息 (非錯誤的一般提示也走這裡) */
    fun showInfo(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    // ===== 導航 =====

    fun navigateTo(path: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, selectedFiles = emptySet()) }

            fileOps.listFiles(
                path = path,
                showHidden = _uiState.value.showHidden,
                sortBy = _uiState.value.sortBy,
                ascending = _uiState.value.sortAscending
            ).onSuccess { files ->
                _uiState.update { state ->
                    val history = if (path != state.currentPath) {
                        state.pathHistory + state.currentPath
                    } else state.pathHistory
                    state.copy(
                        currentPath = path,
                        files = files,
                        isLoading = false,
                        pathHistory = history,
                        showSearchBar = false,
                        searchQuery = ""
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun navigateUp(): Boolean {
        return if (canNavigateUp) {
            navigateTo(_uiState.value.currentPath.substringBeforeLast('/'))
            true
        } else false
    }

    /**
     * 是否還能往上一層 — 以儲存磁區根目錄為樓層下限：
     * 到達磁區根目錄 (如 /storage/emulated/0) 就不能再往上，
     * 避免進入 /storage 這種沒有可列內容、也無法脫身的路徑。
     */
    val canNavigateUp: Boolean
        get() {
            val current = _uiState.value.currentPath
            val floors = _uiState.value.storageVolumes.map { it.path } +
                Environment.getExternalStorageDirectory().absolutePath

            if (current in floors) return false

            val parent = current.substringBeforeLast('/')
            if (parent.isEmpty() || parent == current) return false

            // 父目錄在任何樓層之上 (例如 /storage) — 不上去
            if (floors.any { it.startsWith("$parent/") }) return false

            return true
        }

    fun goBack(): Boolean {
        val history = _uiState.value.pathHistory
        return if (history.isNotEmpty()) {
            val prev = history.last()
            _uiState.update { it.copy(pathHistory = history.dropLast(1)) }
            navigateTo(prev)
            true
        } else false
    }

    // ===== 選擇 =====

    fun toggleSelection(path: String) {
        _uiState.update { state ->
            val newSet = state.selectedFiles.toMutableSet()
            if (path in newSet) newSet.remove(path) else newSet.add(path)
            state.copy(selectedFiles = newSet)
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedFiles = state.files.map { it.path }.toSet())
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedFiles = emptySet()) }
    }

    val isMultiSelectMode: Boolean
        get() = _uiState.value.selectedFiles.isNotEmpty()

    // ===== 檔案操作 =====

    fun deleteSelected() {
        val paths = _uiState.value.selectedFiles.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            fileOps.delete(paths).onSuccess {
                clearSelection()
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(error = "刪除失敗: ${e.message}") }
            }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            fileOps.rename(path, newName).onSuccess {
                clearSelection()
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(error = "重命名失敗: ${e.message}") }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            fileOps.createDirectory(_uiState.value.currentPath, name).onSuccess {
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(error = "建立資料夾失敗: ${e.message}") }
            }
        }
    }

    // ===== 複製 / 移動 (剪貼簿模式) =====

    /**
     * 把選中的檔案放入剪貼簿（還沒執行，等使用者選好目標資料夾後按「貼上」）
     */
    fun copySelected() {
        val paths = _uiState.value.selectedFiles.toList()
        if (paths.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = ClipboardData(ClipboardMode.COPY, paths),
                selectedFiles = emptySet()
            )
        }
    }

    fun moveSelected() {
        val paths = _uiState.value.selectedFiles.toList()
        if (paths.isEmpty()) return
        _uiState.update {
            it.copy(
                clipboard = ClipboardData(ClipboardMode.MOVE, paths),
                selectedFiles = emptySet()
            )
        }
    }

    /**
     * 貼上 — 在目前目錄執行複製或移動
     */
    fun pasteClipboard() {
        val clip = _uiState.value.clipboard ?: return
        val destination = _uiState.value.currentPath

        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            try {
                when (clip.mode) {
                    ClipboardMode.COPY -> {
                        fileOps.copy(clip.paths, destination).collect { progress ->
                            _uiState.update { it.copy(operationProgress = progress) }
                        }
                    }
                    ClipboardMode.MOVE -> {
                        fileOps.move(clip.paths, destination).collect { progress ->
                            _uiState.update { it.copy(operationProgress = progress) }
                        }
                    }
                }
                _uiState.update {
                    it.copy(clipboard = null, isOperating = false, operationProgress = null)
                }
                refresh()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = "操作失敗: ${e.message}",
                        isOperating = false,
                        operationProgress = null
                    )
                }
            }
        }
    }

    fun clearClipboard() {
        _uiState.update { it.copy(clipboard = null) }
    }

    val hasClipboard: Boolean
        get() = _uiState.value.clipboard != null

    // ===== 分享 =====

    fun shareSelected() {
        val paths = _uiState.value.selectedFiles.toList()
        if (paths.isEmpty()) return

        try {
            val uris = paths.mapNotNull { path ->
                val file = File(path)
                if (file.isFile) {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        file
                    )
                } else null
            }

            if (uris.isEmpty()) return

            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(paths.first())
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            }

            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val chooser = Intent.createChooser(intent, "分享檔案")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)

            clearSelection()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "分享失敗: ${e.message}") }
        }
    }

    // ===== 開啟檔案 =====

    fun openFile(fileItem: FileItem) {
        if (fileItem.fileType == FileType.APK) {
            installApk(fileItem)
            return
        }
        if (fileItem.fileType == FileType.VIDEO) {
            // 內建播放器 (手機/TV 通用,不依賴外部 App)
            try {
                val intent = Intent(appContext, VideoPlayerActivity::class.java).apply {
                    putExtra(VideoPlayerActivity.EXTRA_PATH, fileItem.path)
                    putExtra(VideoPlayerActivity.EXTRA_TITLE, fileItem.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "無法播放: ${e.message}") }
            }
            return
        }
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val mimeType = getMimeType(fileItem.path)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                clipData = ClipData.newUri(appContext.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            _uiState.update { it.copy(error = "找不到可以開啟此檔案的應用程式") }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "無法開啟: ${e.message}") }
        }
    }

    // ===== 安裝 APK =====

    fun installApk(fileItem: FileItem) {
        if (fileItem.extension.equals("apks", ignoreCase = true)) {
            runCatching {
                appContext.startActivity(com.openfx.mobile.apps.AppPackageInstallerActivity.intent(appContext, File(fileItem.path)))
            }.onFailure { error ->
                _uiState.update { it.copy(error = "無法安裝: ${error.message}") }
            }
            return
        }
        // API 26+ 需要先取得「安裝未知應用」權限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            try {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${appContext.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(settingsIntent)
                _uiState.update {
                    it.copy(error = "請允許 OpenFX 安裝未知應用程式，返回後再按一次「安裝」")
                }
            } catch (e: Exception) {
                // 部分 TV ROM 沒有這個設定頁
                _uiState.update {
                    it.copy(error = "請手動到系統設定允許 OpenFX 安裝未知應用程式")
                }
            }
            return
        }

        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(fileItem.path))
                clipData = ClipData.newUri(appContext.contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                appContext.startActivity(installIntent)
            } catch (e: ActivityNotFoundException) {
                // 部分裝置 (尤其 TV) 的安裝器只認舊版 action
                val fallback = Intent("android.intent.action.INSTALL_PACKAGE").apply {
                    data = uri
                    clipData = ClipData.newUri(appContext.contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(fallback)
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "無法安裝: ${e.message}") }
        }
    }

    // ===== 傳送到 OpenFX 裝置 =====

    private val _sendDevices = MutableStateFlow<List<OpenFxDevice>>(emptyList())
    val sendDevices: StateFlow<List<OpenFxDevice>> = _sendDevices.asStateFlow()

    private val _isScanningSendDevices = MutableStateFlow(false)
    val isScanningSendDevices: StateFlow<Boolean> = _isScanningSendDevices.asStateFlow()

    fun openSendDialog() {
        if (_uiState.value.selectedFiles.isEmpty()) return
        _uiState.update { it.copy(showSendDialog = true) }
        scanSendDevices()
    }

    fun closeSendDialog() {
        _uiState.update { it.copy(showSendDialog = false) }
    }

    fun scanSendDevices() {
        if (_isScanningSendDevices.value) return
        _isScanningSendDevices.value = true
        viewModelScope.launch {
            val myIp = LanDeviceScanner.getLocalIpAddress()
            _sendDevices.value = transferManager.discoverDevices()
                .filterNot { it.ip == myIp }
            _isScanningSendDevices.value = false
        }
    }

    fun sendSelectedTo(device: OpenFxDevice) {
        val paths = _uiState.value.selectedFiles.toList()
            .map(::File)
            .filter { it.isFile }
            .map { it.absolutePath }

        if (paths.isEmpty()) {
            _uiState.update {
                it.copy(
                    showSendDialog = false,
                    error = "選取項目沒有可傳送的檔案（資料夾請先壓縮）"
                )
            }
            return
        }

        transferManager.sendFiles(paths, device)
        _uiState.update {
            it.copy(
                showSendDialog = false,
                selectedFiles = emptySet(),
                error = "已加入 ${paths.size} 個傳送任務，到「傳檔」頁查看進度"
            )
        }
    }

    // ===== 檢視設定 =====

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setSortBy(sort: SortBy) {
        _uiState.update { it.copy(sortBy = sort, showSortMenu = false) }
        refresh()
    }

    fun toggleSortOrder() {
        _uiState.update { it.copy(sortAscending = !it.sortAscending) }
        refresh()
    }

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
        refresh()
    }

    // ===== 對話框控制 =====

    fun showRenameDialog() = _uiState.update { it.copy(showRenameDialog = true) }
    fun hideRenameDialog() = _uiState.update { it.copy(showRenameDialog = false) }
    fun showNewFolderDialog() = _uiState.update { it.copy(showNewFolderDialog = true) }
    fun hideNewFolderDialog() = _uiState.update { it.copy(showNewFolderDialog = false) }
    fun showDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = true) }
    fun hideDeleteConfirm() = _uiState.update { it.copy(showDeleteConfirm = false) }
    fun toggleSortMenu() = _uiState.update { it.copy(showSortMenu = !it.showSortMenu) }
    fun toggleSearchBar() = _uiState.update { it.copy(showSearchBar = !it.showSearchBar) }
    fun showPropertiesDialog() = _uiState.update { it.copy(showPropertiesDialog = true) }
    fun hidePropertiesDialog() = _uiState.update { it.copy(showPropertiesDialog = false) }
    fun clearError() = _uiState.update { it.copy(error = null) }

    // ===== 搜尋 =====

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            viewModelScope.launch {
                _uiState.update { it.copy(isSearching = true) }
                val results = mutableListOf<FileItem>()
                fileOps.search(_uiState.value.currentPath, query).collect { item ->
                    results.add(item)
                    if (results.size <= 200) {
                        _uiState.update { it.copy(files = results.toList()) }
                    }
                }
                _uiState.update { it.copy(isSearching = false) }
            }
        } else if (query.isEmpty()) {
            refresh()
        }
    }

    // ===== Private =====

    private fun refresh() {
        navigateTo(_uiState.value.currentPath)
    }

    private fun loadStorageVolumes() {
        viewModelScope.launch {
            val volumes = fileOps.getStorageVolumes()
            _uiState.update { it.copy(storageVolumes = volumes) }
        }
    }

    private fun getMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"

        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
