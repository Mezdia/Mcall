package com.mcall.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.SpannableString
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mcall.app.R
import com.mcall.app.crypto.PasswordStore
import com.mcall.app.crypto.VaultCrypto
import com.mcall.app.data.AppSettings
import com.mcall.app.service.CallRecordService
import com.mcall.app.service.RecordingController
import com.mcall.app.ui.widget.SettingRow
import com.mcall.app.util.Insets
import com.mcall.app.util.OneUiDialog

/** One UI 8.5 grouped settings: subheaders, 58.5dp rows, trailing switch / chevron. */
class SettingsActivity : Activity() {

    companion object {
        const val GITHUB = "https://github.com/Mezdia"
    }

    private lateinit var container: LinearLayout
    private lateinit var rowAuto: SettingRow
    private lateinit var rowEncrypt: SettingRow
    private lateinit var rowPassword: SettingRow
    private lateinit var rowPermCalls: SettingRow
    private lateinit var rowPermFiles: SettingRow
    private lateinit var rowPermBattery: SettingRow
    private lateinit var rowSpeed: SettingRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enableEdgeToEdge(this)
        setContentView(R.layout.activity_settings)
        Insets.padTop(findViewById(R.id.header))

        container = findViewById(R.id.settings_container)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        buildRows()
        setupFooter()
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun subheader(res: Int) {
        val t = TextView(this, null, 0, R.style.Text_Mcall_Subheader)
        t.setText(res)
        container.addView(t)
    }

