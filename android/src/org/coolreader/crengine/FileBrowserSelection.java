/*
 * CoolReader for Android
 *
 * This program is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.coolreader.crengine;

import android.app.AlertDialog;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.coolreader.CoolReader;
import org.coolreader.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multiple selection mode for the file browser: select several books and
 * delete them, move them to another folder or set reading status at once.
 */
public class FileBrowserSelection {

	public static final Logger log = L.create("fbsel");

	private final CoolReader mActivity;
	private final FileBrowser mBrowser;
	private final LinkedHashMap<String, FileInfo> mSelected = new LinkedHashMap<>();
	private boolean mActive;
	private LinearLayout mBar;
	private LinearLayout mBottomBar;
	private TextView mCountView;
	private TextView mAllButton;
	private final ArrayList<TextView> mItemActions = new ArrayList<>();

	private static final String PREFS = "fork";
	private static final String PREF_LAST_MOVE_DIR = "lastMoveDir";

	public FileBrowserSelection(CoolReader activity, FileBrowser browser) {
		mActivity = activity;
		mBrowser = browser;
	}

	public static boolean isSelectable(FileInfo item) {
		if (item == null || item.isDirectory)
			return false;
		if (item.isOPDSBook() || item.isOPDSDir() || item.isOnlineCatalogPluginBook() || item.isOnlineCatalogPluginDir())
			return false;
		// entries of multi-file archives can't be handled separately
		if (item.parent != null && item.parent.isArchive)
			return false;
		return item.getBasePath() != null;
	}

	public boolean isActive() {
		return mActive;
	}

	public boolean isSelected(FileInfo item) {
		return item != null && mSelected.containsKey(item.getPathName());
	}

	public void start(FileInfo item) {
		mActive = true;
		mSelected.clear();
		if (isSelectable(item))
			mSelected.put(item.getPathName(), item);
		update();
	}

	public void finish() {
		mActive = false;
		mSelected.clear();
		update();
	}

	public void toggle(FileInfo item) {
		if (!isSelectable(item))
			return;
		String key = item.getPathName();
		if (mSelected.containsKey(key))
			mSelected.remove(key);
		else
			mSelected.put(key, item);
		update();
	}

	private void toggleAll() {
		FileInfo dir = mBrowser.getCurrentDir();
		if (dir == null)
			return;
		ArrayList<FileInfo> items = new ArrayList<>();
		for (int i = 0; i < dir.fileCount(); i++) {
			FileInfo f = dir.getFile(i);
			if (isSelectable(f))
				items.add(f);
		}
		boolean allSelected = true;
		for (FileInfo f : items) {
			if (!isSelected(f)) {
				allSelected = false;
				break;
			}
		}
		for (FileInfo f : items) {
			if (allSelected)
				mSelected.remove(f.getPathName());
			else
				mSelected.put(f.getPathName(), f);
		}
		update();
	}

	/**
	 * Add hidden check box at the left of a book list item.
	 */
	public android.widget.CheckBox addCheckBox(LinearLayout row) {
		android.widget.CheckBox cb = new android.widget.CheckBox(mActivity);
		// clicks go to the list item, the box only shows the state
		cb.setFocusable(false);
		cb.setFocusableInTouchMode(false);
		cb.setClickable(false);
		cb.setVisibility(View.GONE);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.gravity = Gravity.CENTER_VERTICAL;
		lp.rightMargin = dp(4);
		row.addView(cb, 0, lp);
		return cb;
	}

	/**
	 * Show check box state of the item in selection mode.
	 */
	private static final int SELECTED_ROW_COLOR = 0x332196F3;

	public void decorate(View row, android.widget.CheckBox check, View infoButton, FileInfo item) {
		if (row != null) {
			row.setAlpha(mActive && !isSelectable(item) ? 0.4f : 1.0f);
			if (mActive && isSelected(item))
				row.setBackgroundColor(SELECTED_ROW_COLOR);
			else
				row.setBackground(null);
		}
		if (infoButton != null)
			infoButton.setVisibility(mActive ? View.INVISIBLE : View.VISIBLE);
		if (check == null)
			return;
		if (mActive && isSelectable(item)) {
			check.setVisibility(View.VISIBLE);
			check.setChecked(isSelected(item));
		} else {
			check.setVisibility(View.GONE);
		}
	}

