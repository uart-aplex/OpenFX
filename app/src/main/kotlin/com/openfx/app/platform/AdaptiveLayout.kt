package com.openfx.app.platform

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 自適應佈局資訊 — 根據螢幕大小和方向決定 UI 佈局策略
 *
 * 使用 Material3 WindowSizeClass 的概念，但不引入額外依賴。
 * 
 * 佈局決策邏輯：
 * ┌──────────────────────────────────────────────────┐
 * │ 裝置      │ 直向              │ 橫向              │
 * ├──────────────────────────────────────────────────┤
 * │ 手機      │ 單欄、底部導航    │ 雙欄、側邊導航    │
 * │ 平板      │ 單欄或雙欄        │ 雙欄、側邊導航    │
 * │ TV        │ (不適用)          │ 永遠雙欄+側邊     │
 * └──────────────────────────────────────────────────┘
 */
data class AdaptiveLayoutInfo(
    /** 螢幕寬度分類 */
    val widthClass: WindowWidthClass,
    /** 螢幕高度分類 */
    val heightClass: WindowHeightClass,
    /** 目前的螢幕方向 */
    val orientation: ScreenOrientation,
    /** 螢幕寬度 dp */
    val screenWidthDp: Int,
    /** 螢幕高度 dp */
    val screenHeightDp: Int,
) {
    /**
     * 是否應該使用雙欄佈局
     * - 手機橫向：是 (左側欄 + 右檔案清單)
     * - 平板任何方向：是
     * - TV：永遠是
     */
    val useDualPane: Boolean
        get() = widthClass != WindowWidthClass.COMPACT

    /**
     * 是否應該使用側邊導航列 (取代底部導航列)
     * - 手機直向：否 (用底部)
     * - 手機橫向：是 (用側邊，節省垂直空間)
     * - 平板/TV：是
     */
    val useSideNavigation: Boolean
        get() = widthClass != WindowWidthClass.COMPACT

    /**
     * 檔案網格每列的最少欄數
     */
    val gridMinColumns: Int
        get() = when (widthClass) {
            WindowWidthClass.COMPACT -> 4    // 手機直向：4 欄
            WindowWidthClass.MEDIUM -> 5     // 手機橫向 / 小平板：5 欄
            WindowWidthClass.EXPANDED -> 6   // 大平板 / TV：6 欄以上
        }

    /**
     * 側邊欄建議寬度
     */
    val sidebarWidth: Int
        get() = when (widthClass) {
            WindowWidthClass.COMPACT -> 0
            WindowWidthClass.MEDIUM -> 240
            WindowWidthClass.EXPANDED -> 300
        }
}

/**
 * 螢幕寬度分類 — 參考 Material3 WindowSizeClass
 * Compact: < 600dp (手機直向)
 * Medium:  600-839dp (手機橫向、小平板)
 * Expanded: >= 840dp (大平板、TV)
 */
enum class WindowWidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun fromWidth(widthDp: Int): WindowWidthClass = when {
            widthDp < 600 -> COMPACT
            widthDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

enum class WindowHeightClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun fromHeight(heightDp: Int): WindowHeightClass = when {
            heightDp < 480 -> COMPACT
            heightDp < 900 -> MEDIUM
            else -> EXPANDED
        }
    }
}

enum class ScreenOrientation {
    PORTRAIT, LANDSCAPE
}

/**
 * Composable 函式 — 在任何畫面中取得自適應佈局資訊
 * 會自動隨螢幕旋轉更新
 *
 * 用法：
 * ```
 * @Composable
 * fun FileBrowserScreen() {
 *     val layoutInfo = rememberAdaptiveLayoutInfo()
 *     if (layoutInfo.useDualPane) {
 *         // 雙欄：左邊側邊欄 + 右邊檔案清單
 *         Row {
 *             Sidebar(width = layoutInfo.sidebarWidth.dp)
 *             FileList(modifier = Modifier.weight(1f))
 *         }
 *     } else {
 *         // 單欄：只有檔案清單
 *         FileList()
 *     }
 * }
 * ```
 */
@Composable
fun rememberAdaptiveLayoutInfo(): AdaptiveLayoutInfo {
    val config = LocalConfiguration.current

    return remember(config.screenWidthDp, config.screenHeightDp, config.orientation) {
        AdaptiveLayoutInfo(
            widthClass = WindowWidthClass.fromWidth(config.screenWidthDp),
            heightClass = WindowHeightClass.fromHeight(config.screenHeightDp),
            orientation = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ScreenOrientation.LANDSCAPE
            } else {
                ScreenOrientation.PORTRAIT
            },
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
        )
    }
}
