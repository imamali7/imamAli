#!/usr/bin/env python3
"""Apply narrowly-scoped, reproducible Smali optimizations to the decoded APK."""
from pathlib import Path
import sys

TARGET = Path(sys.argv[1]) / "smali/com/android/inputmethod/latin/DictionaryEditor.smali"
text = TARGET.read_text(encoding="utf-8")

old_loop = """    if-eqz v1, :cond_0

    .line 266
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->db:Landroid/database/sqlite/SQLiteDatabase;
"""
new_loop = """    if-eqz v1, :cond_0

    # Build the reversed display list once after loading all rows (not once per row).
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V

    .line 266
    invoke-interface {v0}, Landroid/database/Cursor;->close()V

    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->db:Landroid/database/sqlite/SQLiteDatabase;
"""
old_call = """    .line 264
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V
    :try_end_0
"""
new_call = """    .line 264
    :try_end_0
"""
if text.count(old_loop) != 1:
    raise SystemExit(f"Expected exactly one dbload loop-exit anchor; found {text.count(old_loop)}")
if text.count(old_call) != 1:
    raise SystemExit(f"Expected exactly one per-row listfp call; found {text.count(old_call)}")
text = text.replace(old_loop, new_loop, 1).replace(old_call, new_call, 1)
TARGET.write_text(text, encoding="utf-8")
print("Patched dbload: listfp now runs once; cursor closes on successful load.")
