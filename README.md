# Neara 居家照護機器人

長者在家跌倒時，機器人會主動前往、開口確認，沒有回應就通知家屬與守護中心，並整理一份給醫師看的事件報告。

```
機器人 Android App ──▶ 雲端後端（Firebase，台灣）──▶ 家屬 App（推播）＋ LINE
     （看、問、記、報）                         └─▶ 守護中心網頁後台（即時警報、結案）
```

| 資料夾 | 是什麼 | 狀態 |
|---|---|---|
| `android-robot/` | 機器人端 Android App（跌倒偵測、語音詢問、AI 陪聊、底盤控制、事件紀錄、上傳） | 核心邏輯已測試；App 需在 Android Studio 建置 |
| `backend/` | 雲端後端：機器人 API、綁定、通知（推播＋LINE）、AI 代理、權限規則、資料保存期限 | 邏輯已測試；需部署到你的 Firebase 專案 |
| `guardian-web/` | 守護中心網頁後台 | 已用展示資料測試（網址加 `?demo=1`） |
| `family-app/` | 家屬 App（Flutter，iOS＋Android） | 需在 Flutter 環境建置 |
| `docs/api.md` | 三端之間的介面規格 | |
| `index.html`、`s6/`、`tobbie_ble.ts` | 第一版網頁 MVP（展示用）、石頭 S6 電腦橋接、Tobbie micro:bit 程式 | |

網頁 MVP：https://wangzheqi95-rgb.github.io/tobbie-mvp/
守護中心展示：https://wangzheqi95-rgb.github.io/tobbie-mvp/guardian-web/?demo=1
