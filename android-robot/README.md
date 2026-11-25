# Neara 機器人 Android App

裝在機器人上的「大腦」：一支 Android 手機架在底盤上，24 小時看、問、記、報。

| 資料夾 | 內容 |
|---|---|
| `core/` | 純 Kotlin 核心邏輯：跌倒偵測、回答判讀、台詞、事件紀錄、miIO 協定（石頭 S6）。不依賴 Android，可在電腦上測試。 |
| `app/` | Android App：相機＋MediaPipe 姿勢辨識、語音（TTS／STT）、照護狀態機、底盤（S6／Tobbie）、雲端上傳、設定頁。 |
| `tools/miio_crosscheck.py` | 用 python-miio 交叉驗證 miIO 封包，並用假 S6 做端到端 UDP 測試。 |

## 建置（第一次在 Mac 上）

1. 安裝 [Android Studio](https://developer.android.com/studio)（內含 JDK 與 Android SDK）。
2. Android Studio → Open → 選 `android-robot` 資料夾。第一次會自動下載 Gradle 與套件，等它跑完。
   - 第一次建置會自動下載姿勢辨識模型 `pose_landmarker_lite.task`（約 5.5 MB）到 `app/src/main/assets/`。
3. 手機開啟「開發人員選項 → USB 偵錯」，用傳輸線接 Mac，按 Run ▶。

## 第一次在機器人手機上設定

1. 打開 App，允許相機與麥克風（用 Tobbie 還要允許藍牙）。
2. 右下「設定」：
   - 稱呼、長者資料（印在事件報告上）
   - 底盤：石頭 S6 → 填 IP 與 32 位金鑰（取得方式見 `s6/README.md`）
   - 雲端後端網址（後端部署好之後填；沒填就只存在本機）
3. 系統設定 → 預設應用程式 → 主畫面 → 選「Neara 照護機器人」：開機就直接進入。
4. 設定裡勾「螢幕固定」：長者不會不小心跳出 App。

## 照護流程（對應提案 4.4）

```
監測 ─跌倒─▶ 前往（停止打掃→轉向→慢速靠近）─▶ 詢問兩次
                                              ├─ 沒事 → 取消 → 冷卻 20 秒 → 監測
                                              └─ 求救／沒回應 → 警報（通知家屬與守護中心，每 60 秒安撫一次）
主動關心：看到人且超過設定時間沒聊 → AI 陪聊；聊天中跌倒或聽到「救命」等，立刻改走跌倒流程
```

## 測試

```
# 核心邏輯（不需要 Android）
kotlinc core/src/main/kotlin core/src/test/kotlin -include-runtime -d core-test.jar
java -cp core-test.jar tw.neara.core.CoreTestsKt
# miIO 與 python-miio 交叉驗證、假 S6 端到端
pip3 install python-miio && python3 tools/miio_crosscheck.py core-test.jar
```

## 安全設計

- 跌倒是否成立、要不要警報：規則判斷，不交給 AI。
- 底盤失敗或逾時（8 秒）不會卡住詢問流程。
- S6 遙控速度上限 0.15 m/s、單次 3 秒；警報時底盤不再移動，避免碰到倒地長者。
- 影像只在記憶體處理；事件只存一張照片＋骨架座標。
- 事件先存本機再上傳，網路斷了下次心跳自動補傳。
