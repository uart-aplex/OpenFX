package com.openfx.mobile.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.mobile.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InstalledAppsState(
    val apps: List<InstalledApp> = emptyList(),
    val loading: Boolean = true,
    val error: Int? = null
)

@HiltViewModel
class InstalledAppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(InstalledAppsState())
    val state = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = InstalledAppsState()
            try {
                _state.value = InstalledAppsState(apps = repository.listApps(), loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = InstalledAppsState(loading = false, error = R.string.apps_load_failed)
            }
        }
    }
}
