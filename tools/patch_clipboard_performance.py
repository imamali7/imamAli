#!/usr/bin/env python3
"""Update the newest-first clipboard view incrementally instead of copying history."""
from pathlib import Path
import sys

base = Path(sys.argv[1]) / "smali/com/android/inputmethod/latin"
patches = {
    "DictionaryEditor$20.smali": (
        "    invoke-virtual {v1}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V",
        """    iget-object v1, v1, Lcom/android/inputmethod/latin/DictionaryEditor;->listf:Ljava/util/List;
    const/4 v2, 0x0
    invoke-interface {v1, v2, v0}, Ljava/util/List;->add(ILjava/lang/Object;)V""",
        "    invoke-interface {v1, v2, v0}, Ljava/util/List;->add(ILjava/lang/Object;)V",
    ),
    "DictionaryEditor$17.smali": (
        "    invoke-virtual {v2}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V",
        """    iget-object v2, v2, Lcom/android/inputmethod/latin/DictionaryEditor;->listf:Ljava/util/List;
    const/4 v3, 0x0
    invoke-interface {v2, v3, v1}, Ljava/util/List;->add(ILjava/lang/Object;)V""",
        "    invoke-interface {v2, v3, v1}, Ljava/util/List;->add(ILjava/lang/Object;)V",
    ),
}
for filename, (old, new, marker) in patches.items():
    path = base / filename
    text = path.read_text(encoding="utf-8")
    old_count = text.count(old)
    if old_count == 1:
        text = text.replace(old, new, 1)
        path.write_text(text, encoding="utf-8")
        print(f"OK: {filename}: replaced full-list rebuild with O(1) front insertion")
    elif old_count == 0 and marker in text:
        print(f"OK: {filename}: already optimized")
    else:
        raise SystemExit(f"Unexpected clipboard update anchor in {filename}: old={old_count}, optimized={marker in text}")
print("OK: incremental clipboard history updates validated.")
