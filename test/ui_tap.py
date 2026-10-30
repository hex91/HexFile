# -*- coding: utf-8 -*-
"""UI 自动化辅助：dump 后按文本找坐标并可选点击。
用法: python ui_tap.py <文本> [long|tap|info] [occurrence_index]
"""
import re, os, subprocess, sys, io

TEXT = sys.argv[1] if len(sys.argv) > 1 else ""
MODE = sys.argv[2] if len(sys.argv) > 2 else "tap"
IDX = int(sys.argv[3]) if len(sys.argv) > 3 else 0

def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True).stdout.decode("utf-8", "ignore")

sh("adb shell uiautomator dump /sdcard/ui.xml")
sh("adb pull /sdcard/ui.xml %TEMP%/ui.xml_x".replace("%TEMP%", os.environ["TEMP"].replace("\\", "/")))
p = os.environ["TEMP"].replace("\\", "/") + "/ui.xml_x"
xml = io.open(p, encoding="utf-8", errors="ignore").read()

hits = []
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if TEXT in m.group(1):
        x1, y1, x2, y2 = map(int, m.group(2, 3, 4, 5))
        hits.append((m.group(1), (x1 + x2) // 2, (y1 + y2) // 2))

if not hits:
    print("NOT_FOUND:" + TEXT)
    sys.exit(1)

if IDX >= len(hits):
    IDX = 0
name, cx, cy = hits[IDX]
print("FOUND: %s @ %d,%d" % (name, cx, cy))
if MODE == "long":
    sh("adb shell input swipe %d %d %d %d 900" % (cx, cy, cx, cy))
    print("LONG_TAPPED")
elif MODE == "tap":
    sh("adb shell input tap %d %d" % (cx, cy))
    print("TAPPED")
