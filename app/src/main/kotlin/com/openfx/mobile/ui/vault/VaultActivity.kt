package com.openfx.mobile.ui.vault

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.openfx.core.vault.VaultManager
import com.openfx.mobile.ui.theme.OpenFXTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 加密保險箱 — 手機與 TV 共用
 *
 * - FLAG_SECURE：禁止截圖、錄影，也不會出現在最近使用的縮圖裡
 * - 離開畫面一分鐘後自動上鎖 (選檔、拍照會短暫離開，所以不是立刻上鎖)
 */
@AndroidEntryPoint
class VaultActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openfx.common.util.AppLocale.wrap(newBase))
    }

    @Inject
    lateinit var manager: VaultManager

    private val viewModel: VaultViewModel by viewModels()

    /** 只有支援強生物辨識的裝置才提供 (TV 沒有) */
    private val biometric: VaultBiometric? by lazy {
        VaultBiometric(this, manager.store).takeIf { it.isAvailable || it.isEnabled }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        if (savedInstanceState == null) {
            intent.getStringArrayListExtra(EXTRA_IMPORT_PATHS)?.takeIf { it.isNotEmpty() }?.let { paths ->
                viewModel.requestImportAfterUnlock(paths, intent.getBooleanExtra(EXTRA_DELETE_ORIGINALS, false))
            }
        }

        setContent {
            OpenFXTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VaultScreen(viewModel = viewModel, onClose = ::finish, biometric = biometric)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        manager.cancelScheduledLock()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) manager.scheduleLock()
    }

    companion object {
        private const val EXTRA_IMPORT_PATHS = "import_paths"
        private const val EXTRA_DELETE_ORIGINALS = "delete_originals"

        fun intent(context: Context): Intent = Intent(context, VaultActivity::class.java)

        /** 開啟保險箱並 (解鎖後) 加密匯入這些本機檔案/資料夾 */
        fun importIntent(context: Context, paths: List<String>, deleteOriginals: Boolean): Intent =
            intent(context)
                .putStringArrayListExtra(EXTRA_IMPORT_PATHS, ArrayList(paths))
                .putExtra(EXTRA_DELETE_ORIGINALS, deleteOriginals)
    }
}
