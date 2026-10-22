package com.openfx.mobile.ui

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.openfx.mobile.ui.transfer.message
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.graphics.Color
import com.openfx.mobile.R
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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openfx.common.util.AppLocale.wrap(newBase))
    }

    @Inject
    lateinit var platformDetector: PlatformDetector

    @Inject
    lateinit var transferManager: com.openfx.core.sync.TransferManager

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 使用者從設定頁返回 */ }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 結果處理 */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairingLink(intent)
    }

    /** 系統相機掃到接收端 QR Code (openfx://pair?...) 時直接配對 */
    private fun handlePairingLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val code = intent.dataString?.let(com.openfx.core.sync.PairingCode::parse)
        if (code == null) {
            android.widget.Toast.makeText(this, R.string.qr_invalid, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.widget.Toast.makeText(this, R.string.pair_in_progress, android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = transferManager.pairWithCode(code)
            android.widget.Toast.makeText(this@MainActivity, result.message(resources), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 系統沒有可用的權限設定頁時顯示 adb 授權說明 */
    private var showStorageAccessHelp by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        if (!platformDetector.isTv) {
//            enableEdgeToEdge(
//                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
//            )
        //}

        // 用相機掃 QR Code 開啟時只做配對，不要先把人帶去權限設定頁
        val openedForPairing = savedInstanceState == null && intent?.action == Intent.ACTION_VIEW
        if (!openedForPairing) requestStoragePermission()
        requestNotificationPermission()
        if (openedForPairing) handlePairingLink(intent)
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
                            var tvOpenNetwork by remember { mutableStateOf(false) }

                            when (tvRoute) {
                                TvRoute.HOME -> {
                                    TvHomeScreen(
                                        onNavigateToFiles = {
                                            tvInitialPath = null
                                            tvOpenNetwork = false
                                            tvRoute = TvRoute.FILES
                                        },
                                        onNavigateToDevices = { tvRoute = TvRoute.DEVICES },
                                        onNavigateToNetwork = {
                                            tvInitialPath = null
                                            tvOpenNetwork = true
                                            tvRoute = TvRoute.FILES
                                        },
                                        onOpenVault = {
                                            startActivity(com.openfx.mobile.ui.vault.VaultActivity.intent(this@MainActivity))
                                        },
                                        onOpenUsb = {
                                            val usb = com.openfx.core.file.StorageVolumes.firstRemovable(this@MainActivity)
                                            if (usb == null) {
                                                android.widget.Toast.makeText(this@MainActivity, R.string.tv_usb_none, android.widget.Toast.LENGTH_LONG).show()
                                            } else {
                                                tvInitialPath = usb.path
                                                tvOpenNetwork = false
                                                tvRoute = TvRoute.FILES
                                            }
                                        },
                                    )
                                }

                                TvRoute.FILES -> {
                                    TvFileBrowserScreen(
                                        onBackToHome = { tvRoute = TvRoute.HOME },
                                        initialPath = tvInitialPath,
                                        openNetworkOnStart = tvOpenNetwork
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
                            com.openfx.mobile.ui.transfer.PairingPinPrompt(transferViewModel)
                            StorageAccessHelp()
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
                            com.openfx.mobile.ui.transfer.PairingPinPrompt(
                                androidx.hilt.navigation.compose.hiltViewModel()
                            )
                            StorageAccessHelp()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StorageAccessHelp() {
        if (!showStorageAccessHelp) return
        val command = "adb shell appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow"
        AlertDialog(
            onDismissRequest = { showStorageAccessHelp = false },
            title = { Text(stringResource(R.string.storage_access_title)) },
            text = { Text(stringResource(R.string.storage_access_message, command)) },
            confirmButton = {
                TextButton(onClick = {
                    showStorageAccessHelp = false
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    }
                }) { Text(stringResource(R.string.storage_access_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showStorageAccessHelp = false }) {
                    Text(stringResource(R.string.storage_access_later))
                }
            }
        )
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
                // 許多 Android TV / Google TV 沒有「所有檔案存取權」設定頁，
                // 兩個 intent 都可能丟 ActivityNotFoundException — 都失敗就改顯示 adb 說明
                val intents = listOf(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
                val launched = intents.any { intent ->
                    runCatching { storagePermissionLauncher.launch(intent) }.isSuccess
                }
                showStorageAccessHelp = !launched
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
