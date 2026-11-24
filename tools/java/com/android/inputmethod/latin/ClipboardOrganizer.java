package com.android.inputmethod.latin;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputBinding;
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
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static ClipboardManager monitoredClipboard;
    private static ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private static WeakReference<DictionaryEditor> clipboardOwner = new WeakReference<DictionaryEditor>(null);
    private static final ExecutorService CLIPBOARD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static String lastObservedClipSignature;
    private static WeakReference<DictionaryEditor> selectionOwner = new WeakReference<DictionaryEditor>(null);
    private static int selectionAnchorAbsolute = -1;
    private static int selectionActiveAbsolute = -1;

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
        try {
            if (!screen.ensureSchema()) return;
            startClipboardMonitor(owner);
            screen.captureCurrentClipboard();
            screen.showList();
        } catch (RuntimeException error) {
            // A transient window/token or platform UI failure must not terminate the IME.
            screen.toast("تعذر فتح الحافظة. أغلقها وحاول مرة أخرى");
            try {
                if (screen.dialog != null && screen.dialog.isShowing()) screen.dialog.dismiss();
            } catch (Exception ignored) {}
        }
    }

    /** Extend selection from one stable anchor instead of Android's normalized range edge. */
    public static boolean moveWithSelection(DictionaryEditor owner, int keyCode) {
        if (owner == null || keyCode < 19 || keyCode > 22) return false;
        try {
            if (!owner.isSelectionModeActive()) { resetSelectionSession(owner); return false; }
            InputConnection connection = owner.getCurrentInputConnection();
            if (connection == null) return false;
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 1000000;
            request.hintMaxLines = 100000;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null) return false;
            String document = extracted.text.toString();
            int base = Math.max(0, extracted.startOffset);
            int length = document.length();
            int limit = base + length;
            DictionaryEditor previous = selectionOwner == null ? null : selectionOwner.get();
            if (previous != owner || selectionAnchorAbsolute < 0) {
                int caret = base + Math.max(0, Math.min(length, extracted.selectionEnd));
                selectionAnchorAbsolute = caret;
                selectionActiveAbsolute = caret;
                selectionOwner = new WeakReference<DictionaryEditor>(owner);
            }
            int active = Math.max(base, Math.min(limit, selectionActiveAbsolute));
            int index = active - base;
            int target = index;
            if (keyCode == 21) target = Math.max(0, index - 1);
            else if (keyCode == 22) target = Math.min(length, index + 1);
            else {
                int lineStart = index == 0 ? 0 : document.lastIndexOf("\n", index - 1) + 1;
                int column = index - lineStart;
                if (keyCode == 19) {
                    if (lineStart == 0) target = 0;
                    else {
                        int previousEnd = lineStart - 1;
                        int previousStart = document.lastIndexOf("\n", previousEnd - 1) + 1;
                        target = Math.min(previousStart + column, previousEnd);
                    }
                } else {
                    int nextStart = document.indexOf("\n", index);
                    if (nextStart < 0) target = length;
                    else {
                        nextStart++;
                        int nextEnd = document.indexOf("\n", nextStart);
                        if (nextEnd < 0) nextEnd = length;
                        target = Math.min(nextStart + column, nextEnd);
                    }
                }
            }
            int targetAbsolute = base + target;
            int anchor = Math.max(0, Math.min(limit, selectionAnchorAbsolute));
            connection.setSelection(anchor, targetAbsolute);
            selectionAnchorAbsolute = anchor;
            selectionActiveAbsolute = targetAbsolute;
            return true;
        } catch (Throwable ignored) { return false; }
    }

    public static void resetSelectionSession(DictionaryEditor owner) {
        DictionaryEditor active = selectionOwner == null ? null : selectionOwner.get();
        if (owner == null || active == null || active == owner) {
            selectionOwner = new WeakReference<DictionaryEditor>(null);
            selectionAnchorAbsolute = -1;
            selectionActiveAbsolute = -1;
        }
    }

    /** HOME/END shares the same anchor as repeated arrow-selection. */
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
            if (owner.isSelectionModeActive()) {
                DictionaryEditor previous = selectionOwner == null ? null : selectionOwner.get();
                if (previous != owner || selectionAnchorAbsolute < 0) {
                    selectionAnchorAbsolute = start + Math.max(0, extracted.selectionEnd);
                    selectionOwner = new WeakReference<DictionaryEditor>(owner);
                }
                connection.setSelection(Math.max(0, Math.min(end, selectionAnchorAbsolute)), target);
                selectionActiveAbsolute = target;
            } else {
                resetSelectionSession(owner);
                connection.setSelection(target, target);
            }
            return true;
        } catch (Throwable ignored) { return false; }
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
        // Rebuild the list in place instead of stacking a new clipboard window over
        // the previous one after folder, search, move, rename or delete operations.
        if (dialog != null) {
            try { if (dialog.isShowing()) dialog.dismiss(); } catch (Exception ignored) {}
            dialog = null;
        }
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
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
            int screenWidth = owner.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = owner.getResources().getDisplayMetrics().heightPixels;
            dialog.getWindow().setLayout((int) (screenWidth * 0.96f), (int) (screenHeight * 0.62f));
        }
        enableDragging(dialog, titleView);
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
                // instr() treats %, _ and backslashes literally instead of as LIKE wildcards.
                cursor = db.rawQuery("SELECT rowid,note,IFNULL(folder_id,0) FROM notes " +
                        "WHERE instr(LOWER(note), LOWER(?)) > 0 ORDER BY rowid DESC",
                        new String[] { searchQuery });
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
        if (Build.VERSION.SDK_INT >= 17) query.setTextDirection(View.TEXT_DIRECTION_RTL);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setPadding(dp(12), dp(8), dp(12), dp(8));
        query.setBackground(roundRect(COLOR_PANEL, dp(8)));
        LinearLayout searchContent = new LinearLayout(owner);
        searchContent.setOrientation(LinearLayout.VERTICAL);
        TextView searchHandle = makeDialogHeader("البحث في الحافظة");
        searchContent.addView(searchHandle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        searchContent.addView(query, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setView(searchContent);
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
        search.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { showList(); }
        });
        search.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        positionInputDialog(search);
        enableDragging(search, searchHandle);
        showKeyboard(query);
    }

    private void createFolderDialog() {
        dismissListDialog();
        final EditText name = makeEditText("اسم المجلد");
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView handle = makeDialogHeader("إنشاء مجلد داخل " + currentFolderTitle());
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        content.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setView(content);
        builder.setPositiveButton("إنشاء", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                String value = name.getText().toString().trim();
                if (value.length() == 0) {
                    toast("اكتب اسم المجلد");
                    showList();
                    return;
                }
                if (createFolder(value, currentFolderId)) toast("تم إنشاء المجلد");
                showList();
            }
        });
        builder.setNegativeButton("إلغاء", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { showList(); }
        });
        AlertDialog prompt = builder.create();
        attachWindow(prompt);
        prompt.show();
        attachWindow(prompt);
        prompt.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        positionInputDialog(prompt);
        enableDragging(prompt, handle);
        prompt.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { showList(); }
        });
        showKeyboard(name);
    }

    private void renameFolderDialog(final Row row) {
        dismissListDialog();
        final EditText name = makeEditText("اسم المجلد");
        name.setText(row.text);
        LinearLayout content = new LinearLayout(owner);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView handle = makeDialogHeader("إعادة تسمية المجلد");
        content.addView(handle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        content.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        AlertDialog.Builder builder = new AlertDialog.Builder(owner);
        builder.setView(content);
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
        builder.setNegativeButton("إلغاء", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { showList(); }
        });
        AlertDialog prompt = builder.create();
        attachWindow(prompt);
        prompt.show();
        attachWindow(prompt);
        prompt.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        positionInputDialog(prompt);
        enableDragging(prompt, handle);
        prompt.setOnCancelListener(new DialogInterface.OnCancelListener() {
            public void onCancel(DialogInterface d) { showList(); }
        });
        showKeyboard(name);
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
            for (int depth = 0; depth < 1000 && current != ROOT; depth++) {
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

    public static void startClipboardMonitor(final DictionaryEditor owner) {
        if (owner == null || Build.VERSION.SDK_INT < 11) return;
        ClipboardOrganizer screen = new ClipboardOrganizer(owner);
        if (!screen.ensureSchema()) return;
        try {
            ClipboardManager manager = (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            clipboardOwner = new WeakReference<DictionaryEditor>(owner);
            if (monitoredClipboard == manager && clipboardListener != null) { screen.captureCurrentClipboard(); return; }
            if (monitoredClipboard != null && clipboardListener != null) {
                try { monitoredClipboard.removePrimaryClipChangedListener(clipboardListener); } catch (Exception ignored) {}
            }
            monitoredClipboard = manager;
            clipboardListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                public void onPrimaryClipChanged() {
                    DictionaryEditor activeOwner = clipboardOwner == null ? null : clipboardOwner.get();
                    ClipboardManager activeManager = monitoredClipboard;
                    if (activeOwner != null && activeManager != null) new ClipboardOrganizer(activeOwner).savePrimaryClip(activeManager);
                }
            };
            manager.addPrimaryClipChangedListener(clipboardListener);
            screen.captureCurrentClipboard();
        } catch (Exception ignored) {}
    }

    private void captureCurrentClipboard() {
        if (Build.VERSION.SDK_INT < 11) return;
        try {
            ClipboardManager manager = (ClipboardManager) owner.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) savePrimaryClip(manager);
        } catch (Exception ignored) {}
    }

    private void savePrimaryClip(ClipboardManager manager) {
        if (manager == null || Build.VERSION.SDK_INT < 11) return;
        try {
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            final ArrayList<String> texts = new ArrayList<String>();
            for (int i = 0; i < clip.getItemCount(); i++) {
                try {
                    CharSequence value = clip.getItemAt(i).coerceToText(owner);
                    if (value != null && value.toString().trim().length() > 0 && !texts.contains(value.toString())) texts.add(value.toString());
                } catch (Exception ignoredItem) {}
            }
            if (texts.isEmpty()) return;
            final String signature = clipSignature(texts);
            synchronized (ClipboardOrganizer.class) {
                if (signature.equals(lastObservedClipSignature)) return;
                lastObservedClipSignature = signature;
            }
            final Context context = owner.getApplicationContext() != null ? owner.getApplicationContext() : owner;
            CLIPBOARD_EXECUTOR.execute(new Runnable() {
                public void run() {
                    SQLiteDatabase db = null;
                    boolean saved = false;
                    try {
                        db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null);
                        db.beginTransaction();
                        for (String text : texts) {
                            ContentValues values = new ContentValues();
                            values.put("note", text);
                            values.put("folder_id", ROOT);
                            if (db.insert("notes", null, values) == -1) throw new IllegalStateException("clipboard insert failed");
                        }
                        db.execSQL("DELETE FROM notes WHERE IFNULL(folder_id,0)=0 AND rowid NOT IN (SELECT rowid FROM notes WHERE IFNULL(folder_id,0)=0 ORDER BY rowid DESC LIMIT 500)");
                        db.setTransactionSuccessful();
                        saved = true;
                    } catch (Exception ignored) {
                    } finally {
                        if (db != null) {
                            try { if (db.inTransaction()) db.endTransaction(); } catch (Exception ignored) {}
                            try { db.close(); } catch (Exception ignored) {}
                        }
                        if (!saved) synchronized (ClipboardOrganizer.class) { if (signature.equals(lastObservedClipSignature)) lastObservedClipSignature = null; }
                    }
                }
            });
        } catch (Exception ignored) {}
    }

    private static String clipSignature(List<String> texts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String text : texts) {
                byte[] bytes = text.getBytes("UTF-8");
                digest.update((byte)(bytes.length >>> 24));
                digest.update((byte)(bytes.length >>> 16));
                digest.update((byte)(bytes.length >>> 8));
                digest.update((byte)bytes.length);
                digest.update(bytes);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            StringBuilder result = new StringBuilder();
            for (String text : texts) result.append(text.length()).append(':').append(text).append('|');
            return result.toString();
        }
    }

    private TextView makeDialogHeader(String title) {
        TextView header = new TextView(owner);
        header.setText(title);
        header.setTextColor(COLOR_TEXT);
        header.setTextSize(15);
        header.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(4));
        header.setBackground(roundRect(COLOR_ACCENT, dp(8)));
        header.setFocusable(false);
        return header;
    }

    private void showKeyboard(final EditText edit) {
        if (edit == null) return;
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) edit.setShowSoftInputOnFocus(true);
        edit.requestFocus();
        edit.post(new Runnable() {
            public void run() {
                try {
                    // Wait until the attached dialog has a window token and has completed
                    // its first layout; otherwise Android may route the request to the
                    // editor behind the panel and leave the IME hidden behind the dialog.
                    if (edit.getWindowToken() == null) return;
                    if (Build.VERSION.SDK_INT >= 21) {
                        owner.requestShowSelf(InputMethodManager.SHOW_IMPLICIT);
                    }
                    InputMethodManager imm = (InputMethodManager) owner.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.restartInput(edit);
                        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void dismissListDialog() {
        if (dialog != null) {
            try { if (dialog.isShowing()) dialog.dismiss(); } catch (Exception ignored) {}
            dialog = null;
        }
    }

    /** Position short input dialogs just above the active keyboard surface. */
    private void positionInputDialog(final AlertDialog target) {
        if (target == null || target.getWindow() == null) return;
        final Window window = target.getWindow();
        window.getDecorView().post(new Runnable() {
            public void run() {
                try {
                    WindowManager.LayoutParams params = window.getAttributes();
                    int screenHeight = owner.getResources().getDisplayMetrics().heightPixels;
                    int screenWidth = owner.getResources().getDisplayMetrics().widthPixels;
                    int imeHeight = 0;
                    Dialog imeDialog = owner.getWindow();
                    if (imeDialog != null && imeDialog.getWindow() != null) {
                        View decor = imeDialog.getWindow().getDecorView();
                        if (decor != null) imeHeight = decor.getHeight();
                    }
                    int dialogHeight = window.getDecorView().getHeight();
                    if (dialogHeight <= 0) dialogHeight = dp(112);
                    params.width = (int)(screenWidth * 0.96f);
                    params.height = WindowManager.LayoutParams.WRAP_CONTENT;
                    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    params.y = Math.max(dp(8), screenHeight - imeHeight - dialogHeight - dp(8));
                    window.setAttributes(params);
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                } catch (RuntimeException ignored) {}
            }
        });
    }

    private void enableDragging(final Dialog target, final View handle) {
        if (target == null || handle == null) return;
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean positioned;
            private Window window;
            public boolean onTouch(View view, MotionEvent event) {
                try {
                    if (window == null) window = target.getWindow();
                    if (window == null) return false;
                    WindowManager.LayoutParams params = window.getAttributes();
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        int width = owner.getResources().getDisplayMetrics().widthPixels;
                        int height = owner.getResources().getDisplayMetrics().heightPixels;
                        if (!positioned) {
                            params.gravity = Gravity.TOP | Gravity.LEFT;
                            int panelWidth = window.getDecorView().getWidth();
                            int panelHeight = window.getDecorView().getHeight();
                            params.x = Math.max(0, (width - panelWidth) / 2);
                            params.y = Math.max(dp(12), (height - panelHeight) / 3);
                            positioned = true;
                            window.setAttributes(params);
                        }
                        startX = params.x;
                        startY = params.y;
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        int width = owner.getResources().getDisplayMetrics().widthPixels;
                        int height = owner.getResources().getDisplayMetrics().heightPixels;
                        int panelWidth = window.getDecorView().getWidth();
                        int panelHeight = window.getDecorView().getHeight();
                        params.x = Math.max(0, Math.min(width - panelWidth,
                                startX + Math.round(event.getRawX() - downRawX)));
                        params.y = Math.max(0, Math.min(height - panelHeight,
                                startY + Math.round(event.getRawY() - downRawY)));
                        window.setAttributes(params);
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_UP ||
                            event.getAction() == MotionEvent.ACTION_CANCEL) return true;
                } catch (Exception ignored) {}
                return false;
            }
        });
    }

    private EditText makeEditText(String hint) {
        EditText edit = new EditText(owner);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setTextColor(COLOR_TEXT);
        edit.setHintTextColor(COLOR_MUTED);
        edit.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        if (Build.VERSION.SDK_INT >= 17) edit.setTextDirection(View.TEXT_DIRECTION_RTL);
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
        if (target == null) return;
        try {
            Window window = target.getWindow();
            if (window == null) return;

            WindowManager.LayoutParams lp = window.getAttributes();
            Dialog imeWindow = owner.getWindow();
            IBinder imeWindowToken = null;
            if (imeWindow != null && imeWindow.getWindow() != null) {
                View decor = imeWindow.getWindow().getDecorView();
                if (decor != null) imeWindowToken = decor.getWindowToken();
            }

            // InputBinding.getConnectionToken() identifies an input connection, not
            // an Android window. Using it as LayoutParams.token can make addView()
            // throw BadTokenException and crash the entire keyboard process.
            // Attach only to the actual IME window token, and never replace a valid
            // token with a connection token or a null value.
            if (imeWindowToken != null) {
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
                lp.token = imeWindowToken;
            }
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            window.setAttributes(lp);
        } catch (RuntimeException windowError) {
            // Keep Android's default Dialog token/type if the IME window is not ready.
            // The caller can still display the panel using the framework defaults.
        }
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
