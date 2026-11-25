#!/usr/bin/env python3
"""
石頭 S6 區網橋接程式（Neara 試用版）

用途：讓手機／電腦上的 Neara 網頁，透過這支程式在「家裡區網」直接控制石頭 S6，
      不經過石頭或小米的雲端（小米 miIO 協定，UDP 54321）。

為什麼需要它：瀏覽器沒辦法直接送 miIO 的 UDP 封包，所以由這支 Python 程式代送。
              網頁 → (HTTP) → 這支程式 → (miIO/UDP) → 石頭 S6

使用方式（在 Mac 上）：
  1. pip3 install python-miio
  2. 真機：python3 s6_bridge.py --ip 192.168.x.x --token 32位金鑰
     模擬：python3 s6_bridge.py --mock        （沒有機器人也能測網頁流程）
  3. 網頁「底盤設定」選「石頭 S6（橋接）」，橋接網址填 http://127.0.0.1:8765

也可以把 ip / token 寫在同資料夾的 s6_config.json（已被 .gitignore 排除，不會上傳 GitHub）：
  {"ip": "192.168.x.x", "token": "0123456789abcdef0123456789abcdef"}

安全設計：
  - 前進速度上限 0.15 m/s、單次動作最長 3 秒，避免撞到倒地的長者。
  - 每次遙控結束一定送出「結束遙控」指令（finally）。
  - 只聽本機 127.0.0.1（預設），區網其他人連不進來。
"""

import argparse
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_VELOCITY = 0.15      # m/s，靠近長者時的速度上限（原廠上限 0.3）
MAX_DURATION_MS = 3000   # 單次動作最長時間
TURN_DEG_PER_SEC = 60    # 原地轉向速度（度／秒）


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


# ---------------------------------------------------------------- 底盤實作
class MockS6:
    """模擬的 S6：不連任何機器，只把收到的指令記下來，方便測網頁流程。"""

    model = "mock.roborock.s6"

    def __init__(self):
        self.history = []
        self.state = "Charging"

    def _log(self, *msg):
        line = " ".join(str(m) for m in msg)
        self.history.append({"t": time.time(), "cmd": line})
        print(f"[MOCK] {line}", flush=True)

    def status(self):
        return {"state": self.state, "battery": 87, "error": None, "mock": True}

    def stop_cleaning(self):
        self._log("pause（停止打掃）")
        self.state = "Paused"

    def dock(self):
        self._log("home（回充）")
        self.state = "Returning home"

    def locate(self):
        self._log("find（「我在這裡」提示音）")

    def rc(self, rotation_deg, velocity, duration_ms):
        self._log(f"rc rotation={rotation_deg}°/s velocity={velocity}m/s duration={duration_ms}ms")
        time.sleep(duration_ms / 1000)

    def goto(self, x, y):
        self._log(f"goto ({x}, {y})")


class RealS6:
    """真的石頭 S6，透過 python-miio 在區網控制。"""

    def __init__(self, ip, token):
        from miio import RoborockVacuum  # 延後匯入：--mock 模式不需要安裝 python-miio
        self.dev = RoborockVacuum(ip, token, model="roborock.vacuum.s6")
        self.model = "roborock.vacuum.s6"

    def status(self):
        s = self.dev.status()
        return {
            "state": s.state,
            "battery": s.battery,
            "error": s.error if s.got_error else None,
            "mock": False,
        }

    def stop_cleaning(self):
        self.dev.pause()

    def dock(self):
        self.dev.home()

    def locate(self):
        self.dev.find()

    def rc(self, rotation_deg, velocity, duration_ms):
        # 遙控模式：開始 → 送一次動作 → 等動作做完 → 一定要結束遙控
        self.dev.manual_start()
        try:
            time.sleep(0.3)  # 機器切進遙控模式需要一點時間
            self.dev.manual_control(rotation_deg, velocity, duration_ms)
            time.sleep(duration_ms / 1000 + 0.2)
        finally:
            self.dev.manual_stop()

    def goto(self, x, y):
        self.dev.goto(int(x), int(y))


