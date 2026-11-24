package com.android.inputmethod.latin;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Clipboard UI and folder storage for the legacy keyboard.
 * The original notes table is kept and upgraded in place.
 */
public final class ClipboardOrganizer {
    private static final String DB_NAME = "Clipboard1.db";
    private static final int ROOT = 0;
    private static final int COLOR_BG = Color.rgb(22, 25, 31);
    private static final int COLOR_PANEL = Color.rgb(35, 40, 48);
    private static final int COLOR_ACCENT = Color.rgb(0, 150, 136);
    private static final int COLOR_TEXT = Color.WHITE;
    private static final int COLOR_MUTED = Color.rgb(190, 198, 207);

    private final DictionaryEditor owner;
    private AlertDialog dialog;
    private int currentFolderId = ROOT;
    private final ArrayList<Integer> parents = new ArrayList<Integer>();
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();
    private boolean selecting;
    private String searchQuery;

    private LinearLayout rootView;
    private LinearLayout toolbar;
    private LinearLayout actionBar;
    private TextView titleView;
    private ListView listView;
    private RowAdapter adapter;

    private ClipboardOrganizer(DictionaryEditor owner) {
        this.owner = owner;
    }

    public static void show(DictionaryEditor owner) {
        if (owner == null) return;
        ClipboardOrganizer screen = new ClipboardOrganizer(owner);
        if (screen.ensureSchema()) screen.showList();
    }

