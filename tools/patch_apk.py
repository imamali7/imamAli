#!/usr/bin/env python3
"""Apply guarded startup and clipboard-history optimizations to decoded Smali."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
target = root / "smali/com/android/inputmethod/latin/DictionaryEditor.smali"
source = target.read_text(encoding="utf-8")

# Defer expensive clipboard-history reads until the clipboard UI is requested.
eager = "    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbload()V"
if source.count(eager) != 1:
    raise SystemExit(f"Expected one eager dbload call; found {source.count(eager)}")
source = source.replace(eager, "    # Clipboard history is loaded only when its panel is opened.", 1)

clipboard_panel = """.method po1()V
    .locals 8

    .prologue
"""
if source.count(clipboard_panel) != 1:
    raise SystemExit("Clipboard panel method anchor mismatch")
source = source.replace(
    clipboard_panel,
    clipboard_panel + "    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->dbload()V\n\n",
    1,
)

# Rebuild the reversed display list once, not for every database row.
old_tail = """    if-eqz v1, :cond_0

    .line 266
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->db:Landroid/database/sqlite/SQLiteDatabase;
"""
new_tail = """    if-eqz v1, :cond_0

    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V

    invoke-interface {v0}, Landroid/database/Cursor;->close()V

    .line 266
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->db:Landroid/database/sqlite/SQLiteDatabase;
"""
old_inside = """    .line 264
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->listfp()V
    :try_end_0
"""
new_inside = """    .line 264
    :try_end_0
"""
if source.count(old_tail) != 1 or source.count(old_inside) != 1:
    raise SystemExit("dbload loop anchors mismatch; refusing partial patch")
source = source.replace(old_tail, new_tail, 1).replace(old_inside, new_inside, 1)

# Clear only the in-memory snapshot before reload; the SQLite history is untouched.
dbload_start = """.method dbload()V
    .locals 4

    .prologue
    .line 256
"""
if source.count(dbload_start) != 1:
    raise SystemExit("dbload method anchor mismatch")
source = source.replace(
    dbload_start,
    """.method dbload()V
    .locals 4

    .prologue
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->list:Ljava/util/List;
    invoke-interface {v1}, Ljava/util/List;->clear()V

    .line 256
""",
    1,
)

target.write_text(source, encoding="utf-8")
print("OK: deferred startup history read; list rebuild once; cursor closed on normal completion.")
