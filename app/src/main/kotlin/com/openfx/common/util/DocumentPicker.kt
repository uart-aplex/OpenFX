package com.openfx.common.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 裝置是否有真正可用的系統檔案選擇器 (Storage Access Framework)
 *
 * Android TV 常把 OPEN_DOCUMENT/CREATE_DOCUMENT 交給 frameworkpackagestubs 的空殼 Activity，
 * 只會顯示「不支援」然後回傳取消，不會丟例外 — 所以要看實際處理的套件名稱。
 */
fun hasDocumentPicker(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    val handler = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        ?: return false
    return !handler.activityInfo.packageName.contains("frameworkpackagestubs")
}
