#!/usr/bin/env python3
"""Apply guarded, reproducible startup and clipboard Smali optimizations."""
from pathlib import Path
import sys

TARGET = Path(sys.argv[1]) / "smali/com/android/inputmethod/latin/DictionaryEditor.smali"
text = TARGET.read_text(encoding="utf-8")

startup = """    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbcreate()V

    .line 215
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbload()V
    const-string v2, "SK"
"""
startup_replacement = """    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbcreate()V

    # Clipboard history is loaded on demand when its panel is opened.
    const-string v2, "SK"
"""
if text.count(startup) != 1:
    raise SystemExit(f"Expected one constructor startup dbload call; found {text.count(startup)}")
text = text.replace(startup, startup_replacement, 1)

panel = """.method po1()V
    .locals 8

    .prologue
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->getLayoutInflater()Landroid/view/LayoutInflater;
"""
panel_replacement = """.method po1()V
    .locals 8

    .prologue
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbload()V

    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->getLayoutInflater()Landroid/view/LayoutInflater;
"""
if text.count(panel) != 1:
    raise SystemExit(f"Expected one clipboard panel method anchor; found {text.count(panel)}")
text = text.replace(panel, panel_replacement, 1)

old_loop = """    if-eqz v1, :cond_0

    .line 266
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->db:Landroid/database/sqlite/SQLiteDatabase;
"""
new_loop = """    if-eqz v1, :cond_0

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
if text.count(old_loop) != 1 or text.count(old_call) != 1:
    raise SystemExit("dbload Smali anchors did not match exactly; refusing to patch")
text = text.replace(old_loop, new_loop, 1).replace(old_call, new_call, 1)

dbload = """.method dbload()V
    .locals 4

    .prologue
    .line 256
"""
dbload_replacement = """.method dbload()V
    .locals 4

    .prologue
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->list:Ljava/util/List;
    invoke-interface {v1}, Ljava/util/List;->clear()V

    .line 256
"""
if text.count(dbload) != 1:
    raise SystemExit("dbload method anchor did not match exactly; refusing to patch")
text = text.replace(dbload, dbload_replacement, 1)

TARGET.write_text(text, encoding="utf-8")
print("Patched startup: clipboard DB read deferred until clipboard panel opens; list rebuilt once.")
