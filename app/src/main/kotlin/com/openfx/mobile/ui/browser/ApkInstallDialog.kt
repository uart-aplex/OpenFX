package com.openfx.mobile.ui.browser

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.openfx.mobile.R
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfx.common.util.drawableToImageBitmap
import com.openfx.core.file.FileItem
import com.openfx.mobile.apps.AppPackageArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * APK 詳情 (安裝前顯示)
 */
private data class ApkInfo(
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val permissions: List<String>,
    val icon: ImageBitmap?,
)

private fun loadApkInfo(context: Context, path: String): ApkInfo? {
    if (path.endsWith(".apks", ignoreCase = true)) {
        return runCatching {
            val base = File.createTempFile("apk_preview_", ".apk", context.cacheDir)
            try {
                ZipFile(path).use { zip ->
                    val entry = AppPackageArchive.entries(zip).first { it.name == "base.apk" }
                    zip.getInputStream(entry).use { input ->
                        base.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= entry.size)
                                output.write(buffer, 0, count)
                            }
                            require(copied == entry.size)
                        }
                    }
                }
                loadApkInfo(context, base.absolutePath)
            } finally { base.delete() }
        }.getOrNull()
    }
    return runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS)
        } ?: return null

        val appInfo = info.applicationInfo ?: return null
        // 必須指定來源路徑，loadLabel/loadIcon 才讀得到 APK 內的資源
        appInfo.sourceDir = path
        appInfo.publicSourceDir = path

        ApkInfo(
            appName = runCatching { appInfo.loadLabel(pm).toString() }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: info.packageName,
            packageName = info.packageName,
            versionName = info.versionName,
            permissions = info.requestedPermissions?.toList() ?: emptyList(),
            icon = runCatching { drawableToImageBitmap(appInfo.loadIcon(pm)) }.getOrNull()
        )
    }.getOrNull()
}

/**
 * APK 安裝前詳情對話框 — 顯示 app 名稱、版本、套件名、要求的權限
 * 確認後才呼叫系統安裝器
 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun ApkInstallDialog(
    file: FileItem,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    received: Boolean = false
) {
    val context = LocalContext.current
    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val loading = remember(file.path) { androidx.compose.runtime.mutableStateOf(true) }
    val loadedInfo by produceState<ApkInfo?>(null, file.path) {
        value = withContext(Dispatchers.IO) { loadApkInfo(context, file.path) }
        loading.value = false
    }
    val info = loadedInfo

    AlertDialog(
        modifier = if (isTv) Modifier.width(600.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !isTv),
        onDismissRequest = onDismiss,
        title = { Text(if (received) stringResource(R.string.apk_received_install_question) else info?.appName ?: file.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (received) {
                    Text(info?.appName ?: file.name, fontWeight = FontWeight.SemiBold)
                    Text(file.name, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (info?.icon != null) {
                        Image(
                            bitmap = info.icon,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        if (info != null) {
                            Text(info.packageName, fontSize = 12.sp)
                            Text(
                                stringResource(R.string.apk_version, info.versionName ?: stringResource(R.string.unknown)),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(R.string.apk_size, file.formattedSize),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (loading.value) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else if (info == null) {
                    Text(
                        stringResource(R.string.apk_info_unreadable),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (info.permissions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.apk_permissions, info.permissions.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    info.permissions.take(if (isTv) 0 else 8).forEach { permission ->
                        Text(
                            "· ${permission.substringAfterLast('.')}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isTv && info.permissions.size > 8) {
                        Text(
                            pluralStringResource(R.plurals.apk_permissions_more, info.permissions.size, info.permissions.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isTv) {
                androidx.tv.material3.Button(
                    onClick = { onDismiss(); onInstall() },
                    enabled = info != null && !loading.value,
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = Color(0xFF0D6B4D), contentColor = Color.White,
                        focusedContainerColor = Color(0xFF57E3A1), focusedContentColor = Color(0xFF062B1C)
                    )
                ) { androidx.tv.material3.Text(stringResource(R.string.install)) }
            } else {
            TextButton(
                onClick = {
                    onDismiss()
                    onInstall()
                },
                enabled = info != null && !loading.value
            ) { Text(stringResource(R.string.install)) }
            }
        },
        dismissButton = {
            if (isTv) {
                androidx.tv.material3.Button(
                    onClick = onDismiss,
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = Color(0xFF252D31), contentColor = Color.White,
                        focusedContainerColor = Color(0xFF57E3A1), focusedContentColor = Color(0xFF062B1C)
                    )
                ) { androidx.tv.material3.Text(stringResource(R.string.cancel)) }
            } else {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
