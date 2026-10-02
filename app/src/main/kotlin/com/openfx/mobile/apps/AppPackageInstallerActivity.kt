package com.openfx.mobile.apps

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.openfx.mobile.R
import com.openfx.mobile.ui.theme.OpenFXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

class AppPackageInstallerActivity : ComponentActivity() {
    private var message by mutableStateOf("")
    private var busy by mutableStateOf(true)
    private var sessionId = -1
    private var started = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstall()) prepare() else fail(getString(R.string.apps_install_permission))
    }
    private val confirmationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_CANCELED && busy) {
            abandon()
            fail(getString(R.string.apps_install_cancelled))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        started = savedInstanceState?.getBoolean("started") ?: false
        sessionId = savedInstanceState?.getInt("session", -1) ?: -1
        message = savedInstanceState?.getString("message") ?: getString(R.string.apps_install_preparing)
        busy = savedInstanceState?.getBoolean("busy") ?: true
        setContent {
            OpenFXTheme {
                BackHandler { if (!busy) finish() }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(stringResource(R.string.apps_install_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        if (busy) CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(message)
                        if (!busy) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { finish() }) { Text(stringResource(R.string.apps_close)) }
                        }
                    }
                }
            }
        }
        if (intent.action == ACTION_RESULT) handleResult(intent)
        else if (!started) {
            started = true
            if (canInstall()) prepare()
            else runCatching {
                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                })
            }.onFailure { fail(getString(R.string.apps_install_permission)) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RESULT) handleResult(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("started", started)
        outState.putInt("session", sessionId)
        outState.putString("message", message)
        outState.putBoolean("busy", busy)
        super.onSaveInstanceState(outState)
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun prepare() {
        lifecycleScope.launch {
            try {
                val path = requireNotNull(intent.getStringExtra(EXTRA_PATH))
                sessionId = AppPackageInstaller.stage(this@AppPackageInstallerActivity, File(path))
                val resultIntent = Intent(this@AppPackageInstallerActivity, AppPackageInstallerActivity::class.java)
                    .setAction(ACTION_RESULT)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val options = if (Build.VERSION.SDK_INT >= 35) {
                    ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }.toBundle()
                } else null
                val callback = PendingIntent.getActivity(this@AppPackageInstallerActivity, sessionId, resultIntent, flags, options)
                packageManager.packageInstaller.openSession(sessionId).use { it.commit(callback.intentSender) }
                message = getString(R.string.apps_install_waiting)
            } catch (error: CancellationException) {
                abandon()
                throw error
            } catch (error: Exception) {
                abandon()
                fail(getString(R.string.apps_install_failed, error.message.orEmpty()))
            }
        }
    }

    private fun handleResult(result: Intent) {
        val resultSession = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId >= 0 && resultSession != sessionId) return
        sessionId = resultSession
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                runCatching { confirmationLauncher.launch(requireNotNull(confirmation)) }
                    .onFailure { abandon(); fail(getString(R.string.apps_install_failed, it.message.orEmpty())) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                sessionId = -1
                message = getString(R.string.apps_install_success)
                busy = false
            }
            else -> {
                sessionId = -1
                val detail = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                fail(if (detail.contains("NO_MATCHING_ABIS")) getString(R.string.apps_install_incompatible_cpu)
                    else getString(R.string.apps_install_failed, detail))
            }
        }
    }

    private fun abandon() {
        if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
        sessionId = -1
    }

    private fun fail(text: String) {
        message = text
        busy = false
    }

    companion object {
        private const val EXTRA_PATH = "package_path"
        private const val ACTION_RESULT = "com.openfx.mobile.APP_INSTALL_RESULT"

        fun intent(context: Context, file: File): Intent =
            Intent(context, AppPackageInstallerActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