    /** Move HOME/END to actual document boundaries, with Shift-style selection. */
    public static boolean moveToBoundary(DictionaryEditor owner, int keyCode) {
        if (owner == null || (keyCode != 122 && keyCode != 123)) return false;
        try {
            InputConnection connection = owner.getCurrentInputConnection();
            if (connection == null) return false;
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 1000000;
            request.hintMaxLines = 100000;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null) return false;

            int start = Math.max(0, extracted.startOffset);
            int end = start + extracted.text.length();
            int target = keyCode == 122 ? start : end;
            int selectionStart = Math.max(0, extracted.selectionStart);
            boolean extend = owner.isSelectionModeActive();
            if (extend) {
                connection.setSelection(selectionStart, target);
            } else {
                connection.setSelection(target, target);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean ensureSchema() {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS notes (note VARCHAR)");
            db.execSQL("CREATE TABLE IF NOT EXISTS folders (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL," +
                    "parent_id INTEGER NOT NULL DEFAULT 0," +
                    "sort_order INTEGER NOT NULL DEFAULT 0)");
            try {
                db.execSQL("ALTER TABLE notes ADD COLUMN folder_id INTEGER NOT NULL DEFAULT 0");
            } catch (Exception alreadyMigrated) {
                // Existing installations already have this column.
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_notes_folder ON notes(folder_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_folders_parent ON folders(parent_id)");
            return true;
        } catch (Exception error) {
            toast("تعذر تهيئة الحافظة: " + error.getMessage());
            return false;
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void showList() {
        if (!ensureSchema()) return;
        loadRows();

        rootView = new LinearLayout(owner);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(10), dp(8), dp(10), dp(8));
        rootView.setBackgroundColor(COLOR_BG);

        toolbar = new LinearLayout(owner);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(owner);
        titleView.setText(searchQuery == null ? currentFolderTitle() : "نتائج البحث");
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(17);
        titleView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(46), 1));

        if (parents.size() > 0 && searchQuery == null) {
            addButton(toolbar, "رجوع", new Runnable() {
                public void run() {
                    if (parents.size() > 0) currentFolderId = parents.remove(parents.size() - 1);
                    selected.clear();
                    selecting = false;
                    showList();
                }
            });
        }
        if (searchQuery != null) {
            addButton(toolbar, "إلغاء البحث", new Runnable() {
                public void run() {
                    searchQuery = null;
                    selected.clear();
                    selecting = false;
                    showList();
                }
            });
        } else {
            addButton(toolbar, "بحث", new Runnable() {
                public void run() { openSearchDialog(); }
            });
            addButton(toolbar, "مجلد جديد", new Runnable() {
                public void run() { createFolderDialog(); }
            });
            addButton(toolbar, selecting ? "إلغاء التحديد" : "تحديد", new Runnable() {
                public void run() {
                    selecting = !selecting;
                    selected.clear();
                    showList();
                }
            });
        }
        rootView.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (selecting) {
            actionBar = new LinearLayout(owner);
            actionBar.setOrientation(LinearLayout.HORIZONTAL);
            addButton(actionBar, "نقل", new Runnable() { public void run() { moveSelectedDialog(); } });
            addButton(actionBar, "حذف", new Runnable() { public void run() { deleteSelected(); } });
            addButton(actionBar, "تحديد الكل", new Runnable() {
                public void run() {
                    for (Row row : rows) selected.add(row.key());
                    showList();
                }
            });
            addButton(actionBar, "إلغاء", new Runnable() {
                public void run() { selected.clear(); selecting = false; showList(); }
            });
            rootView.addView(actionBar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        listView = new ListView(owner);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setBackgroundColor(COLOR_PANEL);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= rows.size()) return;
                Row row = rows.get(position);
                if (selecting) {
                    toggleSelection(row);
                    adapter.notifyDataSetChanged();
                    titleView.setText("تم تحديد " + selected.size());
                    return;
                }
                if (row.folder) {
                    parents.add(currentFolderId);
                    currentFolderId = (int) row.id;
                    searchQuery = null;
                    showList();
                } else {
                    paste(row.text);
                }
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= rows.size()) return true;
                Row row = rows.get(position);
                if (selecting) {
                    toggleSelection(row);
                    adapter.notifyDataSetChanged();
                } else {
                    showRowMenu(row);
                }
                return true;
            }
        });
        rootView.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView footer = new TextView(owner);
        footer.setText(searchQuery == null
                ? "اضغط على النص للصقه، واضغط مطولاً لإدارة العنصر"
                : "نتائج البحث في جميع مجلدات الحافظة");
        footer.setTextColor(COLOR_MUTED);
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        rootView.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setTitle("الحافظة");
        dialog = builder.create();
        dialog.setView(rootView);
        attachWindow(dialog);
        dialog.show();
        attachWindow(dialog);
    }

    private void loadRows() {
        rows.clear();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            if (searchQuery == null) {
                cursor = db.rawQuery("SELECT _id,name FROM folders WHERE parent_id=? ORDER BY name COLLATE NOCASE",
                        new String[] { String.valueOf(currentFolderId) });
                while (cursor.moveToNext()) {
                    rows.add(Row.folder(cursor.getLong(0), cursor.getString(1)));
                }
                cursor.close();
                cursor = db.rawQuery("SELECT rowid,note FROM notes WHERE IFNULL(folder_id,0)=? ORDER BY rowid DESC",
                        new String[] { String.valueOf(currentFolderId) });
            } else {
                cursor = db.rawQuery("SELECT rowid,note,IFNULL(folder_id,0) FROM notes " +
                        "WHERE LOWER(note) LIKE ? ORDER BY rowid DESC",
                        new String[] { "%" + searchQuery.toLowerCase() + "%" });
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String text = cursor.getString(1);
                int folderId = searchQuery == null ? currentFolderId : cursor.getInt(2);
                String label = text == null ? "" : text;
                rows.add(Row.note(id, text == null ? "" : text, label));
            }
        } catch (Exception error) {
            toast("تعذر تحميل الحافظة");
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
    }

    private void openSearchDialog() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Exception ignored) {}
            dialog = null;
        }
        final EditText query = new EditText(owner);
        query.setSingleLine(true);
        query.setText(searchQuery == null ? "" : searchQuery);
        query.setHint("اكتب كلمة أو عبارة للبحث");
        query.setTextColor(COLOR_TEXT);
        query.setHintTextColor(COLOR_MUTED);
        query.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setPadding(dp(12), dp(8), dp(12), dp(8));
        query.setBackground(roundRect(COLOR_PANEL, dp(8)));
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setTitle("البحث في الحافظة");
        builder.setView(query);
        builder.setPositiveButton("بحث", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                searchQuery = query.getText().toString().trim();
                if (searchQuery.length() == 0) searchQuery = null;
                selected.clear();
                selecting = false;
                showList();
            }
        });
        builder.setNegativeButton("إلغاء", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { showList(); }
        });
        AlertDialog search = builder.create();
        attachWindow(search);
        search.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        search.show();
        attachWindow(search);
        query.requestFocus();
        query.post(new Runnable() {
            public void run() {
                try {
                    InputMethodManager imm = (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
                } catch (Exception ignored) {}
            }
        });
    }

    private void createFolderDialog() {
        final EditText name = makeEditText("اسم المجلد");
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setTitle("إنشاء مجلد داخل " + currentFolderTitle());
        builder.setView(name);
        builder.setPositiveButton("إنشاء", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String value = name.getText().toString().trim();
                if (value.length() == 0) { toast("اكتب اسم المجلد"); return; }
                if (createFolder(value, currentFolderId)) {
                    toast("تم إنشاء المجلد");
                    showList();
                }
            }
        });
        builder.setNegativeButton("إلغاء", null);
        AlertDialog prompt = builder.create();
        attachWindow(prompt);
        prompt.show();
    }

    private void renameFolderDialog(final Row row) {
        final EditText name = makeEditText("اسم المجلد");
        name.setText(row.text);
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setTitle("إعادة تسمية المجلد");
        builder.setView(name);
        builder.setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String value = name.getText().toString().trim();
                if (value.length() == 0) { toast("اسم المجلد لا يمكن أن يكون فارغاً"); return; }
                SQLiteDatabase db = null;
                try {
                    db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
                    ContentValues values = new ContentValues();
                    values.put("name", value);
                    db.update("folders", values, "_id=?", new String[] { String.valueOf(row.id) });
                    showList();
                } catch (Exception error) { toast("تعذرت إعادة التسمية"); }
                finally { if (db != null) try { db.close(); } catch (Exception ignored) {} }
            }
        });
        builder.setNegativeButton("إلغاء", null);
        AlertDialog prompt = builder.create();
        attachWindow(prompt);
        prompt.show();
    }

    private void showRowMenu(final Row row) {
        final String[] actions = row.folder
                ? new String[] { "فتح المجلد", "إعادة تسمية", "نقل المجلد", "حذف المجلد" }
                : new String[] { "نقل النص إلى مجلد", "حذف النص" };
        AlertDialog.Builder menu = new AlertDialog.Builder(owner).setTitle(row.folder ? row.text : "إدارة النص");
        menu.setItems(actions, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (row.folder) {
                            if (which == 0) {
                                parents.add(currentFolderId);
                                currentFolderId = (int) row.id;
                                showList();
                            } else if (which == 1) {
                                renameFolderDialog(row);
                            } else if (which == 2) {
                                chooseFolder(row, false);
                            } else if (which == 3) {
                                confirmDeleteFolder(row);
                            }
                        } else {
                            if (which == 0) chooseFolder(row, true);
                            else if (which == 1) deleteNote(row.id);
                        }
                    }
                });
        showPrompt(menu);
    }

    private void confirmDeleteFolder(final Row row) {
        AlertDialog.Builder builder = new AlertDialog.Builder(owner).setTitle("حذف المجلد")
                .setMessage("سيتم نقل النصوص والمجلدات الفرعية إلى المجلد الأب، ولن تُحذف محتوياتها.");
        builder.setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        deleteFolder(row.id);
                        showList();
                    }
                }).setNegativeButton("إلغاء", null);
        showPrompt(builder);
    }

    private void moveSelectedDialog() {
        if (selected.size() == 0) { toast("حدد نصوصاً أو مجلدات أولاً"); return; }
        chooseFolder(null, true);
    }

    private void chooseFolder(final Row singleRow, final boolean moveNotes) {
        final ArrayList<FolderOption> options = new ArrayList<FolderOption>();
        options.add(new FolderOption(ROOT, "الرئيسية"));
        final ArrayList<Integer> folderIds = new ArrayList<Integer>();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            cursor = db.rawQuery("SELECT _id FROM folders ORDER BY name COLLATE NOCASE", null);
            while (cursor.moveToNext()) folderIds.add(cursor.getInt(0));
        } catch (Exception error) {
            toast("تعذر تحميل المجلدات");
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
        for (Integer folderId : folderIds) {
            int id = folderId.intValue();
            if (singleRow != null && singleRow.folder &&
                    (id == singleRow.id || isDescendant(id, singleRow.id))) continue;
            options.add(new FolderOption(id, getFolderPath(id)));
        }
        Collections.sort(options, new Comparator<FolderOption>() {
            public int compare(FolderOption a, FolderOption b) {
                if (a.id == ROOT) return -1;
                if (b.id == ROOT) return 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        final CharSequence[] labels = new CharSequence[options.size()];
        for (int i = 0; i < options.size(); i++) labels[i] = options.get(i).label;
        AlertDialog.Builder builder = new AlertDialog.Builder(owner).setTitle("اختر المجلد الهدف");
        builder.setItems(labels, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        int target = options.get(which).id;
                        if (singleRow != null) {
                            if (singleRow.folder) {
                                if (moveFolder(singleRow.id, target)) toast("تم نقل المجلد");
                            } else {
                                moveNote(singleRow.id, target);
                                toast("تم نقل النص");
                            }
                        } else {
                            moveSelectedTo(target);
                        }
                        showList();
                    }
                }).setNegativeButton("إلغاء", null);
        showPrompt(builder);
    }

    private void moveSelectedTo(int target) {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            for (String key : new ArrayList<String>(selected)) {
                long id = parseId(key);
                if (key.startsWith("N:")) {
                    int sourceFolder = getNoteFolderId(db, id);
                    if (hasSelectedFolderAncestor(db, sourceFolder, true)) continue;
                    ContentValues values = new ContentValues();
                    values.put("folder_id", target);
                    db.update("notes", values, "rowid=?", new String[] { String.valueOf(id) });
                } else if (key.startsWith("F:")) {
                    if (id == target || isDescendant(db, target, id)) {
                        toast("لا يمكن نقل مجلد إلى داخله أو إلى أحد مجلداته الفرعية");
                        continue;
                    }
                    int oldParent = getParentId(db, id);
                    if (hasSelectedFolderAncestor(db, oldParent, true)) continue;
                    ContentValues values = new ContentValues();
                    values.put("parent_id", target);
                    db.update("folders", values, "_id=?", new String[] { String.valueOf(id) });
                }
            }
            selected.clear();
            selecting = false;
            toast("اكتمل نقل العناصر المحددة");
        } catch (Exception error) {
            toast("تعذر نقل بعض العناصر");
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
    }

    private void deleteSelected() {
        if (selected.size() == 0) { toast("لا توجد عناصر محددة"); return; }
        AlertDialog.Builder builder = new AlertDialog.Builder(owner).setTitle("حذف العناصر المحددة")
                .setMessage("هل تريد حذف النصوص والمجلدات المحددة؟ ستُنقل محتويات المجلدات إلى المجلد الأب.");
        builder.setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        SQLiteDatabase db = null;
                        try {
                            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
                            for (String key : new ArrayList<String>(selected)) {
                                long id = parseId(key);
                                if (key.startsWith("N:")) {
                                    db.delete("notes", "rowid=?", new String[] { String.valueOf(id) });
                                } else if (key.startsWith("F:")) {
                                    rehomeAndDeleteFolder(db, id);
                                }
                            }
                            selected.clear();
                            selecting = false;
                            showList();
                        } catch (Exception error) {
                            toast("تعذر حذف بعض العناصر");
                        } finally {
                            if (db != null) try { db.close(); } catch (Exception ignored) {}
                        }
                    }
                }).setNegativeButton("إلغاء", null);
        showPrompt(builder);
    }

    private void toggleSelection(Row row) {
        String key = row.key();
        if (selected.contains(key)) selected.remove(key);
        else selected.add(key);
    }

    private void paste(String text) {
        try {
            InputConnection connection = owner.getCurrentInputConnection();
            if (connection == null) { toast("لا يوجد حقل نصي نشط"); return; }
            connection.commitText(text, 0);
            if (dialog != null) dialog.dismiss();
        } catch (Exception error) {
            toast("تعذر لصق النص");
        }
    }

    private boolean createFolder(String name, int parentId) {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            Cursor c = db.rawQuery("SELECT _id FROM folders WHERE parent_id=? AND name=?",
                    new String[] { String.valueOf(parentId), name });
            boolean exists = c.moveToFirst();
            c.close();
            if (exists) { toast("يوجد مجلد بهذا الاسم هنا"); return false; }
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("parent_id", parentId);
            values.put("sort_order", 0);
            return db.insert("folders", null, values) != -1;
        } catch (Exception error) {
            toast("تعذر إنشاء المجلد");
            return false;
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
    }

    private void deleteNote(long id) {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            db.delete("notes", "rowid=?", new String[] { String.valueOf(id) });
            showList();
        } catch (Exception error) { toast("تعذر حذف النص"); }
        finally { if (db != null) try { db.close(); } catch (Exception ignored) {} }
    }

    private void moveNote(long id, int folderId) {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            ContentValues values = new ContentValues();
            values.put("folder_id", folderId);
            db.update("notes", values, "rowid=?", new String[] { String.valueOf(id) });
        } catch (Exception error) { toast("تعذر نقل النص"); }
        finally { if (db != null) try { db.close(); } catch (Exception ignored) {} }
    }

    private boolean moveFolder(long id, int parentId) {
        if (id == parentId || isDescendant(parentId, id)) {
            toast("لا يمكن نقل المجلد إلى نفسه أو إلى أحد مجلداته الفرعية");
            return false;
        }
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            ContentValues values = new ContentValues();
            values.put("parent_id", parentId);
            return db.update("folders", values, "_id=?", new String[] { String.valueOf(id) }) > 0;
        } catch (Exception error) {
            toast("تعذر نقل المجلد");
            return false;
        } finally { if (db != null) try { db.close(); } catch (Exception ignored) {} }
    }

    private int getNoteFolderId(SQLiteDatabase db, long noteId) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT IFNULL(folder_id,0) FROM notes WHERE rowid=?",
                    new String[] { String.valueOf(noteId) });
            return c.moveToFirst() ? c.getInt(0) : ROOT;
        } catch (Exception ignored) {
            return ROOT;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    /** True when this folder or one of its parents is included in the current selection. */
    private boolean hasSelectedFolderAncestor(SQLiteDatabase db, long folderId, boolean includeSelf) {
        long current = folderId;
        Cursor c = null;
        try {
            for (int depth = 0; depth < 1000 && current != ROOT; depth++) {
                if (includeSelf && selected.contains("F:" + current)) return true;
                c = db.rawQuery("SELECT parent_id FROM folders WHERE _id=?",
                        new String[] { String.valueOf(current) });
                if (!c.moveToFirst()) return false;
                current = c.getLong(0);
                c.close();
                c = null;
                includeSelf = true;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return false;
    }

    private int getParentId(SQLiteDatabase db, long id) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT parent_id FROM folders WHERE _id=?",
                    new String[] { String.valueOf(id) });
            return c.moveToFirst() ? c.getInt(0) : ROOT;
        } catch (Exception ignored) {
            return ROOT;
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
    }

    private boolean isDescendant(SQLiteDatabase db, long candidate, long ancestor) {
        if (candidate == ROOT) return false;
        Cursor c = null;
        try {
            long current = candidate;
            for (int depth = 0; depth < 1000 && current != ROOT; depth++) {
                if (current == ancestor) return true;
                c = db.rawQuery("SELECT parent_id FROM folders WHERE _id=?",
                        new String[] { String.valueOf(current) });
                if (!c.moveToFirst()) return false;
                current = c.getLong(0);
                c.close();
                c = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean isDescendant(long candidate, long ancestor) {
        if (candidate == ROOT) return false;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            long current = candidate;
            for (int depth = 0; depth < 1000 && current != ROOT; depth++) {
                if (current == ancestor) return true;
                c = db.rawQuery("SELECT parent_id FROM folders WHERE _id=?",
                        new String[] { String.valueOf(current) });
                if (!c.moveToFirst()) return false;
                current = c.getLong(0);
                c.close();
                c = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
        return false;
    }

    private void deleteFolder(long id) {
        SQLiteDatabase db = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            rehomeAndDeleteFolder(db, id);
        } catch (Exception error) { toast("تعذر حذف المجلد"); }
        finally { if (db != null) try { db.close(); } catch (Exception ignored) {} }
    }

    private void rehomeAndDeleteFolder(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT parent_id FROM folders WHERE _id=?",
                new String[] { String.valueOf(id) });
        int parent = ROOT;
        if (c.moveToFirst()) parent = c.getInt(0);
        c.close();
        ContentValues values = new ContentValues();
        values.put("folder_id", parent);
        db.update("notes", values, "folder_id=?", new String[] { String.valueOf(id) });
        values = new ContentValues();
        values.put("parent_id", parent);
        db.update("folders", values, "parent_id=?", new String[] { String.valueOf(id) });
        db.delete("folders", "_id=?", new String[] { String.valueOf(id) });
    }

    private String getFolderPath(int id) {
        if (id == ROOT) return "الرئيسية";
        ArrayList<String> names = new ArrayList<String>();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = owner.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
            int current = id;
            for (int depth = 0; depth < 100 && current != ROOT; depth++) {
                c = db.rawQuery("SELECT name,parent_id FROM folders WHERE _id=?",
                        new String[] { String.valueOf(current) });
                if (!c.moveToFirst()) { c.close(); c = null; break; }
                names.add(c.getString(0));
                current = c.getInt(1);
                c.close();
                c = null;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Exception ignored) {}
            if (db != null) try { db.close(); } catch (Exception ignored) {}
        }
        StringBuilder path = new StringBuilder("الرئيسية");
        for (int i = names.size() - 1; i >= 0; i--) path.append(" / ").append(names.get(i));
        return path.toString();
    }

    private String currentFolderTitle() {
        if (currentFolderId == ROOT) return "الحافظة";
        return getFolderPath(currentFolderId);
    }

    private EditText makeEditText(String hint) {
        EditText edit = new EditText(owner);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setTextColor(COLOR_TEXT);
        edit.setHintTextColor(COLOR_MUTED);
        edit.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        edit.setPadding(dp(12), dp(8), dp(12), dp(8));
        edit.setBackground(roundRect(COLOR_PANEL, dp(8)));
        return edit;
    }

    private void addButton(LinearLayout parent, String label, final Runnable action) {
        Button button = new Button(owner);
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(11);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        button.setBackground(roundRect(COLOR_ACCENT, dp(7)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(42), 1);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(button, lp);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { action.run(); }
        });
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private AlertDialog showPrompt(AlertDialog.Builder builder) {
        AlertDialog prompt = builder.create();
        attachWindow(prompt);
        prompt.show();
        attachWindow(prompt);
        return prompt;
    }

    private void attachWindow(AlertDialog target) {
        try {
            Window window = target.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
            Dialog serviceDialog = owner.getWindow();
            if (serviceDialog != null && serviceDialog.getWindow() != null) {
                View decor = serviceDialog.getWindow().getDecorView();
                if (decor != null && decor.getWindowToken() != null) lp.token = decor.getWindowToken();
            }
            window.setAttributes(lp);
        } catch (Exception ignored) {}
    }

    private void toast(String message) {
        try { Toast.makeText(owner, message, Toast.LENGTH_SHORT).show(); }
        catch (Exception ignored) {}
    }

    private int dp(float value) {
        return (int) (value * owner.getResources().getDisplayMetrics().density + 0.5f);
    }

    private long parseId(String key) {
        try { return Long.parseLong(key.substring(2)); }
        catch (Exception ignored) { return -1; }
    }

    private final class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return rows.get(position).id; }
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView text = convertView instanceof TextView ? (TextView) convertView : new TextView(owner);
            Row row = rows.get(position);
            String prefix = row.folder ? "📁  " : "  ";
            if (selecting) prefix = selected.contains(row.key()) ? "☑  " + prefix : "□  " + prefix;
            text.setText(prefix + (searchQuery == null ? row.text : row.displayText));
            text.setTextColor(row.folder ? Color.rgb(128, 220, 205) : COLOR_TEXT);
            text.setTextSize(15);
            text.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            text.setPadding(dp(12), dp(8), dp(12), dp(8));
            text.setMinHeight(dp(54));
            text.setBackground(roundRect(selected.contains(row.key()) ? Color.rgb(44, 82, 78) : COLOR_PANEL, dp(6)));
            return text;
        }
    }

    private static final class Row {
        final boolean folder;
        final long id;
        final String text;
        final String displayText;
        private Row(boolean folder, long id, String text, String displayText) {
            this.folder = folder; this.id = id; this.text = text; this.displayText = displayText;
        }
        static Row folder(long id, String name) { return new Row(true, id, name, name); }
        static Row note(long id, String note, String display) { return new Row(false, id, note, display); }
        String key() { return (folder ? "F:" : "N:") + id; }
    }

    private static final class FolderOption {
        final int id;
        final String label;
        FolderOption(int id, String label) { this.id = id; this.label = label; }
    }
}
