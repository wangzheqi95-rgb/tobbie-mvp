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
| POST | `/api/device/heartbeat` | `{state, chassis, appVersion, integrations:{healthConnect}}` | `{ok, config:{…, callList:[{name, phone}]}, resolvedIncidents:[id], ackedIncidents:[{id, by}]}` |
| GET | `/api/device/config` | — | `{elderName, profile, settings}`（家屬在 App 改的設定） |
| POST | `/api/device/incidents` | 事件 JSON（`neara.incident.v1`）＋ `snapshotJpegBase64?` | `{ok, id}` |
| PATCH | `/api/device/incidents/{id}` | 部分欄位（outcome、end、dialog、post、resolved…） | `{ok}` |
| POST | `/api/device/chat` | `{name, history:[{role, text}]}` | `{reply}`（AI 金鑰只在後端） |
| POST | `/api/device/health` | `{hr:[{t, bpm}], stepsToday?}`（手錶，Health Connect） | `{ok, kept}` |

## 家屬／守護中心 API（callable，需登入）

| 名稱 | 誰能用 | 內容 |
|---|---|---|
| `bindDevice` | 登入者 | `{code, relation}`：配對碼或邀請碼。第一個綁的人＝主要監護人 |
| `createInviteCode` | 該機器人的家人 | `{deviceId}` → `{code, expiresAt}`（6 位數，10 分鐘，用過即失效） |
| `updateGuardians` | 主要監護人／守護中心（退出自己：任何人） | `{deviceId, order?, relations?, remove?}` |
| `updateMyProfile` | 本人 | `{displayName?, phone?, notifyPrefs?:{checking, cancelled, offline, daily, autoCall}}`（緊急警報不能關） |
| `ackIncident` | 該機器人的家人／守護中心 | `{deviceId, incidentId}`：「我來處理」，停止往下通知 |
| `resolveIncident` | 同上 | `{deviceId, incidentId, note}`：結案，機器人停止警報 |
| `updateDeviceConfig` | 同上 | `{deviceId, elderName?, profile?}` |
| `createLineLinkCode` | 登入者 | → `{code}`，傳給 LINE 官方帳號完成綁定 |

## 通知規則

- 事件建立（偵測到跌倒）：通知家屬「機器人正在確認狀況」（每個人可在 App 關閉）。
- `outcome = ALERTED`：**緊急通知**，守護中心＋第 1 位監護人**同時**收到（FCM 高優先＋LINE）。
  - 試用期間所有家庭都通知守護中心（裝置 `plan` 未設定或 `trial`）；之後若有 `basic` 方案則不通知中心。
  - 每分鐘排程 `escalate`：3 分鐘內沒有人按「我來處理」→ 通知下一位監護人，直到名單通知完。
  - 有人按「我來處理」（`ackIncident`）→ 停止升級，通知已收到的其他人，機器人下次心跳告訴長輩誰在處理。
  - 機器人同時用自己的 SIM 卡撥號（開擴音）：先撥第 1 位，每 3 分鐘沒人接手撥下一位；撥號清單＝有填電話、沒關「自動來電」的監護人，依順序。
- `outcome = CANCELLED`：通知家屬「長者表示沒事」（可關閉）。
- 守護中心或家屬按「結案」：寫入 `resolved`、`resolvedBy`，機器人下次心跳收到，停止警報與撥號。
- 機器人超過 30 分鐘沒心跳：排程 `offlineCheck` 通知家屬一次（可關閉），恢復連線後重置。
- 每晚 9 點 `dailySummary`：今天偵測次數、步數（預設關閉，家人自己開）。

事件上多出的欄位：`escLevel, escNotified[uid], escNextAt, centerNotifiedAt, ackBy, ackByName, ackAt, vitals{hrBefore, hrAfter, source}`。
裝置上多出的欄位：`guardianOrder[uid], relations{uid:稱謂}, memberNames{uid:名稱}, plan, integrations{healthConnect{status, lastSync}}, offlineNotified`。

## 智慧裝置（目前：手錶 Health Connect）

- 手錶配對在**機器人那支手機**上；機器人 App 每 5 分鐘（警報中每 1 分鐘）讀心率與今天步數，上傳 `/api/device/health`。
- 後端只保留最近 2 小時心率（`devices/{id}/health/latest`），事件建立與更新時算出 `vitals`：事件前 10 分鐘內最後一筆、事件後 5 分鐘內最高一筆。
- 只讀心率與步數，不寫入、不讀其他健康資料。

## 資料保存

- 事件照片與骨架：預設保存 90 天，排程函式每天自動刪除過期資料（天數可在後端設定）。
- 操作紀錄（誰看了、誰結案）：保存於 `auditLogs`。

## 事件 JSON（neara.incident.v1）

見 `android-robot/core/.../Incident.kt`。主要欄位：
`id, deviceId, reason, start, lastWell, outcome, end, resolved, resolvedBy, profile, chassis, frameW, frameH, dialog[{t,who,text}], pre[{t,deg,p}], post[...]`。
