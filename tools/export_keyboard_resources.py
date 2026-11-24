#!/usr/bin/env python3
"""Export keyboard layouts, keyboard XMLs and resource ID mappings from Apktool output."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
out = []
public = root / "res/values/public.xml"
if public.exists():
    text = public.read_text(encoding="utf-8", errors="replace")
    out.append("===== res/values/public.xml =====\n" + text)
    import re
    wanted = {"0x7f030021", "0x7f030022", "0x7f030023", "0x7f030024", "0x7f030025", "0x7f030026"}
    for line in text.splitlines():
        if any(value in line for value in wanted):
            print("RESOURCE_ID " + line.strip())

for folder in ("res/layout", "res/xml"):
    base = root / folder
    if base.exists():
        for path in sorted(base.rglob("*.xml")):
            try:
                data = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            out.append("\n===== " + str(path.relative_to(root)) + " =====\n" + data)

for path in (root / "res/values/strings.xml", root / "res/values/ids.xml"):
    if path.exists():
        out.append("\n===== " + str(path.relative_to(root)) + " =====\n")
        out.append(path.read_text(encoding="utf-8", errors="replace"))

report = "".join(out)
Path("keyboard-ui-resources.txt").write_text(report, encoding="utf-8")
print("EXPORTED_UI_RESOURCE_BYTES=" + str(len(report.encode("utf-8"))))
