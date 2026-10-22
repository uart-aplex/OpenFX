package com.openfx.mobile.ui.browser

import com.openfx.core.file.StorageVolumes
import com.openfx.core.network.smb.SmbServerRepository
import com.openfx.core.network.smb.SmbShareListUnavailableException
import com.openfx.core.network.smb.SmbPath
import com.openfx.core.network.smb.SmbFileOperations
import com.openfx.core.network.smb.SmbServerNotFoundException
import com.openfx.core.network.smb.SmbAuthException
import com.openfx.core.network.smb.SmbAccessDeniedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openfx.mobile.R
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
    // 開啟網路檔案前先下載到快取的進度
    val remoteFetch: RemoteFetch? = null,
)

/** 正在從 SMB 下載到本機快取的檔案 */
data class RemoteFetch(val name: String, val bytes: Long, val total: Long) {
    val percent: Float get() = if (total > 0) bytes.toFloat() / total else 0f
}

enum class ViewMode { GRID, LIST }

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileOps: FileOperations,
    private val transferManager: TransferManager,
    private val settings: SettingsRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val smbFiles: SmbFileOperations,
    private val smbServers: SmbServerRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val bookmarks: StateFlow<Set<String>> = bookmarkRepo.bookmarks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 不提供共享清單的 SMB 伺服器 (host key)，往上導航到共享根目錄為止 */
    private val hostsWithoutShareList: StateFlow<Set<String>> = smbServers.servers
        .map { list -> list.filterNot { it.listsShares }.map { it.hostKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        loadStorageVolumes()
        observeVolumeChanges()
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
                if (e is SmbShareListUnavailableException) {
                    // 之後不再往上走到伺服器根目錄；留在原本的資料夾
                    smbServers.markNoShareList(e.hostKey)
                    _uiState.update { it.copy(isLoading = false) }
                    return@onFailure
                }
                _uiState.update { it.copy(isLoading = false, error = describeError(e)) }
            }
        }
    }

    /** SMB 相關例外轉成使用者看得懂的訊息，其他沿用原本的例外訊息 */
    private fun describeError(e: Throwable): String = when (e) {
        is SmbServerNotFoundException -> appContext.getString(R.string.smb_error_not_configured)
        is SmbAuthException -> appContext.getString(R.string.smb_error_auth)
        is SmbAccessDeniedException -> appContext.getString(R.string.smb_error_access, e.target)
        is SmbShareListUnavailableException -> appContext.getString(R.string.smb_error_share_list)
        is java.net.SocketTimeoutException,
        is java.net.ConnectException,
        is java.net.UnknownHostException,
        is java.net.NoRouteToHostException -> appContext.getString(R.string.smb_error_unreachable, e.message.orEmpty())
        else -> e.message ?: e.javaClass.simpleName
    }

    fun navigateUp(): Boolean {
        if (!canNavigateUp) return false
        val current = _uiState.value.currentPath
        val parent = if (SmbPath.isSmb(current)) {
            SmbPath.parse(current).parent()?.toString() ?: return false
        } else {
            current.substringBeforeLast('/')
        }
        navigateTo(parent)
        return true
    }

    /**
     * 是否還能往上一層 — 以儲存磁區根目錄為樓層下限：
     * 到達磁區根目錄 (如 /storage/emulated/0) 就不能再往上，
     * 避免進入 /storage 這種沒有可列內容、也無法脫身的路徑。
     */
    val canNavigateUp: Boolean
        get() {
            val current = _uiState.value.currentPath
            if (SmbPath.isSmb(current)) {
                val smb = runCatching { SmbPath.parse(current) }.getOrNull() ?: return false
                // 不給共享清單的伺服器：共享根目錄就是最上層
                if (smb.isShareRoot && smb.hostKey in hostsWithoutShareList.value) return false
                return smb.parent() != null
            }
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
                _uiState.update { it.copy(error = appContext.getString(R.string.error_delete_failed, e.message.orEmpty())) }
            }
        }
    }

    fun rename(path: String, newName: String) {
        viewModelScope.launch {
            fileOps.rename(path, newName).onSuccess {
                clearSelection()
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(error = appContext.getString(R.string.error_rename_failed, e.message.orEmpty())) }
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            fileOps.createDirectory(_uiState.value.currentPath, name).onSuccess {
                refresh()
            }.onFailure { e ->
                _uiState.update { it.copy(error = appContext.getString(R.string.error_create_folder_failed, e.message.orEmpty())) }
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
                        error = appContext.getString(R.string.error_operation_failed, describeError(e)),
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

        if (paths.any(SmbPath::isSmb)) {
            val items = _uiState.value.files.filter { it.path in paths && !it.isDirectory }
            viewModelScope.launch {
                val local = items.mapNotNull { item -> fetchToCache(item)?.path }
                if (local.isNotEmpty()) shareLocal(local)
            }
            return
        }
        shareLocal(paths)
    }

    private fun shareLocal(paths: List<String>) {
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

            val chooser = Intent.createChooser(intent, appContext.getString(R.string.share_chooser_title))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(chooser)

            clearSelection()
        } catch (e: Exception) {
            _uiState.update { it.copy(error = appContext.getString(R.string.error_share_failed, e.message.orEmpty())) }
        }
    }

    // ===== 開啟檔案 =====

    fun openFile(fileItem: FileItem) {
        if (SmbPath.isSmb(fileItem.path) && fileItem.fileType != FileType.VIDEO) {
            withLocalCopy(fileItem) { openFile(it) }
            return
        }
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
                _uiState.update { it.copy(error = appContext.getString(R.string.error_play_failed, e.message.orEmpty())) }
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
            _uiState.update { it.copy(error = appContext.getString(R.string.error_no_app_to_open)) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = appContext.getString(R.string.error_open_failed, e.message.orEmpty())) }
        }
    }

    // ===== 安裝 APK =====

    fun installApk(fileItem: FileItem) {
        if (SmbPath.isSmb(fileItem.path)) {
            withLocalCopy(fileItem) { installApk(it) }
            return
        }
        if (fileItem.extension.equals("apks", ignoreCase = true)) {
            runCatching {
                appContext.startActivity(com.openfx.mobile.apps.AppPackageInstallerActivity.intent(appContext, File(fileItem.path)))
            }.onFailure { error ->
                _uiState.update { it.copy(error = appContext.getString(R.string.error_install_failed, error.message.orEmpty())) }
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
                    it.copy(error = appContext.getString(R.string.install_allow_then_retry))
                }
            } catch (e: Exception) {
                // 部分 TV ROM 沒有這個設定頁
                _uiState.update {
                    it.copy(error = appContext.getString(R.string.install_allow_manually))
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
            _uiState.update { it.copy(error = appContext.getString(R.string.error_install_failed, e.message.orEmpty())) }
        }
    }

    // ===== 網路檔案快取 =====

    /**
     * 網路 (smb://) 檔案先下載到快取再交給 [onReady]；本機檔案直接回呼。
     * 畫面用這個處理圖片預覽、壓縮檔、APK 等需要本機檔案的功能。
     */
    fun withLocalCopy(fileItem: FileItem, onReady: (FileItem) -> Unit) {
        if (!SmbPath.isSmb(fileItem.path)) {
            onReady(fileItem)
            return
        }
        viewModelScope.launch {
            fetchToCache(fileItem)?.let(onReady)
        }
    }

    /** 下載到 cache/remote；同一個檔案 (路徑、大小、修改時間都相同) 不重複下載 */
    private suspend fun fetchToCache(fileItem: FileItem): FileItem? {
        val dir = File(appContext.cacheDir, "remote/${fileItem.path.hashCode().toUInt().toString(16)}")
        val target = File(dir, fileItem.name)
        val cached = target.isFile && target.length() == fileItem.size &&
            (fileItem.lastModified == 0L || target.lastModified() == fileItem.lastModified)

        if (!cached) {
            _uiState.update { it.copy(remoteFetch = RemoteFetch(fileItem.name, 0, fileItem.size)) }
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val partial = File(dir, "${fileItem.name}.part")
                    smbFiles.openRead(fileItem.path).use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var done = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - lastUpdate >= 512 * 1024) {
                                    lastUpdate = done
                                    _uiState.update { it.copy(remoteFetch = RemoteFetch(fileItem.name, done, fileItem.size)) }
                                }
                            }
                        }
                    }
                    target.delete()
                    check(partial.renameTo(target)) { "Cannot finish download: ${target.name}" }
                    if (fileItem.lastModified > 0) target.setLastModified(fileItem.lastModified)
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(error = appContext.getString(R.string.error_open_failed, describeError(e)))
                }
            }.isSuccess
            _uiState.update { it.copy(remoteFetch = null) }
            if (!ok) return null
        }

        return fileItem.copy(path = target.absolutePath)
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

    fun addManualSendDevice(ip: String, port: Int, onResult: (OpenFxDevice?) -> Unit) {
        viewModelScope.launch {
            val device = transferManager.addManualDevice(ip, port)
            if (device != null) _sendDevices.value = listOf(device) + _sendDevices.value.filterNot { it.ip == device.ip }
            onResult(device)
        }
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
                    error = appContext.getString(R.string.send_nothing_sendable)
                )
            }
            return
        }

        transferManager.sendFiles(paths, device)
        _uiState.update {
            it.copy(
                showSendDialog = false,
                selectedFiles = emptySet(),
                error = appContext.resources.getQuantityString(
                    R.plurals.send_tasks_added, paths.size, paths.size
                )
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

    /** USB / SD 卡插拔：更新磁區清單；目前所在的磁區被拔掉就回內部儲存 */
    private fun observeVolumeChanges() {
        viewModelScope.launch {
            StorageVolumes.changes(appContext).collect {
                val volumes = fileOps.getStorageVolumes()
                _uiState.update { it.copy(storageVolumes = volumes) }
                val current = _uiState.value.currentPath
                val gone = !SmbPath.isSmb(current) && volumes.none { current == it.path || current.startsWith("${it.path}/") }
                if (gone) navigateTo(Environment.getExternalStorageDirectory().absolutePath)
            }
        }
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
