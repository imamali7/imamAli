#!/usr/bin/env python3
"""Static acceptance checks for the rebuilt clipboard, selection and portrait defaults."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

repo = Path(__file__).resolve().parents[1]
decoded = Path(sys.argv[1])
java = (repo / "tools/java/com/android/inputmethod/latin/ClipboardOrganizer.java").read_text(encoding="utf-8")
smali = (decoded / "smali/com/android/inputmethod/latin/DictionaryEditor.smali").read_text(encoding="utf-8")
prefs_path = decoded / "res/xml/prefs.xml"
android = "{http://schemas.android.com/apk/res/android}"
prefs = ET.parse(str(prefs_path)).getroot()

required_java = (
    "OnPrimaryClipChangedListener",
    "CLIPBOARD_EXECUTOR",
    "clipSignature",
    "clipboard_history_enabled",
    "selectionAnchorAbsolute",
    "selectionActiveAbsolute",
    "connection.setSelection(anchor, targetAbsolute)",
    "dismissListDialog()",
    "positionInputDialog(",
)
for token in required_java:
    if token not in java:
        raise SystemExit("Clipboard engine acceptance failed: missing Java token " + token)

required_smali = (
    "ClipboardOrganizer;->moveWithSelection",
    "ClipboardOrganizer;->resetSelectionSession",
    "ClipboardOrganizer;->startClipboardMonitor",
)
for token in required_smali:
    if token not in smali:
        raise SystemExit("Keyboard wiring acceptance failed: missing Smali call " + token)

portrait = [n for n in prefs.iter() if n.attrib.get(android + "key1") == "@string/key_hight_vertical"]
if len(portrait) != 1:
    raise SystemExit("Expected exactly one portrait keyboard preferences entry")
expected = {
    "defaultValue1": "138",
    "defaultValue2": "169",
    "defaultValue3": "142",
    "defaultValue4": "20",
    "defaultValue5": "58",
}
for key, value in expected.items():
    if portrait[0].attrib.get(android + key) != value:
        raise SystemExit("Portrait default mismatch: " + key)
toggles = [n for n in prefs.iter() if n.attrib.get(android + "key") == "clipboard_history_enabled"]
if len(toggles) != 1 or toggles[0].attrib.get(android + "defaultValue") != "true":
    raise SystemExit("Clipboard history preference missing or not enabled by default")

print("PASS: clipboard listener, async persistence, cumulative selection, keyboard-safe dialogs and all requested portrait defaults")
