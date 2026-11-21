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

	private static final int SELECTED_ITEM_COLOR = 0x5533B5E5;

	private final CoolReader mActivity;
	private final FileBrowser mBrowser;
	private final LinkedHashMap<String, FileInfo> mSelected = new LinkedHashMap<>();
	private boolean mActive;
	private LinearLayout mBar;
	private TextView mCountView;

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
		if (mSelected.isEmpty())
			finish();
		else
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
		if (mSelected.isEmpty())
			finish();
		else
			update();
	}

	/**
	 * Highlight list item view if the item is selected.
	 */
	@SuppressWarnings("deprecation")
	public void decorate(View view, FileInfo item) {
		if (view == null)
			return;
		if (mActive && isSelected(item))
			view.setBackgroundColor(SELECTED_ITEM_COLOR);
		else
			view.setBackgroundDrawable(null);
	}

	private void update() {
		if (mBar != null) {
			mBar.setVisibility(mActive ? View.VISIBLE : View.GONE);
			if (mCountView != null)
				mCountView.setText(String.valueOf(mSelected.size()));
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

	public View getBar() {
		if (mBar != null) {
			if (mBar.getParent() instanceof ViewGroup)
				((ViewGroup) mBar.getParent()).removeView(mBar);
			return mBar;
		}
		mBar = new LinearLayout(mActivity);
		mBar.setOrientation(LinearLayout.HORIZONTAL);
		mBar.setGravity(Gravity.CENTER_VERTICAL);
		mBar.setBackgroundColor(0xF0303030);
		mBar.setPadding(dp(2), 0, dp(2), 0);

		mBar.addView(makeButton("✕", v -> finish()),
				new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		mCountView = new TextView(mActivity);
		mCountView.setTextColor(Color.WHITE);
		mCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		mCountView.setPadding(dp(4), 0, dp(4), 0);
		mBar.addView(mCountView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		LinearLayout buttons = new LinearLayout(mActivity);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.addView(makeButton(mActivity.getString(R.string.fb_sel_all), v -> toggleAll()));
		buttons.addView(makeButton(mActivity.getString(R.string.fb_sel_status), v -> askStatus()));
		buttons.addView(makeButton(mActivity.getString(R.string.fb_sel_move), v -> askMove()));
		buttons.addView(makeButton(mActivity.getString(R.string.fb_sel_delete), v -> askDelete()));
		HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
		scroll.setHorizontalScrollBarEnabled(false);
		scroll.addView(buttons);
		mBar.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		mBar.setVisibility(mActive ? View.VISIBLE : View.GONE);
		return mBar;
	}

	private ArrayList<FileInfo> selectedItems() {
		return new ArrayList<>(mSelected.values());
	}

	private void refreshCurrentDir() {
		FileInfo dir = mBrowser.getCurrentDir();
		if (dir == null)
			return;
		if (dir.isSpecialDir() && !dir.isOPDSRoot() && !dir.isOPDSDir())
			mBrowser.showDirectory(dir, null); // reload virtual folder (recent, by state etc.) from DB
		else
			mActivity.directoryUpdated(dir);
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
			finish();
			refreshCurrentDir();
		}));
	}

	//=====================================================================
	// Delete
	//=====================================================================

	private void askDelete() {
		final ArrayList<FileInfo> items = selectedItems();
		if (items.isEmpty())
			return;
		mActivity.askConfirmation(mActivity.getString(R.string.fb_sel_confirm_delete, items.size()), () -> {
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
			mActivity.showToast(mActivity.getString(R.string.fb_sel_deleted, ok, failed));
			finish();
			refreshCurrentDir();
		});
	}

	//=====================================================================
	// Move
	//=====================================================================

	private File startFolderForMove(Collection<FileInfo> items) {
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
		new FolderPicker(startFolderForMove(items), destDir -> moveTo(items, destDir)).show();
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
			mActivity.showToast(mActivity.getString(R.string.fb_sel_moved, moved.size(), failed));
			finish();
			refreshCurrentDir();
		});
	}

	private interface FolderSelectedCallback {
		void onFolderSelected(File dir);
	}

	/**
	 * Simple folder chooser: navigate folders, create a new one, pick the current one.
	 */
	private class FolderPicker {
		private File mCurrent;
		private final FolderSelectedCallback mCallback;
		private final ArrayList<File> mEntries = new ArrayList<>();
		private ArrayAdapter<String> mAdapter;
		private AlertDialog mDialog;

		FolderPicker(File start, FolderSelectedCallback callback) {
			mCurrent = start;
			mCallback = callback;
		}

		void show() {
			mAdapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1);
			ListView list = new ListView(mActivity);
			list.setAdapter(mAdapter);
			list.setOnItemClickListener((parent, view, position, id) -> {
				if (position < 0 || position >= mEntries.size())
					return;
				File f = mEntries.get(position);
				if (f.listFiles() == null) {
					mActivity.showToast(R.string.fb_sel_cannot_list);
					return;
				}
				mCurrent = f;
				refresh();
			});
			mDialog = new AlertDialog.Builder(mActivity)
					.setTitle(mCurrent.getAbsolutePath())
					.setView(list)
					.setPositiveButton(R.string.fb_sel_move_here, (d, w) -> mCallback.onFolderSelected(mCurrent))
					.setNeutralButton(R.string.fb_sel_new_folder, null)
					.setNegativeButton(R.string.dlg_button_cancel, null)
					.create();
			mDialog.setOnShowListener(d -> mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> askNewFolder()));
			refresh();
			mDialog.show();
		}

		private void refresh() {
			mEntries.clear();
			mAdapter.clear();
			File parent = mCurrent.getParentFile();
			if (parent != null && parent.listFiles() != null) {
				mEntries.add(parent);
				mAdapter.add("↑ ..");
			}
			File[] dirs = mCurrent.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
			if (dirs != null) {
				Arrays.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
				for (File d : dirs) {
					mEntries.add(d);
					mAdapter.add("📁 " + d.getName());
				}
			}
			mAdapter.notifyDataSetChanged();
			if (mDialog != null)
				mDialog.setTitle(mCurrent.getAbsolutePath());
		}

		private void askNewFolder() {
			final EditText edit = new EditText(mActivity);
			edit.setSingleLine(true);
			new AlertDialog.Builder(mActivity)
					.setTitle(R.string.fb_sel_new_folder)
					.setMessage(R.string.fb_sel_folder_name)
					.setView(edit)
					.setPositiveButton(R.string.dlg_button_ok, (d, w) -> {
						String name = edit.getText().toString().trim();
						if (name.isEmpty() || name.contains("/"))
							return;
						File nf = new File(mCurrent, name);
						if (nf.isDirectory() || nf.mkdirs()) {
							mCurrent = nf;
							refresh();
						} else {
							mActivity.showToast(R.string.fb_sel_cannot_list);
						}
					})
					.setNegativeButton(R.string.dlg_button_cancel, null)
					.show();
		}
	}
}
