#!/usr/bin/env python3
"""
石頭 S6 區網控制「實機測試」腳本：拿到金鑰後第一個要跑的程式。

  pip3 install python-miio
  python3 s6_test.py --ip 192.168.x.x --token 32位金鑰

會依序：讀機器資訊 → 讀狀態 → 「我在這裡」提示音 → 左轉 1 秒 → 右轉 1 秒 → 前進 0.5 秒。
每一步之前都會問你要不要繼續（輸入 y），讓你確定機器人周圍沒有東西再動。
"""

import argparse
import time


def ask(msg):
    return input(f"\n{msg}（y 繼續，其他鍵跳過）：").strip().lower() == "y"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", required=True)
    ap.add_argument("--token", required=True)
    a = ap.parse_args()

    from miio import RoborockVacuum
    vac = RoborockVacuum(a.ip, a.token, model="roborock.vacuum.s6")

    print("① 讀機器資訊（連不上的話，多半是 IP 或金鑰錯，或電腦和機器人不在同一個網路）")
    info = vac.info()
    print(f"   型號：{info.model}　韌體：{info.firmware_version}")

    print("② 讀狀態")
    s = vac.status()
    print(f"   狀態：{s.state}　電量：{s.battery}%")

    if ask("③ 播放「我在這裡」提示音"):
        vac.find()

    def rc(rotation, velocity, ms, label):
        if not ask(f"{label}（請確認周圍淨空）"):
            return
        vac.manual_start()
        try:
            time.sleep(0.3)
            vac.manual_control(rotation, velocity, ms)
            time.sleep(ms / 1000 + 0.2)
        finally:
            vac.manual_stop()

    rc(60, 0.0, 1000, "④ 原地左轉 1 秒")
    rc(-60, 0.0, 1000, "⑤ 原地右轉 1 秒")
    rc(0, 0.1, 500, "⑥ 慢速前進 0.5 秒（約 5 公分）")

    print("\n測試結束。如果左右轉反了，網頁「底盤設定」勾選「左右對調」即可。")


if __name__ == "__main__":
    main()
