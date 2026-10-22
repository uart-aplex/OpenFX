package com.openfx.common.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App 內語言切換 (不跟系統語言)
 *
 * Android 13+ 交給系統 LocaleManager (會記住、設定 App 的語言頁也看得到)；
 * 更舊的版本 (多數電視盒) 自己存語言標籤，在每個 Activity 的 attachBaseContext 套用。
 */
object AppLocale {

    /** 空字串 = 跟隨系統 */
    val SUPPORTED = listOf("", "zh-TW", "zh-CN", "en")

    private const val PREFS = "openfx_locale"
    private const val KEY_TAG = "tag"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        }

    /** 切換語言；舊版 Android 需要重建 Activity 才會生效 */
    fun set(activity: Activity, tag: String) {
        if (tag == current(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
            activity.recreate()
        }
    }

    /** Activity.attachBaseContext 用；Android 13+ 原樣回傳 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** 選單上顯示的名稱：各語言用自己的寫法，讓看不懂目前語言的人也找得到 */
    fun displayName(tag: String): String? = when (tag) {
        "zh-TW" -> "繁體中文"
        "zh-CN" -> "简体中文"
        "en" -> "English"
        else -> null
    }
}
