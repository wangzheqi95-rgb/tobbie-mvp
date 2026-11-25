# Neara 雲端後端（Firebase，台灣區域 asia-east1）

| 項目 | 內容 |
|---|---|
| `functions/index.js` | 接上 Firebase：機器人 API、callable、LINE webhook、事件觸發通知、每日清除 |
| `functions/lib/handlers.js` | 業務邏輯（可在沒有網路的環境測試） |
| `functions/lib/core.js` | 純邏輯：驗證、事件檢查、通知規則、LINE 簽章 |
| `functions/test/` | 單元與流程測試：`npm test` |
| `firestore.rules`、`storage.rules` | 權限：所有寫入經伺服器；家屬只看得到自己綁定的機器人；角色由管理員設定 |
| `scripts/set-role.js` | 把帳號設成守護中心人員或管理員 |

介面規格見 `../docs/api.md`。

## 第一次部署（需要你的帳號）

1. 到 https://console.firebase.google.com 建立專案（例如 `neara-prod`），方案升級為 **Blaze（用多少付多少）**：Cloud Functions 需要。初期用量很小，通常在免費額度內，建議設定預算提醒。
2. 啟用：Authentication（Email/密碼）、Firestore（位置選 `asia-east1`）、Storage、Cloud Messaging。
3. 在這個資料夾：
   ```
   npm install -g firebase-tools
   firebase login
   firebase use --add          # 選剛建立的專案
   cd functions && npm install && npm test && cd ..
   firebase functions:secrets:set GEMINI_KEY
   firebase functions:secrets:set LINE_CHANNEL_TOKEN
   firebase functions:secrets:set LINE_CHANNEL_SECRET
   firebase deploy
   ```
4. 部署完成後，終端機會列出 `api` 的網址（例如 `https://api-xxxx-de.a.run.app`），填到機器人 App「設定 → 後端網址」。
5. LINE：在 LINE Developers 建立 Messaging API channel，Webhook URL 填 `lineWebhook` 的網址。
6. 守護中心帳號：先在 Authentication 新增使用者，再
   `GOOGLE_APPLICATION_CREDENTIALS=金鑰.json node scripts/set-role.js 值班員Email guardian 值班員姓名`

## 安全與隱私設計

- 機器人用「裝置金鑰」驗證，伺服器只存雜湊；配對碼 10 分鐘有效、用過即失效。
- 骨架與照片存在 Storage，Firestore 只放摘要；預設 90 天自動刪除。
- AI 金鑰只在後端（Secret Manager），不在機器人或 App 裡。
- 所有綁定、結案、改資料都寫入 `auditLogs`。
- 守護中心結案後，機器人再上傳也不會覆蓋結案資訊。
