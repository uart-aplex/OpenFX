package com.openfx.common.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.openfx.common.util.drawableToImageBitmap
import com.openfx.common.util.loadApkIcon
import com.openfx.core.file.FileItem
import com.openfx.core.network.smb.SmbPath

@Composable
fun FileIcon(
    file: FileItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val apkIcon = remember(file.path) {
        // 網路上的 APK 要先下載才讀得到圖示，列表裡先用一般圖示
        if (file.name.endsWith(".apk", ignoreCase = true) && !SmbPath.isSmb(file.path)) {
            loadApkIcon(context, file.path)
        } else {
            null
        }
    }

    when {
        apkIcon != null -> {
            Image(
                bitmap = drawableToImageBitmap(apkIcon),
                contentDescription = file.name,
                modifier = modifier
            )
        }

        file.isDirectory -> {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = file.name,
                modifier = modifier
            )
        }

        else -> {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = file.name,
                modifier = modifier
            )
        }
    }
}