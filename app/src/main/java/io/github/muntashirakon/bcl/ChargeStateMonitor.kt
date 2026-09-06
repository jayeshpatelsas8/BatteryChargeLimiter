package io.github.muntashirakon.bcl

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Independent diagnostic monitor for the "app says it wrote 0 but the device
 * keeps charging anyway" class of bug.
 *
 * This class OBSERVES three independent layers of truth and reports when they
 * disagree. It never issues a charging-control command itself:
 *
 *   1. POLICY truth   - what BatteryReceiver's real controller decided (receiverShouldCharge),
 *                       delivered cross-process via a broadcast since this monitor now runs
 *                       inside HeartbeatService, in a SEPARATE OS process from BatteryReceiver
 *   2. CONTROL truth  - what the root control file actually contains right now (actualBit),
 *                       read independently of any write BatteryReceiver/Utils performed
 *   3. HARDWARE truth - what Android reports the device is actually doing (isCharging)
 *
 * It also independently re-derives its own opinion of what should be happening
 * (heartbeatShouldCharge) from raw battery-level/limit numbers, WITHOUT depending on
 * BatteryReceiver's internal bookkeeping - so a bug specific to that bookkeeping
 * doesn't just get silently echoed back as confirmation.
 *
 * Runs inside HeartbeatService, which is declared with its own OS process
 * (android:process=":monitor") specifically so that a crash anywhere in the main
 * app process (BatteryReceiver, Utils, UI, etc.) cannot take this monitor down with
 * it. If the main process dies, updateReceiverState() simply stops being called;
 * this class detects that silence itself (MAIN_PROCESS_UNRESPONSIVE) and keeps
 * logging using data it can gather independently (Android's own battery broadcast,
 * a fresh root-shell read of the control file).
 */
class ChargeStateMonitor(private val service: Service) {

    enum class MonitoringState { ENFORCE, DISABLED, NOT_PLUGGED }
    private enum class DeviationState { OK, PENDING, ERROR, RECOVERY_PENDING }

