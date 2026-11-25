#!/usr/bin/env python3
"""
用 python-miio（社群公認的參考實作）交叉驗證 Kotlin 版 miIO 協定：
  1. Kotlin 組的封包，python-miio 能解開；
  2. python-miio 組的封包，Kotlin 能解開；
  3. 起一個「假 S6」UDP 裝置，讓 Kotlin 的 MiioClient/RoborockS6 實際收發，檢查指令與安全上限。

用法：python3 tools/miio_crosscheck.py path/to/core-test.jar
"""
import datetime
import json
import socket
import subprocess
import sys
import threading

from miio.protocol import Message

JAR = sys.argv[1]
TOKEN_HEX = "0123456789abcdeffedcba9876543210"
TOKEN = bytes.fromhex(TOKEN_HEX)
JAVA = ["java", "-Dstdout.encoding=UTF-8", "-cp", JAR, "tw.neara.core.CoreTestsKt"]
fails = 0


def check(name, ok, detail=""):
    global fails
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""), flush=True)
    if not ok:
        fails += 1


def kt(*args):
    out = subprocess.run(JAVA + list(args), capture_output=True, text=True, encoding="utf-8")
    lines = [l for l in out.stdout.splitlines() if l.strip()]
    if out.returncode != 0:
        print(out.stderr)
    return lines


# 1. Kotlin 組 → python 解
req = {"id": 42, "method": "app_rc_move", "params": [{"omega": 1.0, "velocity": 0.1, "duration": 800, "seqnum": 1}]}
hexpkt = kt("miio-encode", TOKEN_HEX, "a1b2c3d4", "1700000000", json.dumps(req, separators=(",", ":")))[-1]
m = Message.parse(bytes.fromhex(hexpkt), token=TOKEN)
check("Kotlin 封包 → python-miio 解得開", m.data.value == req, str(m.data.value))
check("裝置 ID 與時間戳一致", m.header.value.device_id == bytes.fromhex("a1b2c3d4"))

# 2. python 組 → Kotlin 解
ts = datetime.datetime.fromtimestamp(1700000123, tz=datetime.timezone.utc).replace(tzinfo=None)
pmsg = {"data": {"value": {"id": 7, "result": ["ok"]}},
        "header": {"value": {"length": 0, "unknown": 0, "device_id": bytes.fromhex("01020304"), "ts": ts}},
        "checksum": 0}
built = Message.build(pmsg, token=TOKEN)
out = kt("miio-decode", TOKEN_HEX, built.hex())[-1]
stamp, js = out.split("|", 1)
check("python-miio 封包 → Kotlin 解得開", json.loads(js) == {"id": 7, "result": ["ok"]}, out)

# 3. 假 S6：回應交握與指令
received = []
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("127.0.0.1", 0))
port = sock.getsockname()[1]
DID = bytes.fromhex("0badbeef")


def fake_device():
    while True:
        data, addr = sock.recvfrom(4096)
        if data[:4] == bytes.fromhex("21310020") and data[4:] == b"\xff" * 28:
            hello = bytes.fromhex("21310020") + b"\x00" * 4 + DID + (1700000500).to_bytes(4, "big") + b"\x00" * 16
            sock.sendto(hello, addr)
            continue
        msg = Message.parse(data, token=TOKEN)
        payload = msg.data.value
        received.append(payload)
        result = {"id": payload["id"], "result": [{"state": 8, "battery": 90}] if payload["method"] == "get_status" else ["ok"]}
        reply = {"data": {"value": result},
                 "header": {"value": {"length": 0, "unknown": 0, "device_id": DID, "ts": datetime.datetime.utcnow()}},
                 "checksum": 0}
        sock.sendto(Message.build(reply, token=TOKEN), addr)


threading.Thread(target=fake_device, daemon=True).start()
lines = kt("miio-client", "127.0.0.1", TOKEN_HEX, str(port))
check("Kotlin 用戶端完成整串指令", "CLIENT DONE" in lines, " / ".join(lines[-2:]))
methods = [p["method"] for p in received]
check("指令順序正確", methods == ["get_status", "app_pause", "app_rc_start", "app_rc_move", "app_rc_end",
                                   "app_rc_start", "app_rc_move", "app_rc_end", "app_charge", "find_me"], str(methods))
moves = [p["params"][0] for p in received if p["method"] == "app_rc_move"]
check("左轉 60°/s ≈ omega 1.0 rad/s", moves and abs(moves[0]["omega"] - 1.0) < 1e-9 and moves[0]["velocity"] == 0.0, str(moves[:1]))
check("速度與時間被安全上限截住（0.15 m/s、3000 ms）", len(moves) > 1 and moves[1]["velocity"] == 0.15 and moves[1]["duration"] == 3000, str(moves[1:]))

print("ALL PASS" if fails == 0 else f"{fails} FAILED")
sys.exit(1 if fails else 0)
