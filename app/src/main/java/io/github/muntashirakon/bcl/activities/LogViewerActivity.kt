package io.github.muntashirakon.bcl.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.textview.MaterialTextView
import com.topjohnwu.superuser.Shell
import io.github.muntashirakon.bcl.BuildConfig
import io.github.muntashirakon.bcl.Logger
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils

/**
 * Screen for looking at BCL's debug log without needing adb, a root file
 * manager, or a PC. Shows the log's PUBLIC on-device path (the one visible
 * to any ordinary file manager, not the app-internal one), and lets the
 * user refresh, force a sync to the public copy, copy the path, share the
 * raw file, or clear it - all via the toolbar's overflow menu so nothing
 * overlaps the system navigation bar at the bottom of the screen.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var logPathView: MaterialTextView
    private lateinit var logRootHintView: MaterialTextView
    private lateinit var logContentView: MaterialTextView
    private lateinit var logScrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.view_logs)

        logPathView = findViewById(R.id.log_path)
        logRootHintView = findViewById(R.id.log_root_hint)
        logContentView = findViewById(R.id.log_content)
        logScrollView = findViewById(R.id.log_scroll)

        refresh()
        checkRootStatus()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_log_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.log_action_refresh -> refresh()
            R.id.log_action_sync -> syncNow()
            R.id.log_action_copy_path -> copyPath()
            R.id.log_action_share -> shareLog()
            R.id.log_action_clear -> clearLog()
        }
        return super.onOptionsItemSelected(item)
    }

    /** Checks (asynchronously, never blocking the UI) whether root is currently granted. */
    private fun checkRootStatus() {
        Shell.getShell { shell ->
            runOnUiThread {
                logRootHintView.visibility = if (shell.isRoot) View.GONE else View.VISIBLE
            }
        }
    }

    private fun refresh() {
        val publicPath = Logger.getPublicLogPath()
        logPathView.text = publicPath ?: Logger.getLogFilePath() ?: getString(R.string.log_file_location)
        logContentView.text = Logger.readForDisplay()
        // jump to the most recent entries rather than making the user scroll down manually
        logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun syncNow() {
        Logger.forcePublish()
        Toast.makeText(this, R.string.log_sync_started, Toast.LENGTH_SHORT).show()
        // give the root shell call a moment to complete before refreshing the displayed path
        logContentView.postDelayed({ refresh() }, 800)
    }

    private fun copyPath() {
        val path = Logger.getPublicLogPath() ?: Logger.getLogFilePath() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BCL log path", path))
        Toast.makeText(this, R.string.log_path_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val file = Logger.getLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.log_share_failed, "no log file yet"), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.log_share_chooser_title)))
        } catch (e: Exception) {
            Logger.e("LogViewerActivity", "Failed to share log file", e)
            Toast.makeText(this, getString(R.string.log_share_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLog() {
        Logger.clear()
        // clear() runs on a background thread; give it a beat before refreshing the view
        logContentView.postDelayed({ refresh() }, 150)
        Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
    }
}
