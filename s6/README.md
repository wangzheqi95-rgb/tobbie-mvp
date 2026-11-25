# 石頭 S6 區網控制（Neara 試用版底盤）

不 root、不拆機：用小米的 miIO 協定，在家裡區網直接控制石頭 S6。

```
網頁（Chrome） ──HTTP──▶ s6_bridge.py（同一台電腦） ──miIO/UDP──▶ 石頭 S6
```

## 1. 準備（只做一次）

1. 手機用「米家」App（伺服器選台灣）綁定 S6，讓它連上 2.4 GHz Wi-Fi，**關閉韌體自動更新**。
2. Mac 安裝套件：`pip3 install python-miio`
3. 取得金鑰（token）：用開源工具 [Xiaomi Cloud Tokens Extractor](https://github.com/PiotrMachowski/Xiaomi-cloud-tokens-extractor)，登入米家帳號、伺服器選 `tw`，會列出 S6 的 IP 和 32 位 token。
4. 在這個資料夾建立 `s6_config.json`（已在 .gitignore，不會上傳）：

```json
{"ip": "192.168.x.x", "token": "0123456789abcdef0123456789abcdef"}
```

## 2. 實機測試

```
python3 s6_test.py --ip 192.168.x.x --token 你的金鑰
```

依序測：讀資訊 → 狀態 → 提示音 → 左轉 → 右轉 → 慢速前進，每一步前都會先問你。

## 3. 接上網頁

```
python3 s6_bridge.py          # 真機（讀 s6_config.json）
python3 s6_bridge.py --mock   # 模擬模式：沒有機器人也能測整個流程
```

用**同一台電腦**的 Chrome 開 Neara 網頁 → 「跌倒偵測與底盤設定」選「石頭 S6（區網橋接）」→ 按「連線底盤」。

偵測到跌倒時，S6 會：停止打掃 → 轉向長者 → 慢速靠近一小段（預設 1 秒、0.1 m/s）→ 由手機開口詢問。

## 安全限制（寫在 s6_bridge.py）

- 前進速度上限 0.15 m/s，單次動作最長 3 秒。
- 每次遙控結束一定送出「結束遙控」。
- 只聽本機 127.0.0.1，區網其他裝置連不進來。

## 已知限制

- 手機版網頁無法直接用 S6（瀏覽器不允許網頁連區網裡的其他裝置），要等 Android App。
- 石頭若更新韌體，可能關閉區網控制，所以要關自動更新。
- 左右轉方向要實測；轉反了就在網頁勾「左右對調」。
