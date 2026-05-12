package com.openfx.app.platform

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 平台偵測器 — 運行時判斷目前在哪種裝置上
 * 
 * 同一個 APK 安裝在手機、平板、Android TV 都能正確識別。
 * 偵測邏輯參考 SmartTube 的做法：
 *   1. UiModeManager 是否回報 TV mode
 *   2. 系統是否有 Leanback feature
 *   3. 是否缺少觸控螢幕
 *   4. 特定 TV 廠牌 (如 Fire TV) 的 Build 特徵
 */
@Singleton
class PlatformDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 目前裝置的平台類型，啟動後不會改變
     */
    val platform: Platform by lazy { detectPlatform() }

    /**
     * 快捷判斷：是否在 TV 上
     */
    val isTv: Boolean get() = platform == Platform.TV

    /**
     * 快捷判斷：是否在手機上
     */
    val isPhone: Boolean get() = platform == Platform.PHONE

    /**
     * 快捷判斷：是否在平板上
     */
    val isTablet: Boolean get() = platform == Platform.TABLET

    /**
     * 是否支援觸控（部分 TV 外接觸控螢幕也可能回 true）
     */
    val hasTouchScreen: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * 是否有 D-pad 或遙控器可用
     */
    val hasDpad: Boolean by lazy {
        // TV 通常有 D-pad；某些遊戲手柄也算
        isTv || context.packageManager.hasSystemFeature("android.hardware.gamepad")
    }

    private fun detectPlatform(): Platform {
        // ===== 方法 1: UiModeManager (最可靠) =====
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return Platform.TV
        }

        // ===== 方法 2: Leanback feature (Android TV 必須有) =====
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            || pm.hasSystemFeature("android.software.leanback_only")
        ) {
            return Platform.TV
        }

        // ===== 方法 3: 無觸控螢幕 → 幾乎確定是 TV =====
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return Platform.TV
        }

        // ===== 方法 4: 特殊裝置指紋 =====
        // Amazon Fire TV
        if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
            && (Build.MODEL.startsWith("AFT") || Build.MODEL.contains("Fire TV"))
        ) {
            return Platform.TV
        }
        // Chromecast with Google TV
        if (Build.DEVICE?.contains("sabrina") == true
            || Build.DEVICE?.contains("boreal") == true
        ) {
            return Platform.TV
        }

        // ===== 方法 5: 螢幕大小判斷手機 vs 平板 =====
        val config = context.resources.configuration
        val screenLayout = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK
        return if (screenLayout >= Configuration.SCREENLAYOUT_SIZE_LARGE) {
            Platform.TABLET
        } else {
            Platform.PHONE
        }
    }
}

/**
 * 裝置平台類型
 */
enum class Platform {
    /** 手機 — 觸控操作，底部導航列 */
    PHONE,
    /** 平板 — 觸控操作，可能是側邊導航列 */
    TABLET,
    /** Android TV — D-pad 遙控器操作，Leanback 大卡片 UI */
    TV
}
