#!/usr/bin/env python3
"""Export the complete keyboard editor Smali sources needed for safe follow-up patches."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / "smali"
targets = [
    root / "com/android/inputmethod/latin/DictionaryEditor.smali",
    *sorted((root / "com/android/inputmethod/latin").glob("DictionaryEditor$*.smali")),
    root / "com/android/inputmethod/latin/LatinIME.smali",
    root / "com/android/inputmethod/latin/LatinKeyboardBaseView.smali",
    root / "com/android/inputmethod/latin/LatinKeyboardView.smali",
]
parts = []
for path in targets:
    if path.is_file():
        parts.append("\n\n===== " + str(path.relative_to(root)) + " =====\n")
        parts.append(path.read_text(encoding="utf-8", errors="replace"))
Path("keyboard-core-smali.txt").write_text("".join(parts), encoding="utf-8")
print("EXPORTED_CORE_SMALI_FILES=" + str(len(parts) // 2))
print("EXPORTED_CORE_SMALI_BYTES=" + str(sum(len(p.encode("utf-8")) for p in parts)))
