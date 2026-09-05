package io.github.muntashirakon.bcl.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.muntashirakon.bcl.Constants
import io.github.muntashirakon.bcl.Logger
import io.github.muntashirakon.bcl.R
import io.github.muntashirakon.bcl.activities.CustomCtrlFileDataActivity

class PrefsFragment : PreferenceFragmentCompat() {

    override fun onDisplayPreferenceDialog(preference: Preference) {
        var dialogFragment: DialogFragment? = null
        if (preference is ControlFilePreference) {
            dialogFragment = ControlFileDialogFragmentCompat.newInstance(preference.key)
        }

        if (dialogFragment != null) {
            val settings = requireContext().getSharedPreferences(Constants.SETTINGS, 0)
            if (!settings.getBoolean("has_opened_ctrl_file", false)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.control_file_heads_up_title)
                    .setMessage(R.string.control_file_heads_up_desc)
                    .setCancelable(false)
                    .setPositiveButton(R.string.control_understand) { _, _ ->
                        settings.edit().putBoolean("has_opened_ctrl_file", true).apply()
                        openControlFileDialogFragment(dialogFragment)
                    }.show()
            } else {
                openControlFileDialogFragment(dialogFragment)
            }
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)

        setHasOptionsMenu(true)

        val theme: ListPreference = findPreference("theme")!!
        val customCtrlFileDataSwitch: SwitchPreferenceCompat = findPreference("custom_ctrl_file_data")!!
        val ctrlFilePreference: ControlFilePreference = findPreference("control_file")!!
        val ctrlFileSetupPreference: Preference = findPreference("custom_ctrl_file_setup")!!

        theme.setOnPreferenceChangeListener { preference, _ ->
            if (preference is ListPreference) {
                requireActivity().recreate()
            }
            true
        }

        customCtrlFileDataSwitch.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                ctrlFilePreference.isEnabled = false
                ctrlFileSetupPreference.isEnabled = true
            } else {
                if (!newValue) {
                    ctrlFilePreference.isEnabled = true
                    ctrlFileSetupPreference.isEnabled = false
                }
            }
            true
        }

        ctrlFileSetupPreference.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.control_file_alert_title)
                .setMessage(R.string.control_file_alert_desc)
                .setCancelable(false)
                .setPositiveButton(R.string.control_understand) { _, _ ->
                    val ctrlFileIntent = Intent(requireContext(), CustomCtrlFileDataActivity::class.java)
                    startActivity(ctrlFileIntent)
                }
                .show()
            true
        }

        if (customCtrlFileDataSwitch.isChecked) {
            ctrlFilePreference.isEnabled = false
            ctrlFileSetupPreference.isEnabled = true
        } else {
            ctrlFilePreference.isEnabled = true
            ctrlFileSetupPreference.isEnabled = false
        }

        val logLocationPreference: Preference? = findPreference("log_location")
        val publicLogPath = Logger.getPublicLogPath()
        if (publicLogPath != null) {
            logLocationPreference?.summary = publicLogPath
        }
        logLocationPreference?.setOnPreferenceClickListener {
            val path = Logger.getPublicLogPath() ?: return@setOnPreferenceClickListener true
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("BCL log path", path))
            Toast.makeText(requireContext(), R.string.log_path_copied, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun openControlFileDialogFragment(dialogFragment: DialogFragment) {
        CtrlFileHelper.validateFiles(requireContext()) {
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(this.parentFragmentManager, ControlFileDialogFragmentCompat::class.java.simpleName)
        }
    }

    companion object {
        const val KEY_CONTROL_FILE = "control_file"
        const val KEY_TEMP_FAHRENHEIT = "temp_fahrenheit"
        const val KEY_IMMEDIATE_POWER_INTENT_HANDLING = "immediate_power_intent_handling"
        const val KEY_NOTIFICATION_SOUND = "notification_sound"
        const val KEY_AUTO_RESET_STATS = "auto_reset_stats"
        const val KEY_ENFORCE_CHARGE_LIMIT = "enforce_charge_limit"
        const val KEY_ALWAYS_WRITE_CF = "always_write_cf"
        const val KEY_DISABLE_AUTO_RECHARGE = "disable_auto_recharge"
        const val KEY_THEME = "theme"
    }
}
