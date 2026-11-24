#!/usr/bin/env python3
"""Avoid rebuilding the full reversed clipboard list for every new clip."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "smali/com/android/inputmethod/latin"
patches = {
    "DictionaryEditor$20.smali": (
        """    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor$20;->this$0:Lcom/android/inputmethod/latin/DictionaryEditor;
    invoke-virtual {v1}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V""",
        """    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor$20;->this$0:Lcom/android/inputmethod/latin/DictionaryEditor;
    iget-object v1, v1, Lcom/android/inputmethod/latin/DictionaryEditor;->listf:Ljava/util/List;
    const/4 v2, 0x0
    invoke-interface {v1, v2, v0}, Ljava/util/List;->add(ILjava/lang/Object;)V""",
    ),
    "DictionaryEditor$17.smali": (
        """    iget-object v2, p0, Lcom/android/inputmethod/latin/DictionaryEditor$17;->this$0:Lcom/android/inputmethod/latin/DictionaryEditor;
    invoke-virtual {v2}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V""",
        """    iget-object v2, p0, Lcom/android/inputmethod/latin/DictionaryEditor$17;->this$0:Lcom/android/inputmethod/latin/DictionaryEditor;
    iget-object v2, v2, Lcom/android/inputmethod/latin/DictionaryEditor;->listf:Ljava/util/List;
    const/4 v3, 0x0
    invoke-interface {v2, v3, v1}, Ljava/util/List;->add(ILjava/lang/Object;)V""",
    ),
}

for filename, (old, new) in patches.items():
    path = base / filename
    text = path.read_text(encoding="utf-8")
    old_count = text.count(old)
    new_count = text.count(new)
    if old_count == 1:
        text = text.replace(old, new, 1)
        path.write_text(text, encoding="utf-8")
        print(f"OK: {filename}: incremental newest-first clipboard update")
    elif old_count == 0 and new_count == 1:
        print(f"OK: {filename}: already optimized")
    else:
        raise SystemExit(
            f"Unexpected patch anchors in {filename}: old={old_count}, new={new_count}"
        )

print("OK: clipboard insertion no longer copies the entire history per new item.")
