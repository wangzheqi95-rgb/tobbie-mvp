# Neara 家屬 App（Flutter，iOS＋Android）

功能：
- 登入／註冊（Email）
- 輸入機器人畫面上的 6 位數配對碼，綁定長輩的機器人
- 首頁看每台機器人是否在線、最近事件
- 推播：緊急（長輩可能跌倒且沒回應，最高優先、會響鈴）與一般（確認中、已取消、已結案）；點通知直接打開事件報告
- 事件報告：摘要、最後正常活動時間、事件照片、問答紀錄、骨架動作重播、長輩資料；警報中可一鍵撥 119、結案（機器人會停止警報）
- 編輯長輩稱呼與資料（年齡、用藥、過敏、緊急聯絡人），機器人一分鐘內同步
- 綁定 LINE：手機沒開 App 也能在 LINE 收到警報
- **通知與監護人**：多位家人依順序通知（守護中心＋第 1 位同時，3 分鐘沒人接手通知下一位）、邀請家人（邀請碼）、調整順序、移除／退出；
  我的稱呼、電話（機器人 SIM 卡自動撥號用）、通知偏好（確認中、已取消、離線、每日摘要、自動來電；緊急警報不能關）
- 事件報告上的「**我來處理**」：按下後停止往下通知，其他家人與機器人都會知道是誰在處理；顯示「已通知誰、幾分鐘後通知誰」
- **智慧裝置**：手錶（Health Connect）連線狀態、最新心率、今天步數；事件報告附事件前後心率
- 配色照設計稿（沉穩青綠＋米白）
- **新手引導**三步驟、首頁長輩卡片、底部選單（長輩／通知／裝置／我的）、長輩資料表單、傳送測試警報
- **視訊通話**（Agora）：首頁「視訊通話」、警報中「視訊看現場」（機器人自動接通）
- 「我的」：服務條款、隱私權政策、刪除帳號

## 建置

1. 安裝 Flutter（https://docs.flutter.dev/get-started/install）。
2. 在這個資料夾執行 `flutter create --platforms=android,ios --org tw.neara .`（補上 android/ios 專案外殼，不會覆蓋 lib/）。
3. 視訊需要的權限（`flutter create` 之後加）：
   - `android/app/src/main/AndroidManifest.xml`：`CAMERA`、`RECORD_AUDIO`、`MODIFY_AUDIO_SETTINGS`、`INTERNET`
   - `ios/Runner/Info.plist`：`NSCameraUsageDescription`、`NSMicrophoneUsageDescription`（寫「視訊通話時讓長輩看到你、聽到你」）
4. `dart pub global activate flutterfire_cli`，再執行 `flutterfire configure`，選 Neara 的 Firebase 專案 → 會產生 `lib/firebase_options.dart`。
5. `flutter run`（條款網址預設 `https://neara-prod.web.app`，Firebase 專案名稱不同時加 `--dart-define=NEARA_WEB=https://你的專案.web.app`）。

> 注意：這個環境裝不了 Flutter，**家屬 App 還沒有編譯過**。第一次 `flutter run` 如果有錯誤，把訊息貼給我修。

## 上架前要做

- iOS：Apple 開發者帳號；在 Xcode 開啟 Push Notifications 與 Background Modes（Remote notifications）；上傳 APNs 金鑰到 Firebase。
- iOS 緊急通知（Critical Alerts，勿擾模式也會響）要向 Apple 另外申請權限。
- Android：Google Play 開發者帳號；隱私權政策網址；資料安全表單。
