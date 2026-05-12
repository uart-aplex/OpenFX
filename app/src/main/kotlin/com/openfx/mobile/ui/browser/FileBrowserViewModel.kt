package com.openfx.mobile.ui.browser

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.file.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
)

enum class ViewMode { GRID, LIST }

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileOps: FileOperations
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
                        pathHistory = history
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
            fileOps.delete(paths).onSuccess { count ->
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

    fun copySelectedTo(destination: String) {
        val sources = _uiState.value.selectedFiles.toList()
        viewModelScope.launch {
            fileOps.copy(sources, destination).collect { progress ->
                // TODO: 顯示進度到 UI
            }
            clearSelection()
            refresh()
        }
    }

    fun moveSelectedTo(destination: String) {
        val sources = _uiState.value.selectedFiles.toList()
        viewModelScope.launch {
            fileOps.move(sources, destination).collect { progress ->
                // TODO: 顯示進度到 UI
            }
            clearSelection()
            refresh()
        }
    }

    // ===== 檢視設定 =====

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun setSortBy(sort: SortBy) {
        _uiState.update { it.copy(sortBy = sort) }
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

    // ===== 搜尋 =====

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.length >= 2) {
            viewModelScope.launch {
                _uiState.update { it.copy(isSearching = true) }
                val results = mutableListOf<FileItem>()
                fileOps.search(_uiState.value.currentPath, query).collect { item ->
                    results.add(item)
                    if (results.size <= 100) { // 限制結果數量
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
}
