# 上架 F-Droid

OpenFX 只用開源函式庫 (沒有 Google Play 服務、Firebase 或廣告 SDK)，可以直接送 F-Droid。
商店頁面的文字、圖示與截圖放在 [`fastlane/metadata/android`](../fastlane/metadata/android)，F-Droid 會自動讀取。

## 送審前檢查

- [x] repo 根目錄有 `LICENSE` 檔 (Apache-2.0 全文)
- [ ] `app/build.gradle.kts` 的 `versionCode` / `versionName` 已更新，並建立對應的 tag (例如 `v0.3.0`)
- [ ] `fastlane/metadata/android/*/changelogs/<versionCode>.txt` 已寫好這一版的更新內容
- [ ] 本機能用 `./gradlew :app:assembleRelease` 建置成功 (不設簽章環境變數時產生未簽章 APK，F-Droid 會用自己的金鑰簽)

## 送審方式

到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 開 merge request，新增 `metadata/com.openfx.mobile.yml`：

```yaml
Categories:
  - System
License: Apache-2.0
SourceCode: https://github.com/uart-aplex/OpenFX
IssueTracker: https://github.com/uart-aplex/OpenFX/issues
Changelog: https://github.com/uart-aplex/OpenFX/blob/HEAD/CHANGELOG.md

AutoName: OpenFX

RepoType: git
Repo: https://github.com/uart-aplex/OpenFX.git

Builds:
  - versionName: 0.3.0
    versionCode: 5
    commit: v0.3.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.3.0
CurrentVersionCode: 5
```

## 審核時可能被問到的權限

| 權限 | 用途 |
|------|------|
| `MANAGE_EXTERNAL_STORAGE` | 檔案管理器的核心功能 |
| `QUERY_ALL_PACKAGES` | 列出已安裝的 App，打包傳到另一台裝置 |
| `REQUEST_INSTALL_PACKAGES` | 安裝收到的 APK |
| `CAMERA` | 掃描接收端的配對 QR Code (選用，TV 不需要) |

## 簽章

F-Droid 版用 F-Droid 的金鑰簽章，GitHub Releases 的 APK 用作者的金鑰簽章 (見 [`.github/workflows/release.yml`](../.github/workflows/release.yml))。
兩者簽章不同，使用者不能直接從一邊更新到另一邊，必須先解除安裝。
