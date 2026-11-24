#!/usr/bin/env python3
"""Patch the clipboard entry point and cursor composition state safely."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
path = root / "smali/com/android/inputmethod/latin/DictionaryEditor.smali"
text = path.read_text(encoding="utf-8")

def replace_method(source, method_name, replacement):
    pattern = re.compile(r"(?ms)^\.method [^\n]*\b" + re.escape(method_name) +
                         r"\([^\n]*?\n.*?^\.end method")
    matches = list(pattern.finditer(source))
    if len(matches) != 1:
        raise SystemExit("Expected exactly one method " + method_name + "; found " + str(len(matches)))
    return pattern.sub(replacement.rstrip(), source, count=1)

text = replace_method(text, "po1", """
.method po1()V
    .locals 0
    invoke-static {p0}, Lcom/android/inputmethod/latin/ClipboardOrganizer;->show(Lcom/android/inputmethod/latin/DictionaryEditor;)V
    return-void
.end method
""")

text = replace_method(text, "handleArrow", """
.method private handleArrow(I)V
    .locals 3
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->getCurrentInputConnection()Landroid/view/inputmethod/InputConnection;
    move-result-object v0
    if-eqz v0, :clipboard_arrow_reset
    invoke-interface {v0}, Landroid/view/inputmethod/InputConnection;->finishComposingText()Z
    :clipboard_arrow_reset
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mComposing:Ljava/lang/StringBuilder;
    if-eqz v1, :clipboard_arrow_no_builder
    const/4 v2, 0x0
    invoke-virtual {v1, v2}, Ljava/lang/StringBuilder;->setLength(I)V
    :clipboard_arrow_no_builder
    const/4 v2, 0x0
    iput-boolean v2, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mPredicting:Z
    iput-boolean v2, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mJustAccepted:Z
    iput-boolean v2, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mJustAddedAutoSpace:Z
    const/4 v1, 0x0
    iput-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mBestWord:Ljava/lang/CharSequence;
    iget-object v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mWord:Lcom/android/inputmethod/latin/WordComposer;
    if-eqz v1, :clipboard_arrow_no_word
    invoke-virtual {v1}, Lcom/android/inputmethod/latin/WordComposer;->reset()V
    :clipboard_arrow_no_word
    invoke-static {}, Lcom/android/inputmethod/latin/TextEntryState;->reset()V
    invoke-direct {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->postUpdateSuggestions()V
    return-void
.end method
""")

# Use the extracted editable text to place HOME/END exactly at the document boundaries.
send_pattern = re.compile(r"(?ms)^\.method private sendEditorKey\(I\)V\n.*?^\.end method")
m = send_pattern.search(text)
if not m:
    raise SystemExit("sendEditorKey method not found")
send_method = m.group(0)
if "ClipboardOrganizer;->moveToBoundary" not in send_method:
    send_method = send_method.replace(
        "    .locals 12\n",
        """    .locals 12
    const/16 v0, 0x7a
    if-ne p1, v0, :clipboard_check_end
    goto :clipboard_try_boundary
    :clipboard_check_end
    const/16 v0, 0x7b
    if-ne p1, v0, :clipboard_continue_key
    :clipboard_try_boundary
    invoke-static {p0, p1}, Lcom/android/inputmethod/latin/ClipboardOrganizer;->moveToBoundary(Lcom/android/inputmethod/latin/DictionaryEditor;I)Z
    move-result v0
    if-eqz v0, :clipboard_continue_key
    return-void
    :clipboard_continue_key
""", 1)
    text = text[:m.start()] + send_method + text[m.end():]

# Re-find the method after the first rewrite because its byte offsets have changed.
m = send_pattern.search(text)
if not m:
    raise SystemExit("sendEditorKey method disappeared after boundary patch")
send_method = m.group(0)

# Shift-selection uses absolute document offsets instead of relying on editor-specific KeyEvent handling.
selection_anchor = """    if-eqz v0, :plain
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->getCurrentInputConnection()Landroid/view/inputmethod/InputConnection;
"""
selection_replacement = """    if-eqz v0, :plain
    invoke-static {p0, p1}, Lcom/android/inputmethod/latin/ClipboardOrganizer;->moveWithSelection(Lcom/android/inputmethod/latin/DictionaryEditor;I)Z
    move-result v0
    if-eqz v0, :clipboard_shift_fallback
    goto :done
    :clipboard_shift_fallback
    invoke-virtual {p0}, Lcom/android/inputmethod/latin/DictionaryEditor;->getCurrentInputConnection()Landroid/view/inputmethod/InputConnection;
"""
if "ClipboardOrganizer;->moveWithSelection" not in send_method:
    if selection_anchor not in send_method:
        raise SystemExit("sendEditorKey selection anchor not found")
    send_method = send_method.replace(selection_anchor, selection_replacement, 1)
    text = text[:m.start()] + send_method + text[m.end():]

# When selection mode is disabled, discard the previous anchor before plain cursor moves.
m = send_pattern.search(text)
if not m:
    raise SystemExit("sendEditorKey method not found while wiring selection reset")
send_method = m.group(0)
if "ClipboardOrganizer;->resetSelectionSession" not in send_method:
    if "    :plain\n" not in send_method:
        raise SystemExit("sendEditorKey plain branch not found for selection reset")
    send_method = send_method.replace(
        "    :plain\n",
        "    :plain\n    invoke-static {p0}, Lcom/android/inputmethod/latin/ClipboardOrganizer;->resetSelectionSession(Lcom/android/inputmethod/latin/DictionaryEditor;)V\n",
        1
    )
    text = text[:m.start()] + send_method + text[m.end():]

# HOME and END must also reset stale composing state after moving the cursor.
text = text.replace(
    """    :sswitch_12
    const/16 v0, 0x7a

    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->sendEditorKey(I)V

    goto/16 :goto_2

    :sswitch_13
    const/16 v0, 0x7b

    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->sendEditorKey(I)V

    goto/16 :goto_2
""",
    """    :sswitch_12
    const/16 v0, 0x7a
    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->sendEditorKey(I)V
    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->handleArrow(I)V
    goto/16 :goto_2

    :sswitch_13
    const/16 v0, 0x7b
    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->sendEditorKey(I)V
    invoke-direct {p0, v0}, Lcom/android/inputmethod/latin/DictionaryEditor;->handleArrow(I)V
    goto/16 :goto_2
""", 1)

# Selection updates must notice either endpoint moving, not just the anchor.
old = """    iget v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mLastSelectionStart:I

    if-eq v1, p3, :cond_b

    .line 1155
"""
new = """    iget v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mLastSelectionStart:I
    if-eq v1, p3, :clipboard_check_selection_end
    goto :clipboard_selection_changed
    :clipboard_check_selection_end
    iget v1, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mLastSelectionEnd:I
    if-eq v1, p4, :cond_b
    :clipboard_selection_changed

    .line 1155
"""
if old not in text:
    raise SystemExit("onUpdateSelection anchor block not found")
text = text.replace(old, new, 1)

if "ClipboardOrganizer;->moveWithSelection" not in text:
    raise SystemExit("Selection-mode cursor movement helper was not wired into sendEditorKey")

# Expose selection mode to the Java helper so HOME/END can extend an existing selection.
if "->isSelectionModeActive()Z" not in text:
    text = text.rstrip() + """

.method public isSelectionModeActive()Z
    .locals 1
    iget-boolean v0, p0, Lcom/android/inputmethod/latin/DictionaryEditor;->mSelectActive:Z
    return v0
.end method
"""

# Keep the clipboard listener alive from the moment the IME starts, not only after
# the user opens the clipboard panel.
start_input_pattern = re.compile(
    r"(?ms)^\.method public onStartInputView\(Landroid/view/inputmethod/EditorInfo;Z\)V\n.*?^\.end method"
)
start_input_match = start_input_pattern.search(text)
if not start_input_match:
    raise SystemExit("onStartInputView method not found")
start_input_method = start_input_match.group(0)
if "ClipboardOrganizer;->startClipboardMonitor" not in start_input_method:
    locals_anchor = "    .locals 11\n"
    if locals_anchor not in start_input_method:
        raise SystemExit("onStartInputView locals anchor changed")
    start_input_method = start_input_method.replace(
        locals_anchor,
        locals_anchor + "    invoke-static {p0}, Lcom/android/inputmethod/latin/ClipboardOrganizer;->startClipboardMonitor(Lcom/android/inputmethod/latin/DictionaryEditor;)V\n",
        1
    )
    text = text[:start_input_match.start()] + start_input_method + text[start_input_match.end():]

# Keep every Smali method terminator on its own line. Earlier method slicing can
# otherwise join the next instruction to ".end method" and make Apktool reject it.
text = re.sub(r"(?m)^\.end method(?=[^\r\n])", ".end method\n", text)

path.write_text(text, encoding="utf-8")
print("Patched clipboard UI entry point, cursor boundaries, arrow composition reset, and selection tracking.")
