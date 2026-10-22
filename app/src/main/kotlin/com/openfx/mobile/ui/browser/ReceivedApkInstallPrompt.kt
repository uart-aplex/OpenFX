package com.openfx.mobile.ui.browser

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.openfx.mobile.R
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.openfx.core.file.FileItem
import com.openfx.mobile.apps.AppPackageInstallerActivity
import java.io.File

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** 手機、TV 與車載 AI BOX 共用的「接收 APK 後安裝」流程。 */
@Composable
fun ReceivedApkInstallPrompt(pendingPaths: List<String>, onHandled: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var apkToInstall by remember { mutableStateOf<FileItem?>(null) }
    var pendingPermissionFile by remember { mutableStateOf<File?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    val pendingPath = pendingPaths.firstOrNull()

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingPermissionFile
        pendingPermissionFile = null
        if (file != null && canInstallUnknownApps(context)) {
            installError = launchApkInstaller(context, file)
        } else if (file != null) {
            installError = resources.getString(R.string.install_unknown_not_allowed)
        }
    }

    LaunchedEffect(pendingPath) {
        val path = pendingPath ?: return@LaunchedEffect
        if (path.endsWith(".apk", ignoreCase = true) || path.endsWith(".apks", ignoreCase = true)) {
            val file = File(path)
            if (file.isFile) {
                apkToInstall = FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = false,
                    mimeType = APK_MIME_TYPE
                )
            } else onHandled(path)
        }
    }

    apkToInstall?.let { apk ->
        ApkInstallDialog(
            file = apk,
            received = true,
            onInstall = {
                val file = File(apk.path)
                if (file.extension.equals("apks", ignoreCase = true)) {
                    runCatching { context.startActivity(AppPackageInstallerActivity.intent(context, file)) }
                        .onFailure { installError = it.message }
                } else if (canInstallUnknownApps(context)) {
                    installError = launchApkInstaller(context, file)
                } else {
                    pendingPermissionFile = file
                    runCatching {
                        unknownSourcesLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }.onFailure {
                        pendingPermissionFile = null
                        installError = resources.getString(R.string.install_unknown_go_settings)
                    }
                }
            },
            onDismiss = { apkToInstall = null; onHandled(apk.path) }
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = { installError = null },
            title = { Text(stringResource(R.string.apk_install_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { installError = null }) { Text(stringResource(R.string.ok)) }
            }
        )
    }
}

private fun canInstallUnknownApps(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

private fun launchApkInstaller(context: Context, file: File): String? {
    if (!file.isFile) return context.getString(R.string.apk_received_missing)

    return runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val commonFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(commonFlags)
        }

        try {
            context.startActivity(installIntent)
        } catch (_: ActivityNotFoundException) {
            @Suppress("DEPRECATION")
            context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                addFlags(commonFlags)
            })
        }
    }.exceptionOrNull()?.let { it.message ?: context.getString(R.string.apk_no_installer) }
}