	private boolean allSelectedInCurrentDir() {
		FileInfo dir = mBrowser.getCurrentDir();
		if (dir == null)
			return false;
		int selectable = 0;
		for (int i = 0; i < dir.fileCount(); i++) {
			FileInfo f = dir.getFile(i);
			if (isSelectable(f)) {
				selectable++;
				if (!isSelected(f))
					return false;
			}
		}
		return selectable > 0;
	}

	private void update() {
		if (mBar != null) {
			mBar.setVisibility(mActive ? View.VISIBLE : View.GONE);
			mBottomBar.setVisibility(mActive ? View.VISIBLE : View.GONE);
			mCountView.setText(mActivity.getString(R.string.fb_sel_count, mSelected.size()));
			boolean any = !mSelected.isEmpty();
			for (TextView b : mItemActions) {
				b.setEnabled(any);
				b.setAlpha(any ? 1.0f : 0.4f);
			}
			mAllButton.setText(allSelectedInCurrentDir() ? R.string.fb_sel_none : R.string.fb_sel_all);
		}
		mBrowser.invalidateList();
	}

	//=====================================================================
	// Action bar
	//=====================================================================

	private int dp(int value) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics());
	}

	private TextView makeButton(String text, View.OnClickListener listener) {
		TextView b = new TextView(mActivity);
		b.setText(text);
		b.setTextColor(Color.WHITE);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		b.setGravity(Gravity.CENTER);
		b.setPadding(dp(10), dp(10), dp(10), dp(10));
		b.setClickable(true);
		b.setOnClickListener(listener);
		TypedValue tv = new TypedValue();
		if (mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0)
			b.setBackgroundResource(tv.resourceId);
		return b;
	}

	/**
	 * Top contextual bar: close, count, select all/none.
	 */
	public View getBar() {
		if (mBar == null)
			createBars();
		detach(mBar);
		return mBar;
	}

	/**
	 * Bottom action bar (within thumb reach on tall phones): move, delete, status.
	 */
	public View getBottomBar() {
		if (mBottomBar == null)
			createBars();
		detach(mBottomBar);
		return mBottomBar;
	}

	private static void detach(View v) {
		if (v.getParent() instanceof ViewGroup)
			((ViewGroup) v.getParent()).removeView(v);
	}

	private void createBars() {
		mBar = new LinearLayout(mActivity);
		mBar.setOrientation(LinearLayout.HORIZONTAL);
		mBar.setGravity(Gravity.CENTER_VERTICAL);
		mBar.setBackgroundColor(0xF0303030);
		mBar.setMinimumHeight(dp(56));
		mBar.setPadding(dp(4), 0, dp(4), 0);

		TextView close = makeButton("\u2715", v -> finish());
		close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
		close.setMinWidth(dp(48));
		close.setContentDescription(mActivity.getString(R.string.fb_sel_close));
		mBar.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
		mCountView = new TextView(mActivity);
		mCountView.setTextColor(Color.WHITE);
		mCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		mCountView.setSingleLine(true);
		mCountView.setEllipsize(android.text.TextUtils.TruncateAt.END);
		mCountView.setPadding(dp(8), 0, dp(8), 0);
		mBar.addView(mCountView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		mAllButton = makeButton(mActivity.getString(R.string.fb_sel_all), v -> toggleAll());
		mBar.addView(mAllButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
		mBar.setVisibility(mActive ? View.VISIBLE : View.GONE);

		mBottomBar = new LinearLayout(mActivity);
		mBottomBar.setOrientation(LinearLayout.HORIZONTAL);
		mBottomBar.setBackgroundColor(0xF0303030);
		mItemActions.clear();
		mItemActions.add(makeButton(mActivity.getString(R.string.fb_sel_move), v -> askMove()));
		TextView delete = makeButton(mActivity.getString(R.string.fb_sel_delete), v -> askDelete());
		delete.setTextColor(0xFFFF8A80); // destructive action
		mItemActions.add(delete);
		mItemActions.add(makeButton(mActivity.getString(R.string.fb_sel_status), v -> askStatus()));
		for (TextView b : mItemActions) {
			b.setMinHeight(dp(56));
			mBottomBar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		}
		mBottomBar.setVisibility(mActive ? View.VISIBLE : View.GONE);
	}

	private ArrayList<FileInfo> selectedItems() {
		return new ArrayList<>(mSelected.values());
	}

	private void refreshCurrentDir(Collection<FileInfo> changed) {
		FileInfo dir = mBrowser.getCurrentDir();
		if (dir == null)
			return;
		// keep list position: scroll back to the first visible item that is still there
		FileInfo anchor = mBrowser.getFirstVisibleItem(changed);
		if (dir.isSpecialDir() && !dir.isOPDSRoot() && !dir.isOPDSDir())
			mBrowser.showDirectory(dir, anchor); // reload virtual folder (recent, by state etc.) from DB
		else
			mActivity.directoryUpdated(dir, anchor);
	}

	//=====================================================================
	// Status
	//=====================================================================

	private void askStatus() {
		final ArrayList<FileInfo> items = selectedItems();
		if (items.isEmpty())
			return;
		final int[] states = new int[] {FileInfo.STATE_NEW, FileInfo.STATE_TO_READ, FileInfo.STATE_READING, FileInfo.STATE_FINISHED};
		String[] names = new String[] {
				mActivity.getString(R.string.book_state_none),
				mActivity.getString(R.string.book_state_toread),
				mActivity.getString(R.string.book_state_reading),
				mActivity.getString(R.string.book_state_finished),
		};
		new AlertDialog.Builder(mActivity)
				.setTitle(mActivity.getString(R.string.fb_sel_status_title, items.size()))
				.setItems(names, (dialog, which) -> setStatus(items, states[which]))
				.setNegativeButton(R.string.dlg_button_cancel, null)
				.show();
	}

	private void setStatus(final ArrayList<FileInfo> items, final int state) {
		mActivity.waitForCRDBService(() -> mActivity.getDB().setReadingState(items, state, () -> {
			for (FileInfo f : items) {
				f.setReadingState(state);
				FileInfo inTree = Services.getScanner().findFileInTree(f);
				if (inTree != null && inTree != f)
					inTree.setReadingState(state);
			}
			Services.getHistory().onReadingStateChanged(items, state);
			mActivity.showToast(mActivity.getString(R.string.fb_sel_status_done, items.size()));
			finish();
			refreshCurrentDir(items);
		}));
	}

	//=====================================================================
	// Delete
	//=====================================================================

	private void askDelete() {
		final ArrayList<FileInfo> items = selectedItems();
		if (items.isEmpty())
			return;
		StringBuilder names = new StringBuilder();
		int shown = 0;
		for (FileInfo item : items) {
			if (shown == 5) {
				names.append(mActivity.getString(R.string.fb_sel_and_more, items.size() - shown));
				break;
			}
			names.append("\u2022 ").append(displayName(item)).append('\n');
			shown++;
		}
		names.append('\n').append(mActivity.getString(R.string.fb_sel_delete_warning));
		new AlertDialog.Builder(mActivity)
				.setTitle(mActivity.getString(R.string.fb_sel_confirm_delete, items.size()))
				.setMessage(names.toString())
				.setPositiveButton(R.string.fb_sel_delete, (d, w) -> doDelete(items))
				.setNegativeButton(R.string.dlg_button_cancel, null)
				.show();
	}

	private String displayName(FileInfo item) {
		String title = item.title;
		if (title != null && !title.trim().isEmpty())
			return title.trim();
		return item.filename != null ? item.filename : item.getPathName();
	}

	private void doDelete(final ArrayList<FileInfo> items) {
		{
			int ok = 0;
			int failed = 0;
			final ArrayList<FileInfo> deleted = new ArrayList<>();
			for (FileInfo item : items) {
				mActivity.closeBookIfOpened(item);
				FileInfo file = Services.getScanner().findFileInTree(item);
				if (file == null)
					file = item;
				if (file.deleteFile()) {
					ok++;
					deleted.add(file);
				} else {
					log.w("Cannot delete " + file.getBasePath());
					failed++;
				}
			}
			mActivity.waitForCRDBService(() -> {
				for (FileInfo file : deleted)
					Services.getHistory().removeBookInfo(mActivity.getDB(), file, true, true);
			});
			mActivity.showToast(failed == 0 ? mActivity.getString(R.string.fb_sel_deleted, ok)
					: mActivity.getString(R.string.fb_sel_deleted_failed, ok, failed));
			finish();
			refreshCurrentDir(items);
		}
	}

	//=====================================================================
	// Move
	//=====================================================================

	private File startFolderForMove(Collection<FileInfo> items) {
		String last = mActivity.getSharedPreferences(PREFS, 0).getString(PREF_LAST_MOVE_DIR, null);
		if (last != null && new File(last).isDirectory())
			return new File(last);
		FileInfo dir = mBrowser.getCurrentDir();
		if (dir != null && !dir.isSpecialDir() && !dir.isArchive && dir.getPathName() != null) {
			File f = new File(dir.getPathName());
			if (f.isDirectory())
				return f;
		}
		for (FileInfo item : items) {
			File parent = new File(item.getBasePath()).getParentFile();
			if (parent != null && parent.isDirectory())
				return parent;
		}
		return android.os.Environment.getExternalStorageDirectory();
	}

	private void askMove() {
		final ArrayList<FileInfo> items = selectedItems();
		if (items.isEmpty())
			return;
		new FolderPicker(startFolderForMove(items), sourceFolders(items), destDir -> {
			mActivity.getSharedPreferences(PREFS, 0).edit().putString(PREF_LAST_MOVE_DIR, destDir.getAbsolutePath()).apply();
			moveTo(items, destDir);
		}).show();
	}

	/** Folders the selected files are in: moving there is a no-op. */
	private java.util.HashSet<String> sourceFolders(Collection<FileInfo> items) {
		java.util.HashSet<String> res = new java.util.HashSet<>();
		for (FileInfo item : items) {
			File parent = new File(item.getBasePath()).getParentFile();
			if (parent != null)
				res.add(parent.getAbsolutePath());
		}
		return res;
	}

	private static boolean copyFile(File src, File dst) {
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
		} catch (Exception e) {
			log.e("copy failed " + src + " -> " + dst, e);
			//noinspection ResultOfMethodCallIgnored
			dst.delete();
			return false;
		}
		if (dst.length() != src.length()) {
			//noinspection ResultOfMethodCallIgnored
			dst.delete();
			return false;
		}
		return src.delete();
	}

	private void moveTo(final ArrayList<FileInfo> items, final File destDir) {
		for (FileInfo item : items)
			mActivity.closeBookIfOpened(item);
		new Thread(() -> {
			// base path (file or archive) -> new base path
			final Map<String, String> movedBases = new HashMap<>();
			final LinkedHashMap<FileInfo, String> moved = new LinkedHashMap<>();
			int failed = 0;
			for (FileInfo item : items) {
				String base = item.getBasePath();
				String newBase = movedBases.get(base);
				if (newBase == null) {
					File src = new File(base);
					File dst = new File(destDir, src.getName());
					if (dst.getAbsolutePath().equals(src.getAbsolutePath())) {
						continue; // already there
					}
					if (!src.isFile() || dst.exists()) {
						log.w("Cannot move " + src + " -> " + dst);
						failed++;
						continue;
					}
					if (!src.renameTo(dst) && !copyFile(src, dst)) {
						log.w("Cannot move " + src + " -> " + dst);
						failed++;
						continue;
					}
					newBase = dst.getAbsolutePath();
					movedBases.put(base, newBase);
				}
				moved.put(item, newBase);
			}
			final int failedCount = failed;
			BackgroundThread.instance().postGUI(() -> onMoved(moved, destDir, failedCount));
		}, "move-books").start();
	}

	private void onMoved(final LinkedHashMap<FileInfo, String> moved, final File destDir, final int failed) {
		mActivity.waitForCRDBService(() -> {
			for (Map.Entry<FileInfo, String> e : moved.entrySet()) {
				FileInfo item = e.getKey();
				String newBase = e.getValue();
				String oldPathName = item.getPathName();
				// update DB record first: it takes a copy of the old item
				mActivity.getDB().moveBookRecord(item, newBase);
				FileInfo newFile = new FileInfo(item);
				if (newFile.arcname != null) {
					newFile.arcname = newBase;
				} else {
					File f = new File(newBase);
					newFile.pathname = f.getAbsolutePath();
					newFile.path = f.getParent();
					newFile.filename = f.getName();
				}
				Services.getHistory().onBookMoved(oldPathName, newFile);
				FileInfo inTree = Services.getScanner().findFileInTree(item);
				if (inTree != null && inTree.parent != null)
					inTree.parent.removeChild(inTree);
				// item may be shown in a virtual folder (recent, by state): keep it usable
				item.pathname = newFile.pathname;
				item.path = newFile.path;
				item.filename = newFile.filename;
				item.arcname = newFile.arcname;
			}
			// destination folder content changed: force rescan of book properties when opened
			FileInfo destNode = Services.getScanner().findFileInTree(new FileInfo(destDir));
			if (destNode != null)
				destNode.isScanned = false;
			mActivity.showToast(failed == 0 ? mActivity.getString(R.string.fb_sel_moved, moved.size())
					: mActivity.getString(R.string.fb_sel_moved_failed, moved.size(), failed));
			finish();
			refreshCurrentDir(moved.keySet());
		});
	}

	private interface FolderSelectedCallback {
		void onFolderSelected(File dir);
	}

	/**
	 * Simple folder chooser: navigate folders, create a new one, pick the current one.
	 */
	private static final File NEW_FOLDER = new File("/.cr3fork-new-folder-row");

	/** /sdcard/... and /storage/emulated/0/... are the same folder: compare canonical paths. */
	private static File canonical(File f) {
		try {
			return f.getCanonicalFile();
		} catch (Exception e) {
			return f.getAbsoluteFile();
		}
	}

	private class FolderPicker {
		private File mCurrent;
		private final File mRoot = android.os.Environment.getExternalStorageDirectory();
		private final java.util.Set<String> mSourceFolders;
		private final FolderSelectedCallback mCallback;
		private final ArrayList<File> mEntries = new ArrayList<>();
		private ArrayAdapter<String> mAdapter;
		private AlertDialog mDialog;

		FolderPicker(File start, java.util.Set<String> sourceFolders, FolderSelectedCallback callback) {
			mCurrent = canonical(start);
			mSourceFolders = new java.util.HashSet<>();
			for (String f : sourceFolders)
				mSourceFolders.add(canonical(new File(f)).getAbsolutePath());
			mCallback = callback;
		}

		/** Path for humans: "Phone/Books/Fantasy" instead of /storage/emulated/0/Books/Fantasy. */
		private String displayPath(File dir) {
			String root = canonical(mRoot).getAbsolutePath();
			String path = dir.getAbsolutePath();
			if (path.equals(root))
				return mActivity.getString(R.string.fb_sel_storage);
			if (path.startsWith(root + "/"))
				return mActivity.getString(R.string.fb_sel_storage) + path.substring(root.length());
			return path;
		}

		private boolean canGoUp() {
			File parent = mCurrent.getParentFile();
			if (parent == null || parent.listFiles() == null)
				return false;
			// don't leave the phone storage into system folders
			return !mCurrent.getAbsolutePath().equals(canonical(mRoot).getAbsolutePath());
		}

		void show() {
			mAdapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1);
			ListView list = new ListView(mActivity);
			list.setAdapter(mAdapter);
			list.setOnItemClickListener((parent, view, position, id) -> {
				if (position < 0 || position >= mEntries.size())
					return;
				File f = mEntries.get(position);
				if (f == NEW_FOLDER) {
					askNewFolder();
					return;
				}
				if (f == null)
					return; // "no folders" hint
				if (f.listFiles() == null) {
					mActivity.showToast(R.string.fb_sel_cannot_list);
					return;
				}
				mCurrent = canonical(f);
				refresh();
			});
			mDialog = new AlertDialog.Builder(mActivity)
					.setTitle(displayPath(mCurrent))
					.setView(list)
					.setPositiveButton(R.string.fb_sel_move_here, (d, w) -> mCallback.onFolderSelected(mCurrent))
					.setNegativeButton(R.string.dlg_button_cancel, null)
					.create();
			mDialog.setOnShowListener(d -> updateMoveButton());
			refresh();
			mDialog.show();
		}

		private void updateMoveButton() {
			if (mDialog == null)
				return;
			android.widget.Button b = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
			if (b != null)
				b.setEnabled(!mSourceFolders.contains(mCurrent.getAbsolutePath()));
		}

		private void refresh() {
			mEntries.clear();
			mAdapter.clear();
			mEntries.add(NEW_FOLDER);
			mAdapter.add("\uFF0B " + mActivity.getString(R.string.fb_sel_new_folder));
			if (canGoUp()) {
				mEntries.add(mCurrent.getParentFile());
				mAdapter.add("\u2191 " + mActivity.getString(R.string.fb_sel_up));
			}
			File[] dirs = mCurrent.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
			if (dirs != null && dirs.length > 0) {
				Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
				for (File d : dirs) {
					mEntries.add(d);
					mAdapter.add("\uD83D\uDCC1 " + d.getName());
				}
			} else {
				mEntries.add(null);
				mAdapter.add(mActivity.getString(R.string.fb_sel_no_folders));
			}
			mAdapter.notifyDataSetChanged();
			if (mDialog != null)
				mDialog.setTitle(displayPath(mCurrent));
			updateMoveButton();
		}

		private void askNewFolder() {
			final EditText edit = new EditText(mActivity);
			edit.setSingleLine(true);
			edit.setHint(R.string.fb_sel_folder_name);
			int pad = dp(20);
			android.widget.FrameLayout box = new android.widget.FrameLayout(mActivity);
			box.setPadding(pad, dp(8), pad, 0);
			box.addView(edit);
			final AlertDialog dlg = new AlertDialog.Builder(mActivity)
					.setTitle(R.string.fb_sel_new_folder)
					.setView(box)
					.setPositiveButton(R.string.fb_sel_create, (d, w) -> {
						String name = edit.getText().toString().trim();
						if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals(".."))
							return;
						File nf = new File(mCurrent, name);
						if (nf.isDirectory() || nf.mkdirs()) {
							mCurrent = canonical(nf);
							refresh();
						} else {
							mActivity.showToast(R.string.fb_sel_cannot_create);
						}
					})
					.setNegativeButton(R.string.dlg_button_cancel, null)
					.create();
			// show keyboard right away
			if (dlg.getWindow() != null)
				dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
			dlg.setOnShowListener(d -> {
				final android.widget.Button create = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
				create.setEnabled(false);
				edit.addTextChangedListener(new android.text.TextWatcher() {
					public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}
					public void onTextChanged(CharSequence cs, int a, int b, int c) {}
					public void afterTextChanged(android.text.Editable e) {
						String n = e.toString().trim();
						create.setEnabled(!n.isEmpty() && !n.contains("/"));
					}
				});
				edit.requestFocus();
			});
			dlg.show();
		}
	}
}
