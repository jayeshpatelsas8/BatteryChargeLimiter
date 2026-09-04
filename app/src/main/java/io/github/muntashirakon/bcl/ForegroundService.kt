package io.github.muntashirakon.bcl

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.muntashirakon.bcl.Constants.INTENT_DISABLE_ACTION
import io.github.muntashirakon.bcl.Constants.NOTIFICATION_LIVE
import io.github.muntashirakon.bcl.Constants.NOTIF_CHARGE
import io.github.muntashirakon.bcl.Constants.NOTIF_MAINTAIN
import io.github.muntashirakon.bcl.Constants.SETTINGS
import io.github.muntashirakon.bcl.activities.MainActivity
import io.github.muntashirakon.bcl.receivers.BatteryReceiver
import io.github.muntashirakon.bcl.receivers.ControlBatteryChargeReceiver
import io.github.muntashirakon.bcl.settings.PrefsFragment


/**
 * Created by harsha on 30/1/17.
 *
 * This is a Service that shows the notification about the current charging state
 * and supplies the context to the BatteryReceiver it is registering.
 *
 * 24/4/17 milux: Changed to make "restart" more efficient by avoiding the need to stop the service
 */
class ForegroundService : Service() {

    private val settings by lazy(LazyThreadSafetyMode.NONE) { this.getSharedPreferences(SETTINGS, 0) }
    private val prefs by lazy(LazyThreadSafetyMode.NONE) { Utils.getPrefs(this) }
    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        NotificationManagerCompat.from(this)

    }
    private val mNotifyBuilder by lazy(LazyThreadSafetyMode.NONE) {
        NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID)
    }
    private var notifyID = 1
    private var autoResetActive = false
    private var batteryReceiver: BatteryReceiver? = null

    /**
     * Enables the automatic reset on service shutdown
     */
    fun enableAutoReset() {
        autoResetActive = true
    }

    override fun onCreate() {
        Logger.i(TAG, "onCreate() pluggedIn=${Utils.isPhonePluggedIn(this)}")
        isRunning = true

        settings.edit().putBoolean(NOTIFICATION_LIVE, true).apply()

        val channel = NotificationChannelCompat.Builder(
            Constants.FOREGROUND_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName("Charge Limit Status")
            .build()
        notificationManager.createNotificationChannel(channel)

        val notification = mNotifyBuilder
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SYSTEM)
            .setContentTitle(getString(R.string.please_wait))
            .setContentInfo(getString(R.string.please_wait))
            .setSmallIcon(R.drawable.ic_notif_charge)
            .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
            .build()
        try {
            startForeground(notifyID, notification)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException on some OEM/Android
            // versions if the process was restarted in the background
            Logger.e(TAG, "startForeground() failed", e)
            throw e
        }

        try {
            batteryReceiver = BatteryReceiver(this@ForegroundService)
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create/register BatteryReceiver", e)
            throw e
        }
        Logger.i(TAG, "onCreate() finished successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.i(TAG, "onStartCommand() startId=$startId flags=$flags action=${intent?.action}")
        ignoreAutoReset = false
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w(TAG, "onTaskRemoved() - app task was removed/swiped away")
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        Logger.w(TAG, "onLowMemory()")
        super.onLowMemory()
    }

    fun setNotificationActionText(actionText: String) {
        mNotifyBuilder.clearActions()
        val flagImmutable: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntentOpenApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flagImmutable)
        val pendingIntentDisable = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, ControlBatteryChargeReceiver::class.java).setAction(INTENT_DISABLE_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )
        mNotifyBuilder.addAction(0, actionText, pendingIntentDisable)
            .addAction(0, getString(R.string.open_app), pendingIntentOpenApp)
    }

    fun setNotificationTitle(title: String) {
        mNotifyBuilder.setContentTitle(title)
    }

    fun setNotificationContentText(contentText: String) {
        mNotifyBuilder.setContentText(contentText)
    }

    fun setNotificationIcon(iconType: String) {
        if (iconType == NOTIF_MAINTAIN) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_maintain)
        } else if (iconType == NOTIF_CHARGE) {
            mNotifyBuilder.setSmallIcon(R.drawable.ic_notif_charge)
        }
    }

    fun updateNotification() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // No permission
            return
        }
        notificationManager.notify(notifyID, mNotifyBuilder.build())
    }

    fun setNotificationSound() {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        mNotifyBuilder.setSound(soundUri)
    }

    fun removeNotificationSound() {
        mNotifyBuilder.setSound(null)
    }

    override fun onDestroy() {
        Logger.i(
            TAG,
            "onDestroy() autoResetActive=$autoResetActive ignoreAutoReset=$ignoreAutoReset " +
                "pluggedIn=${Utils.isPhonePluggedIn(this)}"
        )
        try {
            if (autoResetActive && !ignoreAutoReset && prefs.getBoolean(PrefsFragment.KEY_AUTO_RESET_STATS, false)) {
                Utils.resetBatteryStats(this)
            }
            ignoreAutoReset = false

            settings.edit().putBoolean(NOTIFICATION_LIVE, false).apply()
            // unregister the battery event receiver
            unregisterReceiver(batteryReceiver)

            // make the BatteryReceiver and dependencies ready for garbage-collection
            batteryReceiver!!.detach(this)
            // clear the reference to the battery receiver for GC
            batteryReceiver = null
        } catch (e: Exception) {
            Logger.e(TAG, "Exception during onDestroy()", e)
        } finally {
            isRunning = false
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        private const val TAG = "ForegroundService"

        /**
         * Returns whether the service is running right now
         *
         * @return Whether service is running
         */
        var isRunning = false
        private var ignoreAutoReset = false

        /**
         * Ignore the automatic reset when service is shut down the next time
         */
        internal fun ignoreAutoReset() {
            ignoreAutoReset = true
        }
    }
}
