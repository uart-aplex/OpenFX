# 參與 OpenFX 開發

感謝你願意幫忙！不論是回報問題、翻譯或送 PR 都歡迎。

## 回報問題

開 [issue](https://github.com/uart-aplex/OpenFX/issues) 時請附上：

- 裝置型號與 Android 版本，以及是手機、平板還是 TV
- OpenFX 版本 (設定 → 版本)
- 重現步驟、預期結果與實際結果
- 閃退的話附上 `adb logcat` 中 `FATAL EXCEPTION` 附近的內容

安全性問題 (例如繞過配對碼、保險箱加密) 請不要公開開 issue，改用 GitHub 的
[Private vulnerability reporting](https://github.com/uart-aplex/OpenFX/security/advisories/new)。

## 開發環境

見 [README 的設定指南](README.md#-android-studio-設定指南)。送 PR 前請在本機跑過：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :core:lintDebug :app:assembleDebug
```

CI 會跑同樣的檢查，lint 必須維持 0 個 error。

## 送 PR

1. Fork 後從 `main` 開分支，一個 PR 只做一件事
2. Commit 訊息第一行用英文祈使句簡述改了什麼 (例如 `Add QR code pairing`)，下面可以補充原因
3. 有 UI 的改動請在 PR 附上手機或 TV 的截圖
4. 同時影響手機與 TV 的功能，兩邊都要測；TV 請用遙控器 (D-pad) 實際操作一遍

## 程式風格

- Kotlin + Jetpack Compose，依賴注入用 Hilt，設定存在 DataStore
- 函式庫版本只寫在 `buildSrc/src/main/kotlin/Dependencies.kt`
- 手機與 TV 共用的邏輯放 `core`，畫面放 `app`；`core` 不放 UI 文字以外的畫面邏輯
- 註解用繁體中文，說明「為什麼」而不是「做了什麼」
- 更多細節見 [docs/OPENFX_CODE_CONVENTIONS.md](docs/OPENFX_CODE_CONVENTIONS.md)

### 畫面文字

- 所有使用者看得到的文字都要放在 `strings.xml`，不要寫死在程式碼裡
- 新增字串時三個語系 (`values`、`values-en`、`values-zh-rCN`) 要一起加
- Composable 裡讀資源用 `stringResource` 或 `LocalResources.current`，不要用 `LocalContext`
- 有數量的句子用 `<plurals>`

### TV 注意事項

- 每個可操作的元件都要能用 D-pad 移到並按下
- 輸入框要加 `Modifier.dpadFieldNavigation()`，否則方向鍵離不開輸入框
- 不要把有焦點的按鈕設成 disabled，焦點會跳走

## 翻譯

新增或修正翻譯請看 [TRANSLATING.md](TRANSLATING.md)。

## 授權

送出的貢獻會以 [Apache License 2.0](LICENSE) 授權。
