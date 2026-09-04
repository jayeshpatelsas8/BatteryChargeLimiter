package io.github.muntashirakon.bcl.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.textview.MaterialTextView
import io.github.muntashirakon.bcl.BuildConfig
import io.github.muntashirakon.bcl.Logger
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.Utils

/**
 * Very small screen for looking at BCL's debug log without needing adb,
 * root file manager, or a PC: shows the log file's exact on-device path,
 * lets the user share the raw file (e.g. to paste in a GitHub issue or send
 * over chat), copy the path, refresh, or clear it.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var logPathView: MaterialTextView
    private lateinit var logContentView: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        Utils.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.view_logs)

        logPathView = findViewById(R.id.log_path)
        logContentView = findViewById(R.id.log_content)

        findViewById<android.view.View>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<android.view.View>(R.id.btn_copy_path).setOnClickListener { copyPath() }
        findViewById<android.view.View>(R.id.btn_share).setOnClickListener { shareLog() }
        findViewById<android.view.View>(R.id.btn_clear).setOnClickListener { clearLog() }

        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val path = Logger.getLogFilePath() ?: getString(R.string.log_file_location)
        logPathView.text = path
        logContentView.text = Logger.readForDisplay()
    }

    private fun copyPath() {
        val path = Logger.getLogFilePath() ?: return
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
