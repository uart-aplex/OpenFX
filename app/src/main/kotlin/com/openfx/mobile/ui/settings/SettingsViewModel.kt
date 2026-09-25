package com.openfx.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.file.SortBy
import com.openfx.core.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val showHidden: StateFlow<Boolean> = settings.showHiddenFiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val viewGrid: StateFlow<Boolean> = settings.defaultViewGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sortBy: StateFlow<SortBy> = settings.sortBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortBy.NAME)

    val sortAscending: StateFlow<Boolean> = settings.sortAscending
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val deviceName: StateFlow<String> = settings.deviceName
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.fallbackDeviceName)

    val transferNotify: StateFlow<Boolean> = settings.transferCompleteNotify
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowHidden(value: Boolean) {
        viewModelScope.launch { settings.setShowHiddenFiles(value) }
    }

    fun setViewGrid(value: Boolean) {
        viewModelScope.launch { settings.setDefaultViewGrid(value) }
    }

    fun setSortBy(value: SortBy) {
        viewModelScope.launch { settings.setSortBy(value) }
    }

    fun setSortAscending(value: Boolean) {
        viewModelScope.launch { settings.setSortAscending(value) }
    }

    fun setDeviceName(value: String) {
        viewModelScope.launch { settings.setDeviceName(value) }
    }

    fun setTransferNotify(value: Boolean) {
        viewModelScope.launch { settings.setTransferCompleteNotify(value) }
    }
}
