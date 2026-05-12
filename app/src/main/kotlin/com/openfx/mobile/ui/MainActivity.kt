package com.openfx.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.openfx.app.platform.Platform
import com.openfx.app.platform.PlatformDetector
import com.openfx.app.ui.tv.OpenFXTvTheme
import com.openfx.app.ui.tv.home.TvHomeScreen
import com.openfx.mobile.ui.theme.OpenFXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 統一入口 Activity — 同一個 APK，不同平台自動切換 UI
 *
 * 手機/平板 → 觸控 UI (底部導航列 + Compose Material3)
 * Android TV → 遙控器 UI (Leanback 大卡片 + D-pad focus)
 *
 * 參考 SmartTube 的做法：一個 Activity，runtime 決定 UI tree
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var platformDetector: PlatformDetector

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 使用者從設定頁返回 */ }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 結果處理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!platformDetector.isTv) {
            enableEdgeToEdge()
        }

        requestStoragePermission()

        setContent {
            when (platformDetector.platform) {
                // ===== TV 模式 =====
                Platform.TV -> {
                    OpenFXTvTheme {
                        @OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
                        androidx.tv.material3.Surface(modifier = Modifier.fillMaxSize()) {
                            TvHomeScreen(
                                onNavigateToFiles = { /* TODO: TV file browser */ },
                                onNavigateToDevices = { /* TODO: TV device pairing */ },
                            )
                        }
                    }
                }

                // ===== 手機 / 平板模式 =====
                Platform.PHONE, Platform.TABLET -> {
                    OpenFXTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            OpenFXNavHost()
                        }
                    }
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storagePermissionLauncher.launch(intent)
                }
            }
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }
}
