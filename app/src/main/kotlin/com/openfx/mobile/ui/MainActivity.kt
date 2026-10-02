package com.openfx.mobile.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
//import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
//import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.graphics.Color
import androidx.activity.SystemBarStyle
import com.openfx.app.platform.Platform
import com.openfx.app.platform.PlatformDetector
import com.openfx.app.ui.tv.OpenFXTvTheme
import com.openfx.app.ui.tv.browser.TvFileBrowserScreen
import com.openfx.app.ui.tv.devices.TvDevicesScreen
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

//        if (!platformDetector.isTv) {
//            enableEdgeToEdge(
//                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
//            )
        //}

        requestStoragePermission()
        requestNotificationPermission()
//        window.statusBarColor = android.graphics.Color.TRANSPARENT
        setContent {
            when (platformDetector.platform) {
                // ===== TV 模式 =====
                Platform.TV -> {
                    OpenFXTvTheme {
                        @OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
                        androidx.tv.material3.Surface(modifier = Modifier.fillMaxSize()) {
                            val transferViewModel: com.openfx.mobile.ui.transfer.TransferViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                            val pendingAppInstalls by transferViewModel.pendingAppInstalls.collectAsState()
                            var tvRoute by remember { mutableStateOf(TvRoute.HOME) }
                            var tvInitialPath by remember { mutableStateOf<String?>(null) }

                            when (tvRoute) {
                                TvRoute.HOME -> {
                                    TvHomeScreen(
                                        onNavigateToFiles = {
                                            tvInitialPath = null
                                            tvRoute = TvRoute.FILES
                                        },
                                        onNavigateToDevices = { tvRoute = TvRoute.DEVICES },
                                    )
                                }

                                TvRoute.FILES -> {
                                    TvFileBrowserScreen(
                                        onBackToHome = { tvRoute = TvRoute.HOME },
                                        initialPath = tvInitialPath
                                    )
                                }

                                TvRoute.DEVICES -> {
                                    TvDevicesScreen(
                                        onBackToHome = { tvRoute = TvRoute.HOME },
                                        onOpenReceivedFiles = { path ->
                                            tvInitialPath = path
                                            tvRoute = TvRoute.FILES
                                        },
                                        onBrowseFilesToSend = {
                                            tvInitialPath = null
                                            tvRoute = TvRoute.FILES
                                        }
                                    )
                                }
                            }
                            com.openfx.mobile.ui.browser.ReceivedApkInstallPrompt(pendingAppInstalls, transferViewModel::consumeReceivedApp)
                        }
                    }
                }

                // ===== 手機 / 平板模式 =====
                Platform.PHONE, Platform.TABLET -> {
                    OpenFXTheme {
                        Surface(
                            modifier = Modifier
                            .fillMaxSize()
                            .systemBarsPadding(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            OpenFXNavHost()
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        // API 33+ 需要通知權限才能顯示音樂播放控制與傳輸進度通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
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

private enum class TvRoute {
    HOME,
    FILES,
    DEVICES
}
