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
import java.io.File

@Composable
fun FileIcon(
    file: File,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val apkIcon = remember(file.absolutePath) {
        if (file.name.endsWith(".apk", ignoreCase = true)) {
            loadApkIcon(context, file.absolutePath)
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