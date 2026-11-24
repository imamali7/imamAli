#!/usr/bin/env python3
"""Accept Android 10's legacy permission-review screen in the CI emulator."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

def adb(*args, check=True):
    result = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    if check and result.returncode:
        raise SystemExit("adb " + " ".join(args) + " failed:\n" + result.stdout)
    return result.stdout

def resumed_activity():
    output = adb("shell", "dumpsys", "activity", "activities")
    for line in output.splitlines():
        if "mResumedActivity:" in line:
            return line.strip()
    return ""

def find_continue_button(xml_path):
    root = ET.parse(str(xml_path)).getroot()
    for node in root.iter("node"):
        text = (node.attrib.get("text") or "").strip().lower()
        description = (node.attrib.get("content-desc") or "").strip().lower()
        if text == "continue" or description == "continue":
            bounds = node.attrib.get("bounds", "")
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
            if match:
                left, top, right, bottom = map(int, match.groups())
                return (left + right) // 2, (top + bottom) // 2
    return None

for attempt in range(6):
    current = resumed_activity()
    if "ReviewPermissionsActivity" not in current:
        print("Legacy permission review cleared; resumed activity: " + current)
        sys.exit(0)
    adb("shell", "uiautomator", "dump", "/sdcard/legacy-review.xml")
    adb("pull", "/sdcard/legacy-review.xml", "legacy-permission-review-ui.xml")
    button = find_continue_button(Path("legacy-permission-review-ui.xml"))
    if button is None:
        raise SystemExit("Android 10 permission-review screen has no accessible Continue button; inspect legacy-permission-review-ui.xml")
    print("Accepting legacy permission review, attempt " + str(attempt + 1))
    adb("shell", "input", "tap", str(button[0]), str(button[1]))
    time.sleep(2)

current = resumed_activity()
if "ReviewPermissionsActivity" in current:
    raise SystemExit("Android 10 permission review did not finish after six attempts: " + current)
print("Legacy permission review cleared; resumed activity: " + current)
