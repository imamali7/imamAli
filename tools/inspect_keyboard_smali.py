#!/usr/bin/env python3
"""Map the decompiled keyboard classes involved in clipboard, cursor and key events."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]) / "smali"
needles = (
    "clipboard", "Clipboard", "Cursor", "cursor", "setSelection",
    "onUpdateSelection", "sendKeyEvent", "commitText", "setComposingText",
    "ACTION_DOWN", "ACTION_UP", "repeatCount", "moveCursor", "Selection",
    "onTouch", "onClick", "showDialog", "safeShowDialog",
)
out = []
for path in sorted(root.rglob("*.smali")):
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        continue
    lines = text.splitlines()
    hits = [i for i, line in enumerate(lines) if any(n in line for n in needles)]
    if not hits:
        continue
    out.append("\n### " + str(path.relative_to(root)) + "\n")
    seen = set()
    for i in hits:
        start, end = max(0, i - 3), min(len(lines), i + 5)
        if any(j in seen for j in range(start, end)):
            continue
        seen.update(range(start, end))
        out.extend(f"{j+1}: {lines[j]}" for j in range(start, end))
report = "\n".join(out)
Path("keyboard-smali-map.txt").write_text(report, encoding="utf-8")
print(f"SMALI_MAP_FILES={report.count(chr(10) + '### ')}")
print(report[:45000])
