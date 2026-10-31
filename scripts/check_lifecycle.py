#!/usr/bin/env python3
"""Regression check on an emulator with ZLNA installed and a test folder already selected."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This check is restricted to disposable Android emulators")

    def adb(*command):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True).strip()

    def ui():
        adb("shell", "uiautomator", "dump", "/data/local/tmp/zlna-lifecycle.xml")
        return ET.fromstring(adb("exec-out", "cat", "/data/local/tmp/zlna-lifecycle.xml"))

    def tap(root, *labels):
        node = next((n for n in root.iter("node") if n.get("text") in labels and n.get("clickable") == "true"), None)
        assert node is not None, f"Button {labels!r} missing. Select a test folder and stop sharing first."
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    adb("shell", "pm", "grant", "app.zlna", "android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", "app.zlna/.MainActivity")
    original_pid = adb("shell", "pidof", "app.zlna")
    for cycle, notification in enumerate((False, True, False), 1):
        tap(ui(), "运行", "Start")
        current = ui()
        texts = {n.get("text") for n in current.iter("node")}
        assert {"正在共享", "Sharing"} & texts, "Sharing did not start; inspect the UI and ZLNA log"
        if notification:
            adb("shell", "cmd", "statusbar", "expand-notifications")
            tap(ui(), "停止共享", "Stop sharing")
            adb("shell", "cmd", "statusbar", "collapse")
        else:
            tap(current, "停止共享", "Stop sharing")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            log = adb("logcat", "-d", "-s", "ZLNA:I", "AndroidRuntime:E")
            if log.count("ZLNA stopped") >= cycle:
                break
            time.sleep(0.1)
        assert log.count("ZLNA stopped") == cycle, log
        assert "FATAL EXCEPTION" not in log, log
        assert adb("shell", "pidof", "app.zlna") == original_pid, "Process died during stop"
        assert "isForeground=true" not in adb("shell", "dumpsys", "activity", "services", "app.zlna")
        # dumpsys also contains historical acquire/release logs; inspect only active locks.
        power = adb("shell", "dumpsys", "power")
        assert "Wake Locks:" in power, "Cannot locate active wake-lock section"
        active_locks = power.split("Wake Locks:", 1)[1].split("\n\n", 1)[0]
        assert "ZLNA:sharing" not in active_locks, "Wake lock leaked"
        texts = {n.get("text") for n in ui().iter("node")}
        assert {"共享已停止", "Sharing stopped"} & texts and {"运行", "Start"} & texts, texts
        print(f"PASS cycle {cycle}: {'notification' if notification else 'app'} stop; process survives; service and wake lock released")
    print("PASS start/stop regression, including NetworkOnMainThreadException check")


if __name__ == "__main__":
    main()
