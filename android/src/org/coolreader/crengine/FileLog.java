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

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Local telemetry: info/warning/error log lines, performance events (PERF),
 * UI freezes (STALL) and crashes are written to rotating files in the app's
 * private folder. Nothing is sent anywhere automatically: the user shares
 * a bundle via "Send logs".
 */
public class FileLog {

	private static final String TAG = "cr3fork";
	private static final String FILE_NAME = "cr3fork.log";
	private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;
	private static final int KEEP_OLD_FILES = 3;
	private static final int MAX_QUEUE = 20000;

	private static volatile File sDir;
	private static final LinkedBlockingQueue<Object> sQueue = new LinkedBlockingQueue<>();
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
	private static String sHeader = "";
	private static final long START_TIME = SystemClock.elapsedRealtime();

	public static void setForeground(boolean foreground) {
		GuiWatchdog.setActive(foreground);
	}

	public static File getDir() {
		return sDir;
	}

	public static synchronized void init(Context context) {
		if (sDir != null)
			return;
		File dir = new File(context.getFilesDir(), "logs");
		if (!dir.isDirectory() && !dir.mkdirs())
			return;
		String version = "?";
		try {
			PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			version = pi.versionName + " (" + pi.versionCode + ")";
		} catch (Exception ignored) {
		}
		sHeader = "app: " + context.getPackageName() + " " + version + "\n" +
				"device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + "), Android " + Build.VERSION.RELEASE + " SDK " + Build.VERSION.SDK_INT + "\n" +
				"cpu: " + Build.HARDWARE + " " + join(Build.SUPPORTED_ABIS) + ", cores " + Runtime.getRuntime().availableProcessors() +
				", max heap " + (Runtime.getRuntime().maxMemory() >> 20) + "MB\n";
		sDir = dir;
		Thread writer = new Thread(FileLog::writerLoop, "cr3fork-log");
		writer.setDaemon(true);
		writer.setPriority(Thread.MIN_PRIORITY);
		writer.start();
		installCrashHandler();
		write('I', "START", "---- process start ----\n" + sHeader.trim(), null);
		GuiWatchdog.start();
	}

	private static String join(String[] items) {
		StringBuilder sb = new StringBuilder();
		for (String s : items) {
			if (sb.length() > 0)
				sb.append(',');
			sb.append(s);
		}
		return sb.toString();
	}

	private static String threadLabel() {
		Thread t = Thread.currentThread();
		if (t == Looper.getMainLooper().getThread())
			return "GUI";
		if (t == BackgroundThread.instance())
			return "ENG";
		return t.getName();
	}

	public static void write(char level, String tag, String msg, Throwable e) {
		if (sDir == null)
			return;
		if (sQueue.size() > MAX_QUEUE)
			return; // writer can't keep up, drop rather than eat memory
		StringBuilder sb = new StringBuilder(msg != null ? msg.length() + 64 : 64);
		synchronized (TIME_FORMAT) {
			sb.append(TIME_FORMAT.format(new Date()));
		}
		sb.append(' ').append(level).append(' ').append(threadLabel()).append(' ').append(tag).append(": ").append(msg);
		if (e != null)
			sb.append('\n').append(stackTrace(e));
		sb.append('\n');
		sQueue.offer(sb.toString());
	}

	/**
	 * Performance event: "PERF name 123ms details".
	 */
	public static void perf(String name, long ms, String details) {
		write('P', "PERF", name + " " + ms + "ms" + (details != null && !details.isEmpty() ? " " + details : ""), null);
		if (ms >= 300)
			Log.w(TAG, "PERF " + name + " " + ms + "ms " + details);
	}

	public static String stackTrace(Throwable e) {
		StringWriter sw = new StringWriter();
		e.printStackTrace(new PrintWriter(sw));
		return sw.toString();
	}

	public static String stackTrace(StackTraceElement[] stack, int maxFrames) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < stack.length && i < maxFrames; i++)
			sb.append("    at ").append(stack[i]).append('\n');
		return sb.toString();
	}

	private static File currentFile() {
		return new File(sDir, FILE_NAME);
	}

	private static File oldFile(int n) {
		return new File(sDir, FILE_NAME + "." + n);
	}

	private static void rotate() {
		File last = oldFile(KEEP_OLD_FILES);
		if (last.exists())
			//noinspection ResultOfMethodCallIgnored
			last.delete();
		for (int i = KEEP_OLD_FILES - 1; i >= 1; i--) {
			File f = oldFile(i);
			if (f.exists())
				//noinspection ResultOfMethodCallIgnored
				f.renameTo(oldFile(i + 1));
		}
		//noinspection ResultOfMethodCallIgnored
		currentFile().renameTo(oldFile(1));
	}

	private static void writerLoop() {
		Writer out = null;
		try {
			while (true) {
				Object item = sQueue.take();
				if (out == null) {
					if (currentFile().length() > MAX_FILE_SIZE)
						rotate();
					out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(currentFile(), true), "UTF-8"), 16384);
				}
				if (item instanceof CountDownLatch) {
					out.flush();
					((CountDownLatch) item).countDown();
					continue;
				}
				out.write((String) item);
				if (sQueue.isEmpty()) {
					out.flush();
					if (currentFile().length() > MAX_FILE_SIZE) {
						out.close();
						out = null;
					}
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "log writer stopped", e);
		}
	}

	/**
	 * Wait until all queued lines are on disk.
	 */
	public static void flush(long timeoutMillis) {
		if (sDir == null)
			return;
		CountDownLatch latch = new CountDownLatch(1);
		sQueue.offer(latch);
		try {
			latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ignored) {
		}
	}

	private static void installCrashHandler() {
		final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
			try {
				write('E', "CRASH", "Uncaught exception in thread " + t.getName(), e);
				flush(1500);
			} catch (Throwable ignored) {
			}
			if (prev != null)
				prev.uncaughtException(t, e);
		});
	}

	/**
	 * Build one text file for sharing: device info, our log files (old first) and logcat.
	 * Called from a background thread.
	 */
	public static File buildBundle(Context context) throws Exception {
		flush(2000);
		File shareDir = new File(context.getCacheDir(), "share");
		if (!shareDir.isDirectory())
			//noinspection ResultOfMethodCallIgnored
			shareDir.mkdirs();
		File[] old = shareDir.listFiles();
		if (old != null)
			for (File f : old)
				//noinspection ResultOfMethodCallIgnored
				f.delete();
		String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
		File out = new File(shareDir, "cr3fork-logs-" + stamp + ".txt");
		try (OutputStream os = new FileOutputStream(out)) {
			os.write(("==== CoolReaderFork logs " + new Date() + "\n" + sHeader +
					"uptime: " + ((SystemClock.elapsedRealtime() - START_TIME) / 1000) + "s\n\n").getBytes("UTF-8"));
			if (sDir != null) {
				for (int i = KEEP_OLD_FILES; i >= 1; i--)
					appendFile(os, oldFile(i));
				appendFile(os, currentFile());
			}
			os.write("\n==== logcat (this process) ====\n".getBytes("UTF-8"));
			LogcatSaver.saveLogcat(new Date(System.currentTimeMillis() - 24L * 3600 * 1000), new NonClosingStream(os));
		}
		return out;
	}

	private static void appendFile(OutputStream os, File f) throws Exception {
		if (!f.isFile())
			return;
		os.write(("\n==== " + f.getName() + " ====\n").getBytes("UTF-8"));
		try (InputStream in = new FileInputStream(f)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
				os.write(buf, 0, n);
		}
	}

	/** LogcatSaver closes the stream it gets; keep ours open. */
	private static class NonClosingStream extends java.io.FilterOutputStream {
		NonClosingStream(OutputStream out) {
			super(out);
		}

		@Override
		public void write(byte[] b, int off, int len) throws java.io.IOException {
			out.write(b, off, len);
		}

		@Override
		public void close() throws java.io.IOException {
			flush();
		}
	}

	/**
	 * Detects UI freezes: if the GUI thread does not respond for STALL_MS,
	 * logs stacks of GUI and engine threads, then the total freeze duration.
	 */
	static class GuiWatchdog {
		private static final long CHECK_MS = 200;
		private static final long STALL_MS = 400;
		private static volatile long sLastPong = SystemClock.uptimeMillis();
		private static boolean sStarted;
		private static final Object sLock = new Object();
		private static boolean sActive = true;

		/** Only watch while the activity is in foreground (no wakeups in background). */
		static void setActive(boolean active) {
			synchronized (sLock) {
				sActive = active;
				sLastPong = SystemClock.uptimeMillis();
				sLock.notifyAll();
			}
		}

		static synchronized void start() {
			if (sStarted)
				return;
			sStarted = true;
			final Handler gui = new Handler(Looper.getMainLooper());
			final Runnable pong = () -> sLastPong = SystemClock.uptimeMillis();
			Thread t = new Thread(() -> {
				long stallStart = 0;
				while (true) {
					try {
						synchronized (sLock) {
							while (!sActive) {
								sLock.wait();
								stallStart = 0;
							}
						}
						gui.post(pong);
						Thread.sleep(CHECK_MS);
						long now = SystemClock.uptimeMillis();
						long silent = now - sLastPong;
						if (silent >= STALL_MS) {
							if (stallStart == 0) {
								stallStart = sLastPong;
								StringBuilder sb = new StringBuilder();
								sb.append("GUI thread blocked >= ").append(silent).append("ms\n  GUI stack:\n");
								sb.append(stackTrace(Looper.getMainLooper().getThread().getStackTrace(), 30));
								Thread eng = BackgroundThread.instance();
								if (eng != null) {
									sb.append("  ENGINE stack:\n");
									sb.append(stackTrace(eng.getStackTrace(), 20));
								}
								write('W', "STALL", sb.toString().trim(), null);
							}
						} else if (stallStart != 0) {
							perf("gui.stall", sLastPong - stallStart, "");
							stallStart = 0;
						}
					} catch (InterruptedException e) {
						return;
					} catch (Throwable e) {
						Log.e(TAG, "watchdog", e);
					}
				}
			}, "cr3fork-watchdog");
			t.setDaemon(true);
			t.start();
		}
	}
}
