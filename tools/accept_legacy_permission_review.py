#!/usr/bin/env python3
"""Handle Android 10's legacy permission review and transient system dialogs."""
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
        raise SystemExit("adb " + " ".join(args) + " failed:\\n" + result.stdout)
    return result.stdout

def resumed_activity():
    output = adb("shell", "dumpsys", "activity", "activities")
    for line in output.splitlines():
        if "mResumedActivity:" in line:
            return line.strip()
    return ""

def find_action_button(xml_path):
    root = ET.parse(str(xml_path)).getroot()
    for node in root.iter("node"):
        text = (node.attrib.get("text") or "").strip().lower()
        desc = (node.attrib.get("content-desc") or "").strip().lower()
        if text in ("continue", "wait") or desc in ("continue", "wait"):
            bounds = node.attrib.get("bounds", "")
            match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
            if match:
                left, top, right, bottom = map(int, match.groups())
                return (left + right) // 2, (top + bottom) // 2, text
    return None

for attempt in range(6):
    current = resumed_activity()
    adb("shell", "uiautomator", "dump", "/sdcard/android-system-dialog.xml")
    adb("pull", "/sdcard/android-system-dialog.xml", "legacy-permission-review-ui.xml")
    button = find_action_button(Path("legacy-permission-review-ui.xml"))
    if button is None:
        print("No actionable system dialog; resumed activity: " + current)
        break
    action = button[2]
    print("Handling Android system dialog (" + action + "), attempt " + str(attempt + 1))
    adb("shell", "input", "tap", str(button[0]), str(button[1]))
    time.sleep(10 if action == "wait" else 3)

current = resumed_activity()
if "ReviewPermissionsActivity" in current:
    raise SystemExit("Android 10 permission review did not finish after six attempts: " + current)
print("System dialog handling finished; resumed activity: " + current)
