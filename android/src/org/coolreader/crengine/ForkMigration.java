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

import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;

/**
 * CoolReaderFork keeps its data (books DB, settings, cache) in its own folder
 * /sdcard/.cr3fork, separate from the original Cool Reader's /sdcard/.cr3.
 *
 * On the first run with storage access, data of the original app is copied once:
 * reading positions, history, statuses, bookmarks, covers and settings.
 * The original files are never modified; after the copy both apps live separately.
 */
public class ForkMigration {

	public static final Logger log = L.create("fork");

	public static final String DATA_DIR_NAME = ".cr3fork";
	public static final String LEGACY_DATA_DIR_NAME = ".cr3";

	private static final String MARKER_FILE = "migration-from-cr3.txt";
	private static final String[] DB_FILES = {"cr3db.sqlite", "cr3db_cover.sqlite"};
	private static final String SETTINGS_PREFIX = "cr3.ini";

	private static boolean sDone = false;

	private static File storageRoot() {
		return DeviceInfo.EINK_NOOK ? new File("/media/") : Environment.getExternalStorageDirectory();
	}

	/**
	 * Run once, before settings or database are opened from the data folder.
	 * Does nothing until shared storage is writable (all files access granted).
	 */
	public static synchronized void migrateIfNeeded() {
		if (sDone)
			return;
		File root = storageRoot();
		if (root == null || !root.isDirectory() || !root.canWrite())
			return; // no access yet, try again later
		File newDir = new File(root, DATA_DIR_NAME);
		File marker = new File(newDir, MARKER_FILE);
		if (marker.exists()) {
			sDone = true;
			return;
		}
		if (!newDir.isDirectory() && !newDir.mkdirs()) {
			log.e("Cannot create " + newDir);
			return;
		}
		StringBuilder report = new StringBuilder();
		report.append("date: ").append(new Date()).append('\n');
		File oldDir = new File(root, LEGACY_DATA_DIR_NAME);
		if (oldDir.isDirectory()) {
			for (String name : DB_FILES) {
				File src = new File(oldDir, name);
				File dst = new File(newDir, name);
				if (!src.isFile())
					continue;
				if (dst.exists()) {
					report.append(name).append(": skipped, already exists\n");
					continue;
				}
				boolean ok = snapshotDatabase(src, dst) || copyFile(src, dst);
				report.append(name).append(ok ? ": copied\n" : ": FAILED\n");
			}
			File[] settings = oldDir.listFiles(f -> f.isFile() && f.getName().startsWith(SETTINGS_PREFIX));
			if (settings != null) {
				for (File src : settings) {
					File dst = new File(newDir, src.getName());
					if (dst.exists()) {
						report.append(src.getName()).append(": skipped, already exists\n");
						continue;
					}
					boolean ok = copyFile(src, dst);
					report.append(src.getName()).append(ok ? ": copied\n" : ": FAILED\n");
				}
			}
		} else {
			report.append(oldDir).append(" not found, nothing to copy\n");
		}
		log.i("Migration from " + oldDir + ":\n" + report);
		try (Writer w = new OutputStreamWriter(new FileOutputStream(marker), "UTF-8")) {
			w.write(report.toString());
		} catch (Exception e) {
			log.e("Cannot write migration marker", e);
		}
		sDone = true;
	}

	/**
	 * Consistent copy of SQLite DB, even if the original app has it open right now.
	 */
	private static boolean snapshotDatabase(File src, File dst) {
		SQLiteDatabase db = null;
		try {
			db = SQLiteDatabase.openDatabase(src.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
			db.execSQL("VACUUM INTO '" + dst.getAbsolutePath().replace("'", "''") + "'");
			return dst.isFile() && dst.length() > 0;
		} catch (Exception e) {
			log.w("VACUUM INTO failed for " + src + ": " + e);
			//noinspection ResultOfMethodCallIgnored
			dst.delete();
			return false;
		} finally {
			if (db != null)
				db.close();
		}
	}

	private static boolean copyFile(File src, File dst) {
		try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return true;
		} catch (Exception e) {
			log.e("Copy failed " + src + " -> " + dst, e);
			//noinspection ResultOfMethodCallIgnored
			dst.delete();
			return false;
		}
	}
}
