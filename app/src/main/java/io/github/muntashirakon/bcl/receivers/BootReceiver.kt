package io.github.muntashirakon.bcl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Logger
import io.github.muntashirakon.bcl.Utils

/**
 * Created by Michael on 20.04.2017.
 *
 * Triggered when the phone finished booting.
 * Checks whether power supply is attached and starts the foreground service if necessary.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Logger.i("BootReceiver", "ACTION_BOOT_COMPLETED received")
            Utils.setVoltageThreshold(null, true, context, null)
            Utils.startServiceIfLimitEnabled(context)
            Shell.cmd("cat ${Utils.getVoltageFile()}").submit {
                if (it.out.size != 0) {
                    Utils.getSettings(context).edit().putString(Constants.DEFAULT_VOLTAGE_LIMIT, it.out[0]).apply()
                }
            }
        }
    }
}
