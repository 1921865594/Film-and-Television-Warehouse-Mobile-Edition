package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Early-startup diagnostic logger.
 * All logs are stored only in the app-private files/diagnostics directory.
 * Previous .log files are cleared at every process start.
 */
public final class GlobalCrashLogger {
    private static final String TAG = "MBox-Diagnostic";
    private static final Object LOCK = new Object();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_LOGCAT_BYTES = 8L * 1024L * 1024L;

    private static volatile boolean initialized;
    private static volatile boolean handlingFatal;
    private static volatile boolean logcatLimitReached;
    private static boolean lifecycleCallbacksRegistered;
    private static Context appContext;
    private static File internalLogFile;
    private static FileOutputStream internalOutput;
    private static String fileName;
    private static long logcatBytes;
    private static Thread.UncaughtExceptionHandler previousHandler;

    private GlobalCrashLogger() { }

    /** Must be called from Application.attachBaseContext, before normal startup. */
    public static void initialize(Context context) {
        synchronized (LOCK) {
            if (initialized) return;
            initialized = true;
            try {
                appContext = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                String clearError = clearOldLogsLocked();
                openPrivateLogLocked(true);
                installUncaughtExceptionHandler();

                appendLocked("INFO", "LOGGER", "Global diagnostic logger initialized");
                appendLocked("INFO", "LOGGER", "Private log: " + internalLogFile.getAbsolutePath());
                if (clearError != null) {
                    appendLocked("WARN", "LOGGER", "Some old log files could not be deleted: " + clearError);
                }
                writeDeviceInfoLocked();
                registerLifecycleCallbacksLocked();
                startLogcatCaptureLocked();
            } catch (Throwable error) {
                // Diagnostics must never prevent the application from starting.
                try {
                    appendLocked("ERROR", "LOGGER_INIT", stackTrace(error));
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Clear only log files from the app-private diagnostics directory. */
    private static String clearOldLogsLocked() {
        if (appContext == null) return "application context unavailable";
        File logDir = new File(appContext.getFilesDir(), "diagnostics");
        if (!logDir.exists() && !logDir.mkdirs()) {
            return "cannot create diagnostics directory: " + logDir.getAbsolutePath();
        }
        File[] oldLogs = logDir.listFiles();
        if (oldLogs == null) return null;
        StringBuilder failures = new StringBuilder();
        for (File oldLog : oldLogs) {
            if (oldLog == null || !oldLog.isFile()) continue;
            String name = oldLog.getName();
            if (!name.endsWith(".log")) continue;
            if (!oldLog.delete()) {
                if (failures.length() > 0) failures.append(", ");
                failures.append(name);
            }
        }
        return failures.length() == 0 ? null : failures.toString();
    }

    private static void openPrivateLogLocked(boolean createNew) throws Exception {
        if (appContext == null) throw new IllegalStateException("Application context unavailable");
        File logDir = new File(appContext.getFilesDir(), "diagnostics");
        if (!logDir.exists() && !logDir.mkdirs()) {
            throw new IllegalStateException("Cannot create private diagnostics directory: " + logDir);
        }
        if (fileName == null || createNew) {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            fileName = "MBox_runtime_" + stamp + "_pid" + Process.myPid() + ".log";
        }
        internalLogFile = new File(logDir, fileName);
        internalOutput = new FileOutputStream(internalLogFile, !createNew);
    }

    private static void installUncaughtExceptionHandler() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                if (!handlingFatal) {
                    handlingFatal = true;
                    try {
                        synchronized (LOCK) {
                            appendLocked("FATAL", "UNCAUGHT", "Uncaught exception on thread: "
                                    + (thread == null ? "unknown" : thread.getName())
                                    + " (id=" + (thread == null ? -1 : thread.getId()) + ")");
                            appendLocked("FATAL", "UNCAUGHT", stackTrace(throwable));
                            appendLocked("FATAL", "UNCAUGHT", "Process is terminating after uncaught exception");
                            flushLocked(true);
                        }
                    } catch (Throwable ignored) { }
                }
                if (previousHandler != null && previousHandler != this) {
                    previousHandler.uncaughtException(thread, throwable);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    /** Rebinds the early base Context to the Application and installs lifecycle probes. */
    public static void attachApplication(Application application) {
        synchronized (LOCK) {
            if (application != null) appContext = application;
            if (internalOutput == null && appContext != null) {
                try {
                    openPrivateLogLocked(false);
                    appendLocked("INFO", "LOGGER", "Private diagnostics output recovered after Application attach");
                } catch (Throwable ignored) { }
            }
            registerLifecycleCallbacksLocked();
            appendLocked("INFO", "LOGGER", "Application context attached");
        }
    }

    private static void registerLifecycleCallbacksLocked() {
        if (lifecycleCallbacksRegistered || !(appContext instanceof Application)) return;
        lifecycleCallbacksRegistered = true;
        ((Application) appContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                append("INFO", "LIFECYCLE", "Activity created: " + activity.getClass().getName());
            }
            @Override public void onActivityStarted(Activity activity) {
                append("INFO", "LIFECYCLE", "Activity started: " + activity.getClass().getName());
            }
            @Override public void onActivityResumed(Activity activity) {
                append("INFO", "LIFECYCLE", "Activity resumed: " + activity.getClass().getName());
            }
            @Override public void onActivityPaused(Activity activity) {
                append("INFO", "LIFECYCLE", "Activity paused: " + activity.getClass().getName());
            }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) {
                append("INFO", "LIFECYCLE", "Activity destroyed: " + activity.getClass().getName());
            }
        });
    }

    private static void startLogcatCaptureLocked() {
        final int pid = Process.myPid();
        Thread readerThread = new Thread(new Runnable() {
            @Override public void run() {
                java.lang.Process process = null;
                BufferedReader reader = null;
                try {
                    String[] command = Build.VERSION.SDK_INT >= 24
                            ? new String[]{"logcat", "-v", "threadtime", "--pid=" + pid}
                            : new String[]{"logcat", "-v", "threadtime"};
                    process = Runtime.getRuntime().exec(command);
                    reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF8));
                    append("INFO", "LOGCAT", "Started current-process logcat capture, pid=" + pid);
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Build.VERSION.SDK_INT < 24 && !looksLikeOurPid(line, pid)) continue;
                        synchronized (LOCK) {
                            if (logcatLimitReached) continue;
                            long bytes = line.getBytes(UTF8).length + 1;
                            if (logcatBytes + bytes > MAX_LOGCAT_BYTES) {
                                logcatLimitReached = true;
                                appendLocked("WARN", "LOGCAT", "Logcat capture reached 8 MiB limit; other diagnostic logging continues");
                                continue;
                            }
                            logcatBytes += bytes;
                            appendLocked("LOGCAT", "PID-" + pid, line);
                        }
                    }
                    append("WARN", "LOGCAT", "Logcat reader ended");
                } catch (Throwable error) {
                    append("ERROR", "LOGCAT", "Could not capture logcat: " + stackTrace(error));
                } finally {
                    try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
                    if (process != null) {
                        try { process.destroy(); } catch (Throwable ignored) { }
                    }
                }
            }
        }, "MBox-Logcat-Capture");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static boolean looksLikeOurPid(String line, int pid) {
        if (line == null) return false;
        String value = String.valueOf(pid);
        return line.contains(" " + value + " ") || line.contains(" " + value + ":");
    }

    private static void writeDeviceInfoLocked() {
        try {
            appendLocked("INFO", "DEVICE", "package=" + appContext.getPackageName());
            appendLocked("INFO", "DEVICE", "manufacturer=" + Build.MANUFACTURER + ", model=" + Build.MODEL
                    + ", device=" + Build.DEVICE + ", product=" + Build.PRODUCT);
            appendLocked("INFO", "DEVICE", "android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            appendLocked("INFO", "DEVICE", "supportedAbis=" + join(Build.SUPPORTED_ABIS));
            appendLocked("INFO", "DEVICE", "fingerprint=" + Build.FINGERPRINT);
            appendLocked("INFO", "DEVICE", "appVersion=" + getAppVersion());
            appendLocked("INFO", "DEVICE", "processPid=" + Process.myPid());
        } catch (Throwable error) {
            appendLocked("WARN", "DEVICE", "Could not collect full device info: " + error);
        }
    }

    private static String getAppVersion() {
        try {
            PackageInfo info = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Throwable error) {
            return "unknown: " + error;
        }
    }

    private static String join(String[] values) {
        if (values == null) return "unknown";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        return out.toString();
    }

    public static void mark(String message) {
        append("BOOT", "STARTUP", message);
    }

    public static void log(String level, String tag, String message) {
        append(level, tag, message);
    }

    public static void exception(String tag, Throwable throwable) {
        append("ERROR", tag, stackTrace(throwable));
    }

    private static void append(String level, String tag, String message) {
        synchronized (LOCK) {
            appendLocked(level, tag, message);
        }
    }

    private static void appendLocked(String level, String tag, String message) {
        if (internalOutput == null) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " [" + (level == null ? "INFO" : level) + "] ["
                + (tag == null ? TAG : tag) + "] " + (message == null ? "null" : message) + "\n";
        try {
            internalOutput.write(line.getBytes(UTF8));
            internalOutput.flush();
        } catch (Throwable error) {
            try { internalOutput.close(); } catch (Throwable ignored) { }
            internalOutput = null;
        }
    }

    private static void flushLocked(boolean sync) {
        try {
            if (internalOutput != null) {
                internalOutput.flush();
                if (sync) internalOutput.getFD().sync();
            }
        } catch (Throwable ignored) { }
    }

    /** Returns the private app-files path of the current launch's diagnostic log. */
    public static String getLogLocation() {
        synchronized (LOCK) {
            return internalLogFile == null ? "private diagnostics log is not available" : internalLogFile.getAbsolutePath();
        }
    }

    public static void flush() {
        synchronized (LOCK) { flushLocked(true); }
    }

    private static String stackTrace(Throwable throwable) {
        if (throwable == null) return "<null throwable>";
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);
        throwable.printStackTrace(printWriter);
        printWriter.flush();
        return writer.toString();
    }
}