# ---------------------------------------------------------------- HTTP 介面
ROBOT = None
LOCK = threading.Lock()  # 一次只做一個動作，避免指令打架


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[HTTP] " + fmt % args, flush=True)

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        # Chrome「私人網路存取」規定：https 網頁呼叫本機 http 時，需要這個標頭
        self.send_header("Access-Control-Allow-Private-Network", "true")

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        try:
            if self.path == "/api/ping":
                return self._json(200, {"ok": True, "model": ROBOT.model, "mock": isinstance(ROBOT, MockS6)})
            if self.path == "/api/status":
                return self._json(200, {"ok": True, **ROBOT.status()})
            if self.path == "/api/history" and isinstance(ROBOT, MockS6):
                return self._json(200, {"ok": True, "history": ROBOT.history})
            return self._json(404, {"ok": False, "error": "沒有這個路徑"})
        except Exception as e:  # noqa: BLE001 — 錯誤一律回傳給網頁顯示
            return self._json(500, {"ok": False, "error": str(e)})

    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            data = json.loads(self.rfile.read(n) or b"{}") if n else {}
        except Exception:
            return self._json(400, {"ok": False, "error": "JSON 格式錯誤"})

        try:
            with LOCK:
                if self.path == "/api/stop":
                    ROBOT.stop_cleaning()
                elif self.path == "/api/dock":
                    ROBOT.dock()
                elif self.path == "/api/locate":
                    ROBOT.locate()
                elif self.path == "/api/turn":
                    # dir: "L" 左轉 / "R" 右轉；ms: 轉多久
                    ms = int(clamp(float(data.get("ms", 800)), 100, MAX_DURATION_MS))
                    sign = 1 if data.get("dir") == "L" else -1
                    ROBOT.rc(sign * TURN_DEG_PER_SEC, 0.0, ms)
                elif self.path == "/api/move":
                    # 往前（正）或往後（負）一小段
                    v = clamp(float(data.get("velocity", 0.1)), -MAX_VELOCITY, MAX_VELOCITY)
                    ms = int(clamp(float(data.get("ms", 1000)), 100, MAX_DURATION_MS))
                    ROBOT.rc(0, v, ms)
                elif self.path == "/api/goto":
                    ROBOT.goto(data["x"], data["y"])
                else:
                    return self._json(404, {"ok": False, "error": "沒有這個路徑"})
            return self._json(200, {"ok": True})
        except Exception as e:  # noqa: BLE001
            return self._json(500, {"ok": False, "error": str(e)})


def main():
    ap = argparse.ArgumentParser(description="石頭 S6 區網橋接程式")
    ap.add_argument("--ip", help="S6 在區網的 IP")
    ap.add_argument("--token", help="32 位十六進位金鑰")
    ap.add_argument("--mock", action="store_true", help="模擬模式，不連機器人")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="127.0.0.1", help="預設只聽本機；不建議改成 0.0.0.0")
    args = ap.parse_args()

    global ROBOT
    if args.mock:
        ROBOT = MockS6()
    else:
        ip, token = args.ip, args.token
        cfg_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "s6_config.json")
        if (not ip or not token) and os.path.exists(cfg_path):
            with open(cfg_path, encoding="utf-8") as f:
                cfg = json.load(f)
            ip, token = ip or cfg.get("ip"), token or cfg.get("token")
        if not ip or not token:
            ap.error("需要 --ip 和 --token（或 s6_config.json）；只想測網頁流程請加 --mock")
        ROBOT = RealS6(ip, token)

    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    mode = "模擬模式" if args.mock else f"真機 {args.ip or '(設定檔)'}"
    print(f"S6 橋接已啟動（{mode}）：http://{args.host}:{args.port}  按 Ctrl+C 結束", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
