package com.openfx.mobile.ui.browser

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.file.*
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
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    init {
        loadStorageVolumes()
        navigateTo(Environment.getExternalStorageDirectory().absolutePath)
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
        val current = _uiState.value.currentPath
        val parent = current.substringBeforeLast('/')
        return if (parent.isNotEmpty() && parent != current) {
            navigateTo(parent)
            true
        } else false
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
        try {
            val file = File(fileItem.path)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, fileItem.mimeType ?: getMimeType(fileItem.path))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "無法開啟: ${e.message}") }
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
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