    private fun addRow(configure: SettingRow.() -> Unit): SettingRow {
        if (container.childCount > 0 && container.getChildAt(container.childCount - 1) is SettingRow) {
            val divider = View(this)
            divider.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.divider_height)
            )
            divider.background = getDrawable(R.drawable.divider_inset)
            container.addView(divider)
        }
        val row = SettingRow(this)
        row.configure()
        container.addView(row)
        return row
    }

    private fun buildRows() {
        subheader(R.string.sec_auto)
        rowAuto = addRow {
            setIcon(R.drawable.ic_mic)
            setTitle(getString(R.string.sw_auto))
            setWidget(SettingRow.Widget.SWITCH)
            switch.isChecked = RecordingController.isEnabled(this@SettingsActivity)
            switch.setOnCheckedChangeListener { _, on ->
                RecordingController.set(this@SettingsActivity, on)
                rowAuto.setSummary(serviceStateLabel())
            }
        }
        addRow {
            setIcon(R.drawable.ic_wave)
            setTitle(getString(R.string.btn_test))
            setSummary(getString(R.string.sum_test))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { runSelfTest() }
        }

        subheader(R.string.sec_encryption)
        rowEncrypt = addRow {
            setIcon(R.drawable.ic_lock)
            setTitle(getString(R.string.sw_encrypt))
            setSummary(getString(R.string.sum_encrypt))
            setWidget(SettingRow.Widget.SWITCH)
            switch.isChecked = AppSettings.encryptNew(this@SettingsActivity)
            switch.setOnCheckedChangeListener { _, on ->
                AppSettings.setEncryptNew(this@SettingsActivity, on)
            }
        }
        rowPassword = addRow {
            setIcon(R.drawable.ic_key)
            setTitle(getString(R.string.sec_password))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { showPasswordDialog() }
        }
        addRow {
            setIcon(R.drawable.ic_info)
            setTitle(getString(R.string.pw_policy_title))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { showPolicyDialog() }
        }

        subheader(R.string.sec_permissions)
        rowPermCalls = addRow {
            setIcon(R.drawable.ic_mic)
            setTitle(getString(R.string.btn_grant_calls))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { requestCallPermissions() }
        }
        rowPermFiles = addRow {
            setIcon(R.drawable.ic_folder)
            setTitle(getString(R.string.btn_grant_files))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { openAllFilesAccess() }
        }
        rowPermBattery = addRow {
            setIcon(R.drawable.ic_battery)
            setTitle(getString(R.string.btn_grant_battery))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { openBatteryOptimization() }
        }

        subheader(R.string.sec_playback)
        rowSpeed = addRow {
            setIcon(R.drawable.ic_speed)
            setTitle(getString(R.string.pref_default_speed))
            setWidget(SettingRow.Widget.CHEVRON)
            setOnClickListener { showSpeedDialog() }
        }

        subheader(R.string.sec_about)
        addRow {
            setIcon(R.drawable.ic_info)
            setTitle(getString(R.string.pref_version))
            setSummary(versionName())
            setWidget(SettingRow.Widget.NONE)
        }
    }

    private fun setupFooter() {
        val footer = findViewById<TextView>(R.id.about_footer)
        val coded = getString(R.string.coded_by, "Mezdia")
        val span = SpannableString(coded)
        val start = coded.indexOf("Mezdia")
        if (start >= 0) {
            span.setSpan(URLSpan(GITHUB), start, start + "Mezdia".length, 0)
        }
        footer.text = span
        footer.movementMethod = LinkMovementMethod.getInstance()
        footer.setLinkTextColor(getColor(R.color.accent))
    }

    private fun refreshSummaries() {
        val secrets = runCatching { PasswordStore(this).load() }.getOrDefault(null)
        rowPassword.setSummary(
            if (secrets == null) getString(R.string.v_not_set)
            else getString(R.string.sum_password_set, secrets.history.size)
        )
        rowAuto.switch.isChecked = RecordingController.isEnabled(this)
        rowAuto.setSummary(serviceStateLabel())
        rowEncrypt.switch.isChecked = AppSettings.encryptNew(this)
        rowEncrypt.setSummary(getString(R.string.sum_encrypt))

        val callsOk = hasPermission(Manifest.permission.RECORD_AUDIO) &&
            hasPermission(Manifest.permission.READ_PHONE_STATE) &&
            hasPermission(Manifest.permission.READ_CALL_LOG)
        rowPermCalls.setSummary(statusLabel(callsOk))
        rowPermFiles.setSummary(statusLabel(Environment.isExternalStorageManager()))
        rowPermBattery.setSummary(statusLabel(isBatteryUnrestricted()))
        rowSpeed.setSummary(speedLabel(AppSettings.defaultSpeed(this)))
    }

    /** Same wording the main screen shows, so the two screens cannot disagree. */
    private fun serviceStateLabel(): String = getString(
        when {
            !RecordingController.isEnabled(this) -> R.string.service_stopped
            CallRecordService.running -> R.string.service_running
            else -> R.string.service_starting
        }
    )

    private fun statusLabel(granted: Boolean) =
        getString(if (granted) R.string.v_granted else R.string.v_not_granted)

    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun speedLabel(f: Float): String =
        if (f == f.toInt().toFloat()) "${f.toInt()}x" else "${f}x"

    // ---------- actions ----------

    private fun runSelfTest() {
        val has = runCatching { PasswordStore(this).has() }.getOrDefault(false)
        if (!has) {
            toast(getString(R.string.set_password_first))
            showPasswordDialog()
            return
        }
        val i = Intent(this, CallRecordService::class.java).setAction(CallRecordService.ACT_SELFTEST)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        toast(getString(R.string.test_started))
    }

    private fun requestCallPermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(need.toTypedArray(), 7)
    }

    private fun openAllFilesAccess() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun openBatteryOptimization() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { toast(it.message ?: "error") }
    }

    private fun showPolicyDialog() {
        OneUiDialog.show(this, R.layout.dialog_confirm) { d ->
            d.findViewById<TextView>(R.id.dlg_title).setText(R.string.pw_policy_title)
            d.findViewById<TextView>(R.id.dlg_message).setText(R.string.pw_policy_body)
            d.findViewById<Button>(R.id.btn_negative).visibility = View.GONE
            d.findViewById<Button>(R.id.btn_positive).setText(R.string.btn_ok)
            d.findViewById<Button>(R.id.btn_positive).setOnClickListener { d.dismiss() }
        }
    }

    /** Reveals or hides a password field, keeping the caret at the end. */
    private fun bindEye(field: EditText, eye: android.widget.ImageButton) {
        var shown = false
        eye.setOnClickListener {
            shown = !shown
            field.inputType = if (shown) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            field.setSelection(field.text.length)
            eye.setImageResource(if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        }
    }

    private fun showPasswordDialog() {
        val secrets = runCatching { PasswordStore(this).load() }.getOrDefault(null)
        OneUiDialog.show(
            this,
            R.layout.dialog_password,
            theme = R.style.Theme_Mcall_InputDialog
        ) { d ->
            val cur = d.findViewById<EditText>(R.id.pw_current)
            val neu = d.findViewById<EditText>(R.id.pw_new)
            val conf = d.findViewById<EditText>(R.id.pw_confirm)
            d.findViewById<View>(R.id.field_current).visibility =
                if (secrets == null) View.GONE else View.VISIBLE
            bindEye(cur, d.findViewById(R.id.eye_current))
            bindEye(neu, d.findViewById(R.id.eye_new))
            bindEye(conf, d.findViewById(R.id.eye_confirm))
            d.findViewById<Button>(R.id.btn_cancel).setOnClickListener { d.dismiss() }
            d.findViewById<Button>(R.id.btn_save).setOnClickListener {
                val current = cur.text.toString()
                val newPw = neu.text.toString()
                val confirm = conf.text.toString()
                when {
                    secrets != null && current != secrets.current ->
                        toast(getString(R.string.err_pw_current_wrong))
                    newPw != confirm -> toast(getString(R.string.err_pw_mismatch))
                    newPw.length < VaultCrypto.MIN_PASSWORD_LEN ->
                        toast(getString(R.string.err_pw_short))
                    else -> {
                        runCatching { PasswordStore(this).store(newPw) }
                            .onSuccess {
                                toast(getString(R.string.msg_pw_changed))
                                d.dismiss()
                                refreshSummaries()
                            }
                            .onFailure { toast(it.message ?: "error") }
                    }
                }
            }
        }
    }

    private fun showSpeedDialog() {
        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        OneUiDialog.show(this, R.layout.dialog_menu) { d ->
            d.findViewById<TextView>(R.id.menu_title).setText(R.string.pref_default_speed)
            val host = d.findViewById<LinearLayout>(R.id.menu_items)
            speeds.forEach { s ->
                val b = Button(this, null, 0, R.style.Widget_Mcall_MenuItem)
                b.text = speedLabel(s)
                b.setOnClickListener {
                    AppSettings.setDefaultSpeed(this, s)
                    d.dismiss()
                    refreshSummaries()
                }
                host.addView(b)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
