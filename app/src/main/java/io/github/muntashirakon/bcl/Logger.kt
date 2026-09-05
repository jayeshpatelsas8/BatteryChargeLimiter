package io.github.muntashirakon.bcl

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
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
 * There are two copies of the log:
 *
 * 1. An internal "buffer" file at app-specific external storage, e.g.
 *      /storage/emulated/0/Android/data/io.github.muntashirakon.bcl/files/BCL_Logs/bcl_log.txt
 *    This is written with plain Java file I/O - no root required, never
 *    blocks on a root grant/prompt, and is reliable even before/without
 *    root. It's what backs the in-app "Share" button.
 *
 * 2. A PUBLIC copy at the root of shared storage, e.g.
 *      /storage/emulated/0/BCL_Logs/bcl_log_20260904_154210.txt
 *    (one file per app-process session, named with the session's start
 *    timestamp). This is periodically mirrored from the internal buffer
 *    using a root shell command, since scoped storage (API 29+) blocks a
 *    normal app process from writing directly to the public part of
 *    /sdcard. The app already requires root for its core function, so
 *    this simply reuses that same privilege to make the log visible to
 *    any ordinary file manager, without needing MANAGE_EXTERNAL_STORAGE
 *    or any extra permission prompts.
 *
 * All writes happen on a single background thread so this never blocks the
 * caller (BroadcastReceiver#onReceive, Service lifecycle callbacks, etc).
 * The one exception is the uncaught-exception handler, which writes
 * synchronously (and attempts one best-effort synchronous publish to the
 * public copy) because the process may be killed immediately afterwards.
 */
object Logger {
    private const val TAG = "BCL-Logger"
    private const val LOG_DIR_NAME = "BCL_Logs"
    private const val LOG_FILE_NAME = "bcl_log.txt"
    private const val OLD_LOG_FILE_NAME = "bcl_log_old.txt"

    /** Public, non-app-specific directory any file manager/MTP/PC can browse without root. */
    private const val PUBLIC_LOG_DIR = "/storage/emulated/0/BCL_Logs"

    // Rotate once the active log file passes this size, keeping one backup.
    private const val MAX_LOG_SIZE_BYTES = 2L * 1024 * 1024 // 2 MB

    // Don't spam a root shell call on every single log line; batch publishes.
    private const val PUBLISH_THROTTLE_MS = 5_000L

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val sessionFileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    // Fixed for the lifetime of the process, so repeated publishes overwrite
    // the same public file instead of creating a new one on every sync.
    @Volatile
    private var publicLogFileName: String? = null

    @Volatile
    private var lastPublishAtMs = 0L

    /**
     * Must be called once, as early as possible (App.onCreate). Safe to call
     * multiple times; only the first call has an effect.
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val app = context.applicationContext
            appContext = app
            val baseDir = app.getExternalFilesDir(null)
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
            publicLogFileName = "bcl_log_${sessionFileDateFormat.format(Date())}.txt"
            installCrashHandler()

            i("Logger", "================ Logger initialized ================")
            i("Logger", "Internal buffer file: ${logFile?.absolutePath}")
            i("Logger", "Public log (this session): $PUBLIC_LOG_DIR/$publicLogFileName")
            i(
                "Logger",
                "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build=${BuildConfig.BUILD_TYPE} " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) " +
                    "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.FINGERPRINT})"
            )
            // publish immediately so the public file exists (and is visible in file
            // managers) right away, instead of only after the first throttled write
            publishToPublicStorage()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize file logger", e)
        }
    }

    /** Absolute path of the internal buffer file, or null if not initialized. */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * Absolute path of the PUBLIC, file-manager-visible log for this session,
     * e.g. /storage/emulated/0/BCL_Logs/bcl_log_20260904_154210.txt. This is
     * the path to show the user - it requires root to be granted at least
     * once to actually be written, but the path itself is always known.
     */
    fun getPublicLogPath(): String? = publicLogFileName?.let { "$PUBLIC_LOG_DIR/$it" }

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
                maybePublish()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log line", e)
            }
        }
    }

    /** Publishes to public storage at most once every [PUBLISH_THROTTLE_MS]. */
    private fun maybePublish() {
        val now = System.currentTimeMillis()
        if (now - lastPublishAtMs >= PUBLISH_THROTTLE_MS) {
            lastPublishAtMs = now
            publishToPublicStorage()
        }
    }

    /**
     * Copies the internal buffer file to the public sdcard directory via a
     * root shell command (mkdir + chmod + cp), so it shows up in any regular
     * file manager without needing MANAGE_EXTERNAL_STORAGE. Runs the actual
     * shell command asynchronously - never blocks the caller. Silently does
     * nothing useful (but doesn't crash anything) if root isn't available;
     * the internal buffer file and the in-app Share button still work either way.
     */
    private fun publishToPublicStorage() {
        val internal = logFile ?: return
        val name = publicLogFileName ?: return
        try {
            val publicPath = "$PUBLIC_LOG_DIR/$name"
            val cmd = "mkdir -p $PUBLIC_LOG_DIR && chmod 777 $PUBLIC_LOG_DIR && " +
                "cp \"${internal.absolutePath}\" \"$publicPath\" && chmod 666 \"$publicPath\""
            Shell.cmd(cmd).submit { result ->
                if (result.isSuccess) {
                    // make it show up immediately in file managers / MTP / PC without a reboot
                    appContext?.let {
                        MediaScannerConnection.scanFile(it, arrayOf(publicPath), null, null)
                    }
                } else {
                    Log.w(
                        TAG,
                        "Could not publish log to public storage (code=${result.code}). " +
                            "This usually means root isn't granted yet; the in-app Share " +
                            "button still works regardless. stderr=${result.err}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception publishing log to public storage", e)
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

    /** Forces an immediate publish to the public sdcard copy, bypassing the throttle. */
    fun forcePublish() {
        executor.execute {
            lastPublishAtMs = System.currentTimeMillis()
            publishToPublicStorage()
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
                    // Best-effort synchronous publish so the crash is visible on the
                    // sdcard copy even if the process dies right after this handler
                    // returns. Bounded by the global Shell timeout (see App.kt); if
                    // root itself is unavailable/unresponsive this just times out and
                    // is swallowed - the internal buffer file already has the crash.
                    val name = publicLogFileName
                    if (name != null) {
                        try {
                            val publicPath = "$PUBLIC_LOG_DIR/$name"
                            Shell.cmd(
                                "mkdir -p $PUBLIC_LOG_DIR && chmod 777 $PUBLIC_LOG_DIR && " +
                                    "cp \"${file.absolutePath}\" \"$publicPath\" && chmod 666 \"$publicPath\""
                            ).exec()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to publish crash log synchronously", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log crash", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
