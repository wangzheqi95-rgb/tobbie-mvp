# Neara 系統介面規格（v1）

三個端都透過雲端後端（Firebase）溝通：

```
機器人 Android App ──HTTPS（裝置金鑰）──▶ Cloud Functions /api/device/*
                                              │
                                              ├─▶ Firestore（事件、裝置、使用者）
                                              ├─▶ FCM 推播 → 家屬 App
                                              ├─▶ LINE Messaging API → 家屬 LINE
                                              └─▶ 守護中心網頁（Firestore 即時訂閱）
家屬 App／守護中心 ──Firebase Auth＋Firestore 規則──▶ 只看得到自己被授權的裝置
```

機器人不用 Firebase SDK（不需要 google-services.json），用一般 HTTPS 呼叫，像一台 IoT 裝置。

## 裝置驗證

- 第一次開機：`POST /api/device/register` 取得 `deviceKey`（只回傳一次，機器人存在本機）與 6 位數 `pairingCode`。
- 之後每個請求帶標頭：`Authorization: Device <deviceId>:<deviceKey>`。
- 伺服器只存 `deviceKey` 的 SHA-256，資料庫外洩也拿不到原始金鑰。
- 家屬在 App 輸入機器人螢幕上的配對碼 → `bindDevice`（callable）把裝置綁到自己的帳號。配對碼 10 分鐘有效、用過即失效。

## 機器人端 API（Cloud Functions，HTTPS）

| 方法 | 路徑 | 內容 | 回應 |
|---|---|---|---|
| POST | `/api/device/register` | `{deviceId, model, appVersion}` | `{deviceKey, pairingCode, pairingExpiresAt}` |
| POST | `/api/device/pairing-code` | — | `{pairingCode, pairingExpiresAt}`（重新產生） |
| POST | `/api/device/heartbeat` | `{state, chassis, battery?, appVersion}` | `{ok, config}` |
| GET | `/api/device/config` | — | `{elderName, profile, settings}`（家屬在 App 改的設定） |
| POST | `/api/device/incidents` | 事件 JSON（`neara.incident.v1`）＋ `snapshotJpegBase64?` | `{ok, id}` |
| PATCH | `/api/device/incidents/{id}` | 部分欄位（outcome、end、dialog、post、resolved…） | `{ok}` |
| POST | `/api/device/chat` | `{name, history:[{role, text}]}` | `{reply}`（AI 金鑰只在後端） |

## 通知規則

- 事件建立（偵測到跌倒）：通知家屬「機器人正在確認狀況」。
- `outcome = ALERTED`：**緊急通知**家屬（FCM 高優先＋LINE），守護中心警報清單出現紅色項目。
- `outcome = CANCELLED`：通知家屬「長者表示沒事」。
- 守護中心或家屬按「結案」：寫入 `resolved`、`resolvedBy`，機器人下次心跳收到。

## 資料保存

- 事件照片與骨架：預設保存 90 天，排程函式每天自動刪除過期資料（天數可在後端設定）。
- 操作紀錄（誰看了、誰結案）：保存於 `auditLogs`。

## 事件 JSON（neara.incident.v1）

見 `android-robot/core/.../Incident.kt`。主要欄位：
`id, deviceId, reason, start, lastWell, outcome, end, resolved, resolvedBy, profile, chassis, frameW, frameH, dialog[{t,who,text}], pre[{t,deg,p}], post[...]`。
