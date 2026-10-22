package com.openfx.mobile.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.network.smb.SmbConnectionPool
import com.openfx.core.network.smb.SmbServer
import com.openfx.core.network.smb.SmbServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「測試連線」的結果 */
sealed interface ConnectionTest {
    data object Running : ConnectionTest
    /** [shares] 為 null 表示登入成功但伺服器不提供共享清單 */
    data class Success(val shares: List<String>?) : ConnectionTest
    data class Failure(val error: Throwable) : ConnectionTest
}

/**
 * 網路位置 (SMB 伺服器) 管理 — 手機與 TV 共用
 */
@HiltViewModel
class NetworkServersViewModel @Inject constructor(
    private val repository: SmbServerRepository,
    private val pool: SmbConnectionPool
) : ViewModel() {

    val servers: StateFlow<List<SmbServer>> = repository.servers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _test = MutableStateFlow<ConnectionTest?>(null)
    val test: StateFlow<ConnectionTest?> = _test.asStateFlow()

    fun testConnection(server: SmbServer, password: String) {
        if (_test.value == ConnectionTest.Running) return
        _test.value = ConnectionTest.Running
        viewModelScope.launch {
            _test.value = pool.testLogin(server, password).fold(
                onSuccess = { ConnectionTest.Success(it) },
                onFailure = { ConnectionTest.Failure(it) }
            )
        }
    }

    fun clearTest() {
        _test.value = null
    }

    /** [password] 為 null 表示編輯時保留原密碼 */
    fun save(server: SmbServer, password: String?, onSaved: (SmbServer) -> Unit) {
        viewModelScope.launch {
            val saved = repository.save(server, password)
            // 帳號/密碼可能改了，丟掉舊連線
            pool.invalidate(saved.hostKey)
            onSaved(saved)
        }
    }

    fun delete(server: SmbServer) {
        viewModelScope.launch {
            repository.delete(server.id)
            pool.invalidate(server.hostKey)
        }
    }
}
