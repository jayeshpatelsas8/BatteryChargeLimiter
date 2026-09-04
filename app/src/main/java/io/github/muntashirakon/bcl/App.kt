package io.github.muntashirakon.bcl

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell

class App: Application() {
    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
            .setFlags(Shell.FLAG_MOUNT_MASTER)
            .setTimeout(10))
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize the file logger as early as possible so it can also
        // catch crashes that happen shortly after process start.
        Logger.init(this)
        Logger.i("App", "Application onCreate")
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
