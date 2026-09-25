package com.openfx.mobile.ui.browser

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
import java.io.File

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** 手機、TV 與車載 AI BOX 共用的「接收 APK 後安裝」流程。 */
@Composable
fun ReceivedApkInstallPrompt(lastReceivedFilePath: String?) {
    val context = LocalContext.current
    var apkToInstall by remember { mutableStateOf<FileItem?>(null) }
    var pendingPermissionFile by remember { mutableStateOf<File?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var lastPromptedPath by remember { mutableStateOf<String?>(null) }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val file = pendingPermissionFile
        pendingPermissionFile = null
        if (file != null && canInstallUnknownApps(context)) {
            installError = launchApkInstaller(context, file)
        } else if (file != null) {
            installError = "尚未允許 OpenFX 安裝未知來源的應用程式"
        }
    }

    LaunchedEffect(lastReceivedFilePath) {
        val path = lastReceivedFilePath ?: return@LaunchedEffect
        if (path.endsWith(".apk", ignoreCase = true) && path != lastPromptedPath) {
            val file = File(path)
            if (file.isFile) {
                lastPromptedPath = path
                apkToInstall = FileItem(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = false,
                    mimeType = APK_MIME_TYPE
                )
            }
        }
    }

    apkToInstall?.let { apk ->
        ApkInstallDialog(
            file = apk,
            received = true,
            onInstall = {
                val file = File(apk.path)
                if (canInstallUnknownApps(context)) {
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
                        installError = "請到系統設定允許 OpenFX 安裝未知來源的應用程式"
                    }
                }
            },
            onDismiss = { apkToInstall = null }
        )
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = { installError = null },
            title = { Text("無法安裝 APK") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { installError = null }) { Text("確定") }
            }
        )
    }
}

private fun canInstallUnknownApps(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        context.packageManager.canRequestPackageInstalls()

private fun launchApkInstaller(context: Context, file: File): String? {
    if (!file.isFile) return "接收到的 APK 檔案已不存在"

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
    }.exceptionOrNull()?.let { it.message ?: "系統找不到 APK 安裝器" }
}