    companion object {
        private const val TAG = "ChargeStateMonitor"
        private const val TICK_MS = 1000L
        private const val CHECKPOINT_MS = 30_000L
        private const val PENDING_TO_ERROR_MS = 2000L
        private const val RECOVERY_TICKS_NEEDED = 2
        private const val COMMAND_CORRELATION_WINDOW_MS = 5000L

        // If monitoring should be active (plugged in, limit enabled) but no
        // cross-process state update has arrived from BatteryReceiver in this long,
        // the main app process is presumed to have died/stopped responding.
        private const val MAIN_PROCESS_TIMEOUT_MS = 60_000L

        private const val PREFS_NAME = "bcl_monitor"
        private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
        private const val KEY_LAST_HEARTBEAT_SEQ = "last_heartbeat_seq"

        // A gap bigger than this, found on the next check, is reported as a
        // likely crash/kill rather than normal Doze-related scheduling slop.
        private const val SUSPICIOUS_GAP_MS = 90_000L

        private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        /**
         * Call from ForegroundService.onCreate(), HeartbeatService.onCreate(), and
         * MainActivity.onCreate(). A service that crash-loops and eventually gives up
         * leaves no trace of its own death, so the *next* thing that starts up (a
         * service restart, or the user opening the app) is what has to notice the silence.
         */
        fun checkForHeartbeatGap(context: Context, source: String) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val lastAt = prefs.getLong(KEY_LAST_HEARTBEAT_AT, -1L)
                val lastSeq = prefs.getLong(KEY_LAST_HEARTBEAT_SEQ, -1L)
                if (lastAt <= 0L) return // no prior heartbeat recorded yet, nothing to compare against
                val gapMs = System.currentTimeMillis() - lastAt
                if (gapMs > SUSPICIOUS_GAP_MS) {
                    Logger.w(
                        TAG,
                        "HEARTBEAT_GAP source=$source lastHeartbeatAt=${timeFmt.format(Date(lastAt))} " +
                            "lastHeartbeatSeq=$lastSeq gapMs=$gapMs gapSeconds=${gapMs / 1000} - heartbeat " +
                            "generation stopped unexpectedly before this check ran (likely a crash or the " +
                            "service/process being killed outright, not a normal Doze delay)"
                    )
                }
            } catch (e: Exception) {
                Logger.e(TAG, "checkForHeartbeatGap($source) failed", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false
    private var seq = 0L

    private var deviationState = DeviationState.OK
    private var deviationSince = 0L
    private var recoveryTicksMatched = 0
    private var lastCheckpointWriteAt = 0L
    private var currentIncidentReason = ""

    // Fed cross-process by HeartbeatService's broadcast receiver whenever BatteryReceiver
    // (main process) issues a command. lastReceiverUpdateAt is both the PENDING-window
    // anchor AND the signal used to detect the main process going silent/dead.
    @Volatile private var lastKnownShouldCharge: Boolean? = null
    @Volatile private var lastKnownExpectedBit: String? = null
    @Volatile private var lastReceiverUpdateAt: Long = 0L

    @Volatile private var lastControlFileBit: String? = null
    @Volatile private var lastControlFileReadResult: String = "UNKNOWN"
    @Volatile private var controlFileReadInFlight = false

    private val monitorPrefs by lazy {
        service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Called by HeartbeatService's cross-process broadcast receiver whenever
     * BatteryReceiver (in the main process) issues a CHARGE_ON/CHARGE_OFF command.
     * If this stops arriving while monitoring should be active, that silence itself
     * becomes a reportable condition (MAIN_PROCESS_UNRESPONSIVE).
     */
    fun updateReceiverState(shouldCharge: Boolean, expectedBitValue: String) {
        lastKnownShouldCharge = shouldCharge
        lastKnownExpectedBit = expectedBitValue
        lastReceiverUpdateAt = System.currentTimeMillis()
    }

    fun start() {
        if (running) return
        running = true
        acquireWakeLock()
        Logger.i(TAG, "ChargeStateMonitor started")
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tickRunnable)
        releaseWakeLock()
        Logger.i(TAG, "ChargeStateMonitor stopped")
    }

    private fun acquireWakeLock() {
        try {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BCL:ChargeStateMonitor")
            wl.setReferenceCounted(false)
            // Long safety timeout rather than "forever" - if stop() is ever missed due to
            // an unexpected process death, the wake lock still self-releases eventually
            // instead of silently draining the battery indefinitely.
            wl.acquire(8 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Logger.e(TAG, "Exception during heartbeat tick", e)
            } finally {
                if (running) {
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }
    }

    private fun tick() {
        val tickTime = System.currentTimeMillis()
        seq++

        val batteryIntent = service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent == null) {
            Logger.w(TAG, "heartbeatSeq=$seq could not read sticky battery intent, skipping tick")
            return
        }

        val pluggedExtra = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val pluggedIn = pluggedExtra != 0
        val batteryLevel = Utils.getBatteryLevel(batteryIntent)
        val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

        val currentNow: Int = try {
            val bm = service.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (e: Exception) {
            Int.MIN_VALUE
        }

        val settings = Utils.getSettings(service)
        val limitEnabled = settings.getBoolean(Constants.CHARGE_LIMIT_ENABLED, false)
        val limit = settings.getInt(Constants.LIMIT, Constants.DEFAULT_LIMIT_PC)
        val recharge = settings.getInt(Constants.MIN, limit - 2)

        val monitoringState = when {
            !limitEnabled -> MonitoringState.DISABLED
            !pluggedIn -> MonitoringState.NOT_PLUGGED
            else -> MonitoringState.ENFORCE
        }

        // Kick off an independent, asynchronous read of the control file. This is a
        // SEPARATE Shell.cmd() call from anything Utils.changeState() does internally -
        // deliberately not reusing that code path, so a bug correlated with one shared
        // path can't hide from both at once. Also works with zero dependency on the
        // main process being alive, since it's this process's own root shell session.
        requestControlFileRead()

        persistLastHeartbeat(tickTime)

        if (monitoringState != MonitoringState.ENFORCE) {
            deviationState = DeviationState.OK
            if (tickTime - lastCheckpointWriteAt >= CHECKPOINT_MS) {
                lastCheckpointWriteAt = tickTime
                Logger.i(
                    TAG,
                    "HEARTBEAT seq=$seq t=${timeFmt.format(Date(tickTime))} monitoringState=$monitoringState " +
                        "pluggedIn=${yn(pluggedIn)} batteryLevel=$batteryLevel% result=OK"
                )
            }
            return
        }

        // Main-process liveness check: if we've received at least one update ever, but
        // none in the last MAIN_PROCESS_TIMEOUT_MS while monitoring should be active,
        // the main process (BatteryReceiver's process) is presumed dead/unresponsive.
        // This is the case a plain in-process monitor could never detect, since it
        // would have died right along with everything else.
        val mainProcessSilent = lastReceiverUpdateAt > 0 &&
            (tickTime - lastReceiverUpdateAt) > MAIN_PROCESS_TIMEOUT_MS

        val receiverShouldCharge = lastKnownShouldCharge
        // Independently derived, using only the raw threshold numbers - NOT BatteryReceiver's
        // internal chargedToLimit/hysteresis state (which this process can't see anyway,
        // now that it's a genuinely separate process). This can legitimately differ from
        // receiverShouldCharge right after crossing a threshold, or in the recharge
        // dead-zone between limit and recharge% while already stopped - expected, not a bug.
        val heartbeatShouldCharge = batteryLevel < limit

        val expected = lastKnownExpectedBit
        val actualBit = lastControlFileBit
        val readResult = lastControlFileReadResult

        val reasons = mutableListOf<String>()
        if (mainProcessSilent) {
            reasons.add("MAIN_PROCESS_UNRESPONSIVE")
        }
        if (!mainProcessSilent && receiverShouldCharge != null && receiverShouldCharge != heartbeatShouldCharge) {
            reasons.add("INTERNAL_LOGIC_MISMATCH")
        }
        if (!mainProcessSilent && receiverShouldCharge != null && receiverShouldCharge != isCharging) {
            reasons.add("DEVICE_STATE_MISMATCH")
        }
        if (expected != null) {
            if (readResult == "ERROR") {
                reasons.add("CONTROL_FILE_READ_ERROR")
            } else if (actualBit != null && actualBit != expected) {
                reasons.add("CONTROL_BIT_MISMATCH")
            }
        }
        val mismatchDetected = reasons.isNotEmpty()
        val reasonStr = reasons.joinToString(",")

        val line = buildHeartbeatLine(
            seq, tickTime, monitoringState, pluggedIn, batteryLevel, limit, recharge, voltage, currentNow,
            receiverShouldCharge, heartbeatShouldCharge, expected, actualBit, readResult, isCharging,
            if (mismatchDetected) "DEVIATION" else "OK"
        )

        when (deviationState) {
            DeviationState.OK -> {
                if (mismatchDetected) {
                    deviationState = DeviationState.PENDING
                    // Anchor the PENDING clock to when the command was actually issued (if
                    // one happened recently), not to whichever tick happened to notice the
                    // mismatch - so "2 seconds" always means the same thing regardless of
                    // sampling-phase luck relative to the 1-second tick. Not applicable for
                    // a main-process-silence finding, which anchors to now (there is no
                    // "command" event to correlate with - the absence itself is the event).
                    deviationSince = if (!mainProcessSilent && lastReceiverUpdateAt > 0 &&
                        tickTime - lastReceiverUpdateAt < COMMAND_CORRELATION_WINDOW_MS
                    ) {
                        lastReceiverUpdateAt
                    } else {
                        tickTime
                    }
                    currentIncidentReason = reasonStr
                    lastCheckpointWriteAt = tickTime
                    Logger.w(TAG, "$line (deviation onset, reason=$currentIncidentReason)")
                } else if (tickTime - lastCheckpointWriteAt >= CHECKPOINT_MS) {
                    lastCheckpointWriteAt = tickTime
                    Logger.i(TAG, line)
                }
            }
            DeviationState.PENDING -> {
                if (!mismatchDetected) {
                    deviationState = DeviationState.OK
                    lastCheckpointWriteAt = tickTime
                    Logger.i(TAG, "$line (deviation cleared before ERROR threshold)")
                } else if (tickTime - deviationSince >= PENDING_TO_ERROR_MS) {
                    deviationState = DeviationState.ERROR
                    lastCheckpointWriteAt = tickTime
                    Logger.e(
                        TAG,
                        "$line (ERROR: deviation persisted >= ${PENDING_TO_ERROR_MS}ms, " +
                            "reason=$currentIncidentReason, firstObserved=${timeFmt.format(Date(deviationSince))})"
                    )
                }
                // else: still inside the pending grace window - onset was already logged, stay quiet
            }
            DeviationState.ERROR -> {
                if (!mismatchDetected) {
                    deviationState = DeviationState.RECOVERY_PENDING
                    recoveryTicksMatched = 1
                } else if (tickTime - lastCheckpointWriteAt >= CHECKPOINT_MS) {
                    lastCheckpointWriteAt = tickTime
                    val durationMs = tickTime - deviationSince
                    Logger.e(
                        TAG,
                        "ERROR_CONTINUES firstObserved=${timeFmt.format(Date(deviationSince))} " +
                            "durationMs=$durationMs reason=$currentIncidentReason $line"
                    )
                }
            }
            DeviationState.RECOVERY_PENDING -> {
                if (!mismatchDetected) {
                    recoveryTicksMatched++
                    if (recoveryTicksMatched >= RECOVERY_TICKS_NEEDED) {
                        deviationState = DeviationState.OK
                        lastCheckpointWriteAt = tickTime
                        val durationMs = tickTime - deviationSince
                        Logger.i(
                            TAG,
                            "RESOLVED firstObserved=${timeFmt.format(Date(deviationSince))} " +
                                "durationMs=$durationMs reason=$currentIncidentReason $line"
                        )
                        currentIncidentReason = ""
                    }
                } else {
                    // flapped back into deviation before recovery was confirmed - back to ERROR
                    deviationState = DeviationState.ERROR
                    recoveryTicksMatched = 0
                }
            }
        }
    }

    private fun requestControlFileRead() {
        if (controlFileReadInFlight) {
            // Previous read hasn't returned yet. That fact alone (a root shell read taking
            // longer than one tick) is itself useful evidence, surfaced via readResult once
            // the in-flight read eventually completes or the correlation window elapses.
            return
        }
        val file = try {
            Utils.getCtrlFileData(service)
        } catch (e: Exception) {
            lastControlFileBit = null
            lastControlFileReadResult = "ERROR"
            return
        }
        controlFileReadInFlight = true
        Shell.cmd("cat $file").submit { result ->
            controlFileReadInFlight = false
            if (result.isSuccess && result.out.isNotEmpty()) {
                lastControlFileBit = result.out[0]
                lastControlFileReadResult = "OK"
            } else {
                lastControlFileBit = null
                lastControlFileReadResult = "ERROR"
            }
        }
    }

    private fun persistLastHeartbeat(t: Long) {
        // SharedPreferences.apply() is asynchronous and batched - "frequent enough"
        // durable evidence without a synchronous disk write every single tick.
        monitorPrefs.edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, t)
            .putLong(KEY_LAST_HEARTBEAT_SEQ, seq)
            .apply()
    }

    private fun yn(b: Boolean) = if (b) "Yes" else "No"
    private fun boolStr(b: Boolean?): String = when (b) {
        true -> "Yes"
        false -> "No"
        null -> "N/A"
    }

    private fun buildHeartbeatLine(
        seq: Long,
        t: Long,
        monitoringState: MonitoringState,
        pluggedIn: Boolean,
        batteryLevel: Int,
        limit: Int,
        recharge: Int,
        voltage: Int,
        currentNow: Int,
        receiverShouldCharge: Boolean?,
        heartbeatShouldCharge: Boolean,
        expectedBit: String?,
        actualBit: String?,
        readResult: String,
        isCharging: Boolean,
        result: String
    ): String {
        val currentStr = if (currentNow == Int.MIN_VALUE) "N/A" else "${currentNow}uA"
        return "HEARTBEAT seq=$seq t=${timeFmt.format(Date(t))} monitoringState=$monitoringState " +
            "pluggedIn=${yn(pluggedIn)} batteryLevel=$batteryLevel% limit=$limit%/$recharge% " +
            "voltage=${voltage}mV currentNow=$currentStr " +
            "receiverShouldCharge=${boolStr(receiverShouldCharge)} heartbeatShouldCharge=${yn(heartbeatShouldCharge)} " +
            "expectedBit=${expectedBit ?: "N/A"} actualBit=${actualBit ?: "N/A"} readResult=$readResult " +
            "isCharging=${yn(isCharging)} result=$result"
    }
}
