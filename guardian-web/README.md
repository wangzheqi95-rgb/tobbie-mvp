# Neara 守護中心後台（網頁）

守護中心值班人員用：即時看到所有機器人的事件，警報中的排最上面並顯示紅色橫幅；點進去看長者資料、緊急聯絡人、事件照片、問答紀錄、骨架動作重播、通知紀錄，填寫處理經過後結案（機器人會在 10 秒內停止警報）。

- 先看畫面：網址加 `?demo=1`（假資料，不需登入）。
- 正式使用：把 `config.example.js` 複製成 `config.js` 填入 Firebase 設定，用 `firebase deploy --only hosting` 部署（見 `backend/README.md`）。
- 帳號：只有被管理員設為 `guardian` 或 `admin` 的帳號能登入（`backend/scripts/set-role.js`）。
- 瀏覽器規定要點一下才能發出聲音，值班開始時請按「開啟警報音」。
