package io.github.muntashirakon.bcl

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * A tiny foreground service that exists for exactly one reason: to run
 * ChargeStateMonitor in a SEPARATE OS process (see android:process=":monitor"
 * on this service's manifest entry) from ForegroundService/BatteryReceiver/Utils.
 *
 * Why this exists: an in-process heartbeat dies the instant anything else in that
 * same process throws an uncaught exception - which is precisely the failure mode
 * this whole diagnostic system was built to catch. By running in a different
 * process, a crash in BatteryReceiver (main process) cannot take this down. If the
 * main process disappears, this service simply stops receiving state-update
 * broadcasts from it - and ChargeStateMonitor detects THAT silence itself
 * (MAIN_PROCESS_UNRESPONSIVE) and keeps logging using data it can gather
 * independently (Android's own sticky battery broadcast, its own root shell
 * session for reading the control file).
 *
 * This service does not decide anything about charging and cannot write to the
 * control file's enable/disable path - it is strictly an observer, matching the
 * "heartbeat is diagnostic only, never a second controller" requirement.
 */
class HeartbeatService : Service() {

    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        NotificationManagerCompat.from(this)
    }

    val chargeStateMonitor by lazy(LazyThreadSafetyMode.NONE) { ChargeStateMonitor(this) }

    private var receiverStateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i(TAG, "onCreate() in process=${Utils.currentProcessName(this)}")
        ChargeStateMonitor.checkForHeartbeatGap(this, "HeartbeatServiceStart")

        val channel = NotificationChannelCompat.Builder(
            Constants.HEARTBEAT_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_MIN
        )
            .setName("Charge Diagnostics")
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(channel)

        val flagImmutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, io.github.muntashirakon.bcl.activities.MainActivity::class.java),
            flagImmutable
        )
        val notification = NotificationCompat.Builder(this, Constants.HEARTBEAT_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentTitle(getString(R.string.heartbeat_notification_title))
            .setSmallIcon(R.drawable.ic_notif_charge)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFY_ID, notification)
        } catch (e: Exception) {
            Logger.e(TAG, "HeartbeatService startForeground() failed", e)
        }

        receiverStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val shouldCharge = intent.getBooleanExtra(Constants.EXTRA_SHOULD_CHARGE, true)
                val expectedBit = intent.getStringExtra(Constants.EXTRA_EXPECTED_BIT) ?: return
                chargeStateMonitor.updateReceiverState(shouldCharge, expectedBit)
            }
        }
        val filter = IntentFilter(Constants.ACTION_RECEIVER_STATE_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiverStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiverStateReceiver, filter)
        }

        chargeStateMonitor.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: ask Android to recreate this service if it's killed under memory
        // pressure while charging is still supposed to be monitored. This is a request,
        // not a guarantee - Android can still decide not to, especially soon after a
        // repeated crash - but it's the correct signal to give for a service whose whole
        // purpose is "keep watching for as long as possible".
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy()")
        chargeStateMonitor.stop()
        receiverStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to unregister receiver state listener", e)
            }
        }
        receiverStateReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "HeartbeatService"
        private const val NOTIFY_ID = 2

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HeartbeatService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartbeatService::class.java))
        }
    }
}
