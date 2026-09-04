package io.github.muntashirakon.bcl

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Very small file-based logger used to diagnose service crashes / restarts
 * (in particular the "works on wall charger, crashes on power bank" issue).
 *
 * The log file lives on external (sdcard) storage under the app's own
 * directory, e.g.
 *   /storage/emulated/0/Android/data/io.github.muntashirakon.bcl/files/BCL_Logs/bcl_log.txt
 *
 * That path requires no extra runtime permission (unlike writing to the root
 * of /sdcard, which is blocked by scoped storage on API 29+ / targetSdk 34
 * unless the app declares MANAGE_EXTERNAL_STORAGE). It can still be:
 *  - viewed and shared directly from inside the app (Log Viewer screen)
 *  - pulled with `adb pull <path>` without needing the exact permission dance
 *  - browsed with any root-capable file manager (the app already requires
 *    root for its core function, so this is a non-issue on a rooted device)
 *
 * All writes happen on a single background thread so this never blocks the
 * caller (BroadcastReceiver#onReceive, Service lifecycle callbacks, etc).
 * The one exception is the uncaught-exception handler, which writes
 * synchronously because the process may be killed immediately afterwards.
 */
object Logger {
    private const val TAG = "BCL-Logger"
    private const val LOG_DIR_NAME = "BCL_Logs"
    private const val LOG_FILE_NAME = "bcl_log.txt"
    private const val OLD_LOG_FILE_NAME = "bcl_log_old.txt"

    // Rotate once the active log file passes this size, keeping one backup.
    private const val MAX_LOG_SIZE_BYTES = 2L * 1024 * 1024 // 2 MB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var initialized = false

    /**
     * Must be called once, as early as possible (App.onCreate). Safe to call
     * multiple times; only the first call has an effect.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val appContext = context.applicationContext
            val baseDir = appContext.getExternalFilesDir(null)
            if (baseDir == null) {
                Log.e(TAG, "External storage not available, file logging disabled")
                return
            }
            val dir = File(baseDir, LOG_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Could not create log directory: ${dir.absolutePath}")
                return
            }
            logFile = File(dir, LOG_FILE_NAME)
            installCrashHandler()

            i("Logger", "================ Logger initialized ================")
            i("Logger", "Log file: ${logFile?.absolutePath}")
            i(
                "Logger",
                "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build=${BuildConfig.BUILD_TYPE} " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.FINGERPRINT})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logger", e)
        }
    }

    /** Absolute path of the current log file, or null if not initialized. */
    fun getLogFilePath(): String? = logFile?.absolutePath

    fun getLogFile(): File? = logFile

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        write("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        write("W", tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        write("E", tag, msg, tr)
    }

    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        val file = logFile ?: return
        executor.execute {
            try {
                rotateIfNeeded(file)
                appendLine(file, level, tag, msg, tr)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log line", e)
            }
        }
    }

    private fun appendLine(file: File, level: String, tag: String, msg: String, tr: Throwable?) {
        FileOutputStream(file, true).use { fos ->
            PrintWriter(fos).use { pw ->
                val ts = dateFormat.format(Date())
                pw.println("$ts $level/$tag(pid:${Process.myPid()},tid:${Process.myTid()}): $msg")
                if (tr != null) {
                    pw.println(Log.getStackTraceString(tr))
                }
                pw.flush()
            }
        }
    }

    private fun rotateIfNeeded(file: File) {
        if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
            val old = File(file.parentFile, OLD_LOG_FILE_NAME)
            if (old.exists()) old.delete()
            file.renameTo(old)
        }
    }

    /** Deletes the log file(s) on disk. */
    fun clear() {
        executor.execute {
            try {
                logFile?.let { if (it.exists()) it.delete() }
                logFile?.parentFile?.let { parent ->
                    val old = File(parent, OLD_LOG_FILE_NAME)
                    if (old.exists()) old.delete()
                }
                i("Logger", "Log cleared by user")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear log", e)
            }
        }
    }

    /**
     * Reads the log for display in-app. Includes the rotated-out backup file
     * (if any) followed by the current file, tail-truncated to [maxChars].
     */
    fun readForDisplay(maxChars: Int = 300_000): String {
        val file = logFile ?: return "Logger not initialized yet."
        return try {
            val builder = StringBuilder()
            val old = File(file.parentFile, OLD_LOG_FILE_NAME)
            if (old.exists()) {
                builder.append(old.readText())
            }
            if (file.exists()) {
                builder.append(file.readText())
            }
            if (builder.isEmpty()) {
                "No log entries yet.\n\nLog file location:\n${file.absolutePath}"
            } else if (builder.length > maxChars) {
                "...[truncated, showing the most recent part]...\n\n" + builder.substring(builder.length - maxChars)
            } else {
                builder.toString()
            }
        } catch (e: Exception) {
            "Failed to read log file: ${e.message}"
        }
    }

    /**
     * Installs a global uncaught-exception handler that writes the crash
     * (thread, exception, full stack trace) to the log file *synchronously*
     * before handing off to the previous default handler, so the crash is
     * captured even though the process is about to die.
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = logFile
                if (file != null) {
                    appendLine(
                        file,
                        "FATAL",
                        "UncaughtException",
                        "Thread '${thread.name}' crashed the app/process",
                        throwable
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
