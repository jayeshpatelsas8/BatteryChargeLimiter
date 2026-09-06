package io.github.muntashirakon.bcl.receivers

import android.content.*
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Constants.CHARGING_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.LIMIT
import io.github.muntashirakon.bcl.Constants.MAX_BACK_OFF_TIME
import io.github.muntashirakon.bcl.Constants.MIN
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.POWER_CHANGE_TOLERANCE_MS
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.ForegroundService
import io.github.muntashirakon.bcl.Logger
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils
import io.github.muntashirakon.bcl.settings.PrefsFragment


/**
 * Created by Michael on 01.04.2017.
 *
 * Dynamically created receiver for battery events. Only registered if power supply is attached.
 */
class BatteryReceiver(private val service: ForegroundService) : BroadcastReceiver() {

    private var chargedToLimit = false
    private var useFahrenheit = false
    private var lastState = -1
    private var currentAttempt = 0
    private var limitPercentage: Int = 0
    private var rechargePercentage: Int = 0
    private val prefs = Utils.getPrefs(service.baseContext)
    private var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val settings = service.getSharedPreferences(SETTINGS, 0)
    private var useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)

    init {
        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                PrefsFragment.KEY_TEMP_FAHRENHEIT -> {
                    useFahrenheit = sharedPreferences.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
                    service.setNotificationContentText(
                        Utils.getBatteryInfo(
                            service,
                            service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!,
                            useFahrenheit
                        )
                    )
                    service.updateNotification()
                }
                LIMIT, MIN -> {
                    reset(sharedPreferences)
                }
                PrefsFragment.KEY_NOTIFICATION_SOUND -> {
                    this.useNotificationSound = prefs.getBoolean(PrefsFragment.KEY_NOTIFICATION_SOUND, false)
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        settings.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        this.useFahrenheit = prefs.getBoolean(PrefsFragment.KEY_TEMP_FAHRENHEIT, false)
        reset(settings)
    }

    private fun reset(settings: SharedPreferences) {
        Logger.d(TAG, "reset() limit=${settings.getInt(LIMIT, 80)} min=${settings.getInt(MIN, -1)}")
        chargedToLimit = false
        lastState = -1
        backOffTime = CHARGING_CHANGE_TOLERANCE_MS
        limitPercentage = settings.getInt(LIMIT, 80)
        rechargePercentage = settings.getInt(MIN, limitPercentage - 2)
        Logger.expected(
            TAG,
            "Full charge cycle: charging should START when battery drops to " +
                "$rechargePercentage% or below, and STOP when battery reaches $limitPercentage% or above"
        )
        // manually fire onReceive() to update state if service is enabled
        onReceive(service, service.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!)
    }

    /**
     * Remembers the new state and returns whether the state was changed
     *
     * @param newState the new state
     * @return whether the state has changed
     */
    private fun switchState(newState: Int): Boolean {
        val oldState = lastState
        lastState = newState
        if (oldState != newState) {
            // a genuine new policy decision - retries within this same decision get their
            // own incrementing attempt number, starting fresh for each new decision
            currentAttempt = 0
        }
        return oldState != newState
    }

    /**
     * Issues a CHARGE_ON/CHARGE_OFF command, logs it as a distinct COMMAND event (including
     * an attempt number that increments across retries of the *same* policy decision and
     * resets whenever switchState() records a genuinely new decision), and tells the
     * ChargeStateMonitor what the control file is now expected to contain so it can verify
     * that independently.
     */
    private fun issueChangeState(chargeMode: Int) {
        currentAttempt++
        val expected = if (chargeMode == Utils.CHARGE_ON) {
            Utils.getCtrlEnabledData(service)
        } else {
            Utils.getCtrlDisabledData(service)
        }
        Logger.i(
            TAG,
            "COMMAND action=${if (chargeMode == Utils.CHARGE_ON) "ENABLE" else "DISABLE"} " +
                "attempt=$currentAttempt expectedBit=$expected"
        )
        // HeartbeatService now runs in a SEPARATE OS process (see android:process=":monitor"
        // on its manifest entry) specifically so it survives a crash in this process. That
        // means it can no longer be reached with a plain method call - a broadcast is the
        // standard, reliable cross-process signal for "here is the latest state".
        broadcastStateToMonitor(chargeMode == Utils.CHARGE_ON, expected)
        Utils.changeState(service, chargeMode)
    }

    private fun broadcastStateToMonitor(shouldCharge: Boolean, expectedBitValue: String) {
        try {
            val intent = Intent(Constants.ACTION_RECEIVER_STATE_UPDATE).apply {
                setPackage(service.packageName)
                putExtra(Constants.EXTRA_SHOULD_CHARGE, shouldCharge)
                putExtra(Constants.EXTRA_EXPECTED_BIT, expectedBitValue)
            }
            service.sendBroadcast(intent)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to broadcast state to HeartbeatService", e)
        }
    }

    /**
     * If battery should be charging, but there's no power supply, stop the service.
     * NOT to be called if charging is expected to be disabled!
     */
    private fun stopIfUnplugged() {
        // save the state that caused this function call
        val triggerState = lastState
        handler.postDelayed({
            // continue only if the state didn't change in the meantime
            if (triggerState == lastState && !Utils.isPhonePluggedIn(service)) {
                Logger.i(TAG, "stopIfUnplugged(): device is unplugged, stopping service")
                Utils.stopService(service, false)
            }
        }, POWER_CHANGE_TOLERANCE_MS)
    }

    override fun onReceive(context: Context, intent: Intent) {
        // ignore events while trying to fix charging state, see below
        if (Utils.isChangePending(backOffTime * 2)) {
            Logger.d(TAG, "onReceive() ignored: change pending (backOffTime=$backOffTime)")
            return
        }

        val batteryLevel = Utils.getBatteryLevel(intent)
        val currentStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        Logger.d(
            TAG,
            "onReceive() level=$batteryLevel status=$currentStatus plugged=$plugged " +
                "lastState=$lastState chargedToLimit=$chargedToLimit limit=$limitPercentage recharge=$rechargePercentage"
        )
        // Continuous, human-readable measurement of the whole charge cycle, tick by tick,
        // so the log reads as a full journey rather than only the state-change moments -
        // e.g. "measuring battery=8% ... measuring battery=9% ... battery=10% reached the
        // upper limit, charging must stop now". Compare this against the EXPECTED cycle
        // bounds logged once in reset() above.
        val statusName = when (currentStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN($currentStatus)"
        }
        val positionNote = when {
            batteryLevel >= limitPercentage -> "AT/ABOVE upper limit $limitPercentage% - charging must be OFF"
            batteryLevel <= rechargePercentage -> "AT/BELOW recharge threshold $rechargePercentage% - charging must be ON"
            else -> "between bounds [$rechargePercentage%..$limitPercentage%] - no state change expected"
        }
        Logger.actual(TAG, "measuring battery=$batteryLevel% status=$statusName - $positionNote")

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val showTempInNotif = preferences.getBoolean("temp_in_notif", false)

        try {
            if (!showTempInNotif) {
                service.setNotificationContentText(service.getString(R.string.waiting_description))
            } else {
                service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
            }
            // when the service was "freshly started", charge until limit
            if (!chargedToLimit && batteryLevel < limitPercentage) {
                if (switchState(CHARGE_FULL)) {
                    Logger.i(TAG, "CHARGE_FULL " + this.hashCode())
                    Logger.actual(
                        TAG,
                        "battery=$batteryLevel% is below limit=$limitPercentage% - starting charging now"
                    )
                    Logger.expected(
                        TAG,
                        "Writing CHARGE_ON should let the device draw current, rising from " +
                            "$batteryLevel% toward limit=$limitPercentage%"
                    )
                    issueChangeState(Utils.CHARGE_ON)
                    service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                    service.setNotificationIcon(NOTIF_CHARGE)
                    service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                    stopIfUnplugged()
                }
            } else if (batteryLevel >= limitPercentage) {
                if (switchState(CHARGE_STOP)) {
                    Logger.i(TAG, "CHARGE_STOP " + this.hashCode())
                    Logger.actual(
                        TAG,
                        "battery=$batteryLevel% reached upper limit=$limitPercentage% - charging needs to stop now"
                    )
                    // play sound only the first time when the limit was reached
                    if (useNotificationSound && !chargedToLimit) {
                        service.setNotificationSound()
                    }
                    // remember that we let the device charge until limit at least once
                    chargedToLimit = true
                    // active auto reset on service shutdown
                    service.enableAutoReset()
                    Logger.expected(
                        TAG,
                        "Writing CHARGE_OFF should make the charger stop supplying power now that " +
                            "battery=$batteryLevel% reached limit=$limitPercentage%"
                    )
                    issueChangeState(Utils.CHARGE_OFF)

                    if (preferences.getBoolean(PrefsFragment.KEY_DISABLE_AUTO_RECHARGE, false)) {
                        Utils.stopService(service, false)
                    }

                    // set the "maintain" notification, this must not change from now
                    service.setNotificationTitle(
                        service.getString(R.string.maintaining_x_to_y, rechargePercentage, limitPercentage)
                    )
                    service.setNotificationIcon(NOTIF_MAINTAIN)
                    service.setNotificationActionText(service.getString(R.string.dismiss))
                } else if (currentStatus == BatteryManager.BATTERY_STATUS_CHARGING
                    && prefs.getBoolean(PrefsFragment.KEY_ENFORCE_CHARGE_LIMIT, true)
                ) {
                    //Double the back off time with every unsuccessful round up to MAX_BACK_OFF_TIME
                    backOffTime = (backOffTime * 2).coerceAtMost(MAX_BACK_OFF_TIME)
                    // This is the outcome of the CHARGE_OFF expectation logged above (or of the
                    // previous cycle's expectation, below): the charger was expected to stop
                    // supplying power once the limit was reached, but the battery status
                    // extra still reports CHARGING. Frequent repeats of this ACTUAL line are
                    // the main symptom of the power-bank issue.
                    Logger.actual(
                        TAG,
                        "Still BATTERY_STATUS_CHARGING (instance ${this.hashCode()}) - the control file " +
                            "write did not stop the charger. This is a common symptom on power banks " +
                            "whose control-file state doesn't settle the way it does on a wall charger."
                    )
                    // if the device did not stop charging, try to "cycle" the state to fix this
                    Logger.expected(
                        TAG,
                        "Cycling CHARGE_ON then CHARGE_OFF (after ${backOffTime}ms) should force the " +
                            "control file into the OFF state"
                    )
                    issueChangeState(Utils.CHARGE_ON)
                    // schedule the charging stop command to be executed after CHARGING_CHANGE_TOLERANCE_MS
                    handler.postDelayed({ issueChangeState(Utils.CHARGE_OFF) }, backOffTime)
                } else {
                    backOffTime = CHARGING_CHANGE_TOLERANCE_MS
                }
            } else if (batteryLevel < rechargePercentage) {
                if (switchState(CHARGE_REFRESH)) {
                    Logger.i(TAG, "CHARGE_REFRESH " + this.hashCode())
                    Logger.actual(
                        TAG,
                        "battery=$batteryLevel% dropped to/below recharge threshold=$rechargePercentage% - " +
                            "charging needs to start again now"
                    )
                    service.setNotificationIcon(NOTIF_CHARGE)
                    service.setNotificationTitle(service.getString(R.string.waiting_until_x, limitPercentage))
                    service.setNotificationActionText(service.getString(R.string.disable_temporarily))
                    Logger.expected(
                        TAG,
                        "Writing CHARGE_ON should resume charging now that battery dropped to " +
                            "$batteryLevel% below recharge threshold $rechargePercentage%"
                    )
                    issueChangeState(Utils.CHARGE_ON)
                    stopIfUnplugged()
                }
            }

            // update battery status information and rebuild notification
            // service.setNotificationContentText(Utils.getBatteryInfo(service, intent, useFahrenheit))
            service.updateNotification()
            service.removeNotificationSound()
        } catch (e: Exception) {
            Logger.e(
                TAG,
                "Exception in onReceive() level=$batteryLevel status=$currentStatus plugged=$plugged " +
                    "lastState=$lastState",
                e
            )
            throw e
        }
    }

    fun detach(context: Context) {
        // unregister the listener that listens for relevant change events
        prefs.unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        Utils.getSettings(context)
            .unregisterOnSharedPreferenceChangeListener(this.preferenceChangeListener)
        // technically not necessary, but it prevents inlining of this required field
        // see end of https://developer.android.com/guide/topics/ui/settings.html#Listening
        this.preferenceChangeListener = null
    }

    companion object {
        private const val TAG = "BatteryReceiver"
        private const val CHARGE_FULL = 0
        private const val CHARGE_STOP = 1
        private const val CHARGE_REFRESH = 2

        private val handler = Handler(Looper.getMainLooper())
        internal var backOffTime = CHARGING_CHANGE_TOLERANCE_MS
    }

}
