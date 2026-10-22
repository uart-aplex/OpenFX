# Changelog

## 0.3.0

### 安全
- 接收端改用 6 位數配對碼 (`X-OpenFX-Pin`)，連錯 5 次鎖 60 秒；回應 JSON 正確跳脫
- 修正 TV 沒有「所有檔案存取權」設定頁時閃退，改顯示 adb 授權說明

### 新功能
- 網路芳鄰 (SMB/NAS)：伺服器管理、共享列表、本機 ↔ NAS 複製搬移、影音串流
- 加密保險箱：AES-256-GCM、指紋解鎖、自動上鎖、禁止截圖、加密備份還原 (docs/VAULT.md)
- QR Code 配對：TV 接收畫面顯示 QR Code，手機掃描即可配對 (也支援系統相機)
- 手動以 IP 加入接收端 (AP 隔離 / mesh 網路)
- USB 隨身碟 / SD 卡以系統名稱顯示，插拔即時更新；TV「USB 裝置」卡片可用
- App 內切換語言 (繁中 / English / 简体中文)，手機與 TV 皆可

### 其他
- 移除未使用的 Wi-Fi Direct 與定位權限；Wi-Fi 改為非必要功能 (有線電視盒可安裝)
- GitHub Actions CI：單元測試、lint、debug APK；推 v* tag 自動建置簽章版並發布
- 修正 release (R8 縮減) 版啟動即閃退
- F-Droid 上架資料 (fastlane)、CONTRIBUTING.md
