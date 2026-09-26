# Jellyfin Android TV 彈幕版

在官方 Jellyfin Android TV 上加入原生彈幕圖層，保留上游播放與緩衝策略。

## 功能與使用

播放影片時開啟控制列的彈幕設定。可自動或手動匹配節目與集數，設定來源／模式篩選、密度、防重疊、顯示高度、字體、100–900 字重、斜體及單集時間偏移。支援右向左、左向右、頂部及底部彈幕。設定預設字重為 400；Android 9 以前使用 normal/bold 的相容回退。

「字體」是 Android 系統已安裝的字體 family 名稱，例如 `sans-serif`、`serif`；不是 CSS 字體清單，也不會從電腦或網站自動下載字體。不存在的 family 會由 Android 回退。未實作 TTF/OTF 匯入。

自訂彈幕 API 可輸入根網址、帶 token 的根路徑，或以 `/api/v2` 結尾的網址。使用預設設定會連到參考專案的第三方 DandanPlay 代理；可改為自行管理的相容 API。

可優先讀取 Jellyfin 的 `jellyfin-plugin-danmu` XML 接口 `/api/danmu/{itemId}/raw`；XML 無資料時回退線上搜尋。簡繁轉換由線上 API 的 `chConvert` 參數處理，**不會在本機轉換 XML 的文字**。Web 與 TV 的彈幕設定、匹配記憶和 offset 仍各自儲存，不會自動跨裝置同步。

「加入來源」使用 `/api/v2/extcomment?url=...` 讀取播放頁網址的彈幕並合併到目前資料。需要 API 實作此接口；僅保留本次載入，重新載入或換集後不會保留。未實作 DandanPlay 登入、發送彈幕或提交關聯來源。

未移植參考 fork 的 10–20 分鐘強制緩衝修改。

## APK CI

Actions → **Danmaku / APK**：

- 所有 branch 的 push 都會執行；針對 `master` 的 PR 也會執行驗證，並支援手動 `workflow_dispatch`。
- `master` 的 push／手動執行成功後自動建立正式 GitHub Release；其他 branch 成功後自動建立 GitHub prerelease。PR 只驗證，不建立 Release。
- Release tag 採 commit-based 命名：`master` 為 `danmaku-<12位 commit>`，其他 branch 為 `danmaku-<branch>-<12位 commit>`；同一 commit 重跑會更新同一個 Release 與覆蓋 assets。
- 同一分支的新提交會取消舊建置。
- 編譯 Debug 與最佳化 Danmaku APK，執行 app Debug 單元測試。
- `danmaku-apks-<commit>` artifact 保留 30 天，含 Debug、unsigned APK、SHA-256 與建置資訊。
- `danmaku-tests-<commit>` 提供單元測試報告。
- 僅 `master` 的 push／手動執行可以使用 release signing secrets。簽名在另一台乾淨 runner 上執行，不 checkout 程式碼，不執行 Gradle，只下載同一 run 的已完成建置產物。
- 簽名前執行 zipalign，簽名後執行 apksigner verify，產物放在 `danmaku-signed-<commit>`。
- GitHub Release 會附上 Debug、unsigned APK、SHA-256 與 BUILD_INFO；`master` 若已設定四個簽名 secrets，正式 Release 也會附上 persistent-key signed APK 與簽章資訊。

### 安裝哪一個 APK

| 檔名 | 用途 |
| --- | --- |
| `jellyfin-danmaku-debug.apk` | 可直接安裝測試。package 為 `org.jellyfin.androidtv.debug`；可能與其他 Jellyfin Debug fork 衝突。 |
| `jellyfin-danmaku-unsigned.apk` | 最佳化建置，**未簽名，不能直接安裝**。package 為 `org.jellyfin.androidtv.danmaku`。 |
| `jellyfin-danmaku-signed.apk` | 設定下列 secrets 後產生，適合長期使用。package 為 `org.jellyfin.androidtv.danmaku`，可與官方版並存。 |

GitHub runner 的 Debug 簽名是臨時金鑰，不保證不同 run 的 APK 能覆蓋升級。不要把 Debug artifact 當作固定簽名的正式發行。要保留應用資料並長期覆蓋升級，使用自己的固定 release key。

CI 編譯成功不代表已在實機驗證。安裝後請測試遙控器焦點、暫停／seek／倍速、換集、自訂 API、XML 來源、字幕共存及密集彈幕效能。

## 需要新增的 Repository Secrets

GitHub repository → Settings → Secrets and variables → Actions → New repository secret：

| Secret | 內容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | JKS keystore 的 Base64 文字 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密碼 |
| `ANDROID_KEY_ALIAS` | 私鑰 alias，例如 `jellyfin-danmaku` |
| `ANDROID_KEY_PASSWORD` | 該 alias 私鑰的密碼 |

四個都不設定時，CI 仍會產出 Debug 與 unsigned APK，簽名步驟會明確跳過。只設定一部分會讓簽名 job 報錯。**不需要另外建立 PAT 或 `GITHUB_TOKEN` secret。**

### 在本機建立一次固定金鑰

請在專案外的安全目錄執行，需有 JDK 的 `keytool`：

```sh
keytool -genkeypair -v -keystore jellyfin-danmaku.jks -storetype JKS -alias jellyfin-danmaku -keyalg RSA -keysize 3072 -validity 10000
```

依提示輸入密碼與憑證資訊。私鑰密碼提示可直接 Enter 使用 keystore 的密碼，此時兩個 PASSWORD secrets 填相同值。不要把 keystore 或密碼貼進 issue、PR、聊天或 commit。

Windows PowerShell 將 Base64 複製到剪貼簿：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\jellyfin-danmaku.jks))) | Set-Clipboard
```

將剪貼簿內容貼入 `ANDROID_KEYSTORE_BASE64`，新增其他三個 secrets。金鑰檔與密碼應另行備份；遺失／更換私鑰可能無法覆蓋升級既有安裝。Base64 不是加密，和原始私鑰一樣敏感。

新增完後，在 Actions → Danmaku / APK → Run workflow 選 `master` 執行。PR 建置不會簽名，即使 secrets 已存在。

## 開發與來源

本地建置：`./gradlew :app:assembleDebug :app:assembleDanmaku :app:testDebugUnitTest`。未設定本機簽名參數時，Danmaku APK 為 unsigned。

基線為官方 `jellyfin/jellyfin-androidtv` 的 `f3fd7391a0778f73d0c0bea752378a83d86687bf`。
原生彈幕整合參考 `213366/Jellyfin-Android-TV-Danmaku-` 的三個純彈幕 commits：

- `774252d91487bf78fe422eb4ab7d9049baaf7f36`：core
- `ba14263a879c06bff44f9e543774e516db42dcd7`：playback integration
- `1512efd41937e674aac586637b6513cdeccea74c`：resources

行為參考 `Izumiko/jellyfin-danmaku`。本 repository 沿用 Jellyfin Android TV 的 GPL-2.0 授權；保留上游及參考實作的來源說明。
