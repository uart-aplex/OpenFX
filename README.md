# OpenFX — 開源 Android 檔案管理器

> 取代 ES File Explorer 的原生 Kotlin/Jetpack Compose 檔案管理器  
> **單一 APK** 同時支援手機 📱 平板 📲 和 Android TV 📺  
> 內建 P2P 跨裝置傳檔

---

## 🧰 主要功能

| 功能 | 說明 |
|------|------|
| 檔案管理 | 瀏覽、複製/移動/刪除/重新命名、搜尋、書籤、壓縮檔瀏覽與解壓、APK / 分割 APK 安裝 |
| 手機 ↔ TV 傳檔 | 區網自動發現 (UDP)，路由器擋廣播時可手動輸入 IP；接收端以 6 位數配對碼驗證，連錯 5 次鎖 60 秒 |
| 網路芳鄰 (SMB/NAS) | 儲存伺服器 (密碼以 Android Keystore 加密)、自動列出共享、本機 ↔ NAS 複製搬移、影片/音樂直接串流可拖曳 |
| 加密保險箱 | AES-256-GCM 加密檔案、檔名與資料夾；指紋解鎖、自動上鎖、禁止截圖、加密備份還原 — 見 [docs/VAULT.md](docs/VAULT.md) |
| USB / SD 卡 | 顯示系統磁區名稱，插拔即時更新；TV 首頁「USB 裝置」直接開啟隨身碟 |
| 多國語言 | 繁中 (預設)、English、简体中文；手機設定頁與 TV 首頁可直接切換 App 語言 — 見 [TRANSLATING.md](TRANSLATING.md) |
| TV 遙控器操作 | 所有畫面支援 D-pad，輸入框可用方向鍵離開，伺服器欄位自動切英數鍵盤 |

---

## ✨ 統一 APK 架構 (SmartTube 模式)

跟 SmartTube 一樣的做法：**一個 APK，安裝在哪裡就顯示對應的 UI**。

```
同一個 APK 安裝在...
├── 手機   → 自動切換觸控 UI (底部導航列 + Material3)
├── 平板   → 自動切換觸控 UI (未來加側邊欄)
└── TV     → 自動切換遙控器 UI (Leanback 大卡片 + D-pad)
```

核心機制：
1. `PlatformDetector` 啟動時偵測裝置類型 (UiModeManager + Leanback feature + 觸控螢幕)
2. `MainActivity.setContent` 根據偵測結果選擇不同的 Composable tree
3. AndroidManifest 同時宣告 `LAUNCHER` 和 `LEANBACK_LAUNCHER` category

---

## 🚀 Android Studio 設定指南

### 環境需求

| 項目 | 最低版本 |
|------|---------|
| Android Studio | Ladybug (2024.2) 或更新 |
| JDK | 17 |
| Android SDK | API 35 (Android 15) |
| Gradle | 8.9 (自動下載) |

### 快速上手 (3 步)

1. **解壓縮** ZIP 到你要的位置
2. Android Studio → **File → Open** → 選 `OpenFX` 資料夾
3. 等 Gradle sync 完成 (第一次約 5-10 分鐘)

### ⚠️ 第一次可能遇到的事

**gradle-wrapper.jar 找不到？**  
Android Studio 通常會自動提示 Generate → 按就好。
或在專案根目錄 terminal 跑：`gradle wrapper --gradle-version 8.9`

**SDK 路徑錯誤？**  
File → Project Structure → SDK Location → 選你的 Android SDK

### 執行方式

- **手機測試**: 選 `app` 模組 → 插手機 (或開手機模擬器) → ▶️ Run
- **TV 測試**: 選 `app` 模組 → 開 TV 模擬器 → ▶️ Run (同一個 APK!)

TV 模擬器開法：Tools → Device Manager → Create → TV → Android TV → API 35

---

## 📁 專案結構

```
OpenFX/
├── build.gradle.kts             ← 根建置檔
├── settings.gradle.kts          ← 只有 app + core 兩個模組
├── buildSrc/Dependencies.kt     ← 所有版本集中管理
│
├── core/                        ← 共用核心 (手機和 TV 共用)
│   ├── file/                    ← 檔案操作 (列舉/複製/搜尋)
│   ├── sync/                    ← P2P 發現、配對、TCP 傳輸
│   ├── network/                 ← SMB 客戶端 (NAS 連線)
│   └── common/                  ← Hilt DI 模組
│
└── app/                         ← 統一 APK (手機 + TV UI 都在這)
    ├── platform/                ← ⭐ PlatformDetector (核心切換邏輯)
    ├── ui/
    │   ├── MainActivity.kt      ← 統一入口 (根據平台選 UI tree)
    │   ├── NavHost.kt           ← 手機底部導航
    │   ├── browser/             ← 手機檔案瀏覽器 (網格/列表)
    │   ├── transfer/            ← 手機裝置配對 + 傳輸進度
    │   ├── settings/            ← 設定頁面
    │   ├── media/               ← 媒體播放 Service
    │   ├── theme/               ← Material3 主題
    │   └── tv/                  ← ⭐ TV 專用 UI (同一個 APK 內)
    │       ├── TvTheme.kt       ← TV 暗色主題
    │       ├── home/            ← TV 首頁 (Leanback 大卡片)
    │       └── remote/          ← TV 遠端控制 TCP 服務
    └── res/
        └── drawable/tv_banner.xml ← TV Launcher 的 320x180 橫幅
```

---

## 🔧 PlatformDetector 偵測邏輯

```kotlin
// 偵測順序 (和 SmartTube 相同策略)
1. UiModeManager.currentModeType == TV       → TV
2. PackageManager.hasSystemFeature(LEANBACK)  → TV
3. !hasSystemFeature(TOUCHSCREEN)             → TV
4. Build.MANUFACTURER == "Amazon" + AFT*      → Fire TV
5. 螢幕 >= LARGE                              → Tablet
6. 其他                                       → Phone
```

---

## ⚠️ 常見問題

**Q: Gradle sync 失敗？**  
確認網路正常。公司有 proxy 的話在 `gradle.properties` 加 `systemProp.http.proxyHost=...`

**Q: TV 模擬器跑起來但看到手機 UI？**  
確認模擬器是 "Android TV" 不是普通手機。UiModeManager 需要真正的 TV system image。

**Q: APK 裝到 TV 後在首頁找不到？**  
確認 AndroidManifest 有 `LEANBACK_LAUNCHER` category (已設定好) 和 `tv_banner.xml` 圖片。

**Q: APK 大小會不會因為包含兩套 UI 而變大很多？**  
不會。TV UI 的程式碼量很小 (幾個 Composable)。Compose for TV 和 Leanback 函式庫約多加 2-3MB，
但省去維護兩個 APK 的麻煩。SmartTube 也是這樣做的。

---

## 📄 授權

Apache License 2.0
