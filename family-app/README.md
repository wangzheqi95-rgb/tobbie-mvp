# Neara 家屬 App（Flutter，iOS＋Android）

功能：
- 登入／註冊（Email）
- 輸入機器人畫面上的 6 位數配對碼，綁定長輩的機器人
- 首頁看每台機器人是否在線、最近事件
- 推播：緊急（長輩可能跌倒且沒回應，最高優先、會響鈴）與一般（確認中、已取消、已結案）；點通知直接打開事件報告
- 事件報告：摘要、最後正常活動時間、事件照片、問答紀錄、骨架動作重播、長輩資料；警報中可一鍵撥 119、結案（機器人會停止警報）
- 編輯長輩稱呼與資料（年齡、用藥、過敏、緊急聯絡人），機器人一分鐘內同步
- 綁定 LINE：手機沒開 App 也能在 LINE 收到警報

## 建置

1. 安裝 Flutter（https://docs.flutter.dev/get-started/install）。
2. 在這個資料夾執行 `flutter create --platforms=android,ios --org tw.neara .`（補上 android/ios 專案外殼，不會覆蓋 lib/）。
3. `dart pub global activate flutterfire_cli`，再執行 `flutterfire configure`，選 Neara 的 Firebase 專案 → 會產生 `lib/firebase_options.dart`。
4. `flutter run`。

## 上架前要做

- iOS：Apple 開發者帳號；在 Xcode 開啟 Push Notifications 與 Background Modes（Remote notifications）；上傳 APNs 金鑰到 Firebase。
- iOS 緊急通知（Critical Alerts，勿擾模式也會響）要向 Apple 另外申請權限。
- Android：Google Play 開發者帳號；隱私權政策網址；資料安全表單。
