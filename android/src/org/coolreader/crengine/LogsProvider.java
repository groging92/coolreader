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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only access to the log bundle for the share sheet (Telegram, mail etc.).
 * Serves only files from cache/share, by name.
 */
public class LogsProvider extends ContentProvider {

	public static String authority(Context context) {
		return context.getPackageName() + ".logs";
	}

	public static Uri uriFor(Context context, File file) {
		return Uri.parse("content://" + authority(context) + "/" + Uri.encode(file.getName()));
	}

	private File fileFor(Uri uri) {
		String name = uri.getLastPathSegment();
		if (name == null || name.contains("/") || name.contains(".."))
			return null;
		File f = new File(new File(getContext().getCacheDir(), "share"), name);
		return f.isFile() ? f : null;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		File f = fileFor(uri);
		if (f == null)
			return null;
		String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
		MatrixCursor c = new MatrixCursor(cols, 1);
		Object[] row = new Object[cols.length];
		for (int i = 0; i < cols.length; i++) {
			if (OpenableColumns.DISPLAY_NAME.equals(cols[i]))
				row[i] = f.getName();
			else if (OpenableColumns.SIZE.equals(cols[i]))
				row[i] = f.length();
		}
		c.addRow(row);
		return c;
	}

	@Override
	public String getType(Uri uri) {
		return "text/plain";
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (mode != null && !mode.equals("r"))
			throw new SecurityException("read only");
		File f = fileFor(uri);
		if (f == null)
			throw new FileNotFoundException(uri.toString());
		return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}
