package com.mcall.app.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.mcall.app.R
import com.mcall.app.crypto.PasswordStore
import com.mcall.app.data.AppSettings
import com.mcall.app.data.Recording
import com.mcall.app.data.RecordingRepository
import com.mcall.app.player.PlaybackEngine
import com.mcall.app.player.PlaybackService
import com.mcall.app.service.RecordingController
import com.mcall.app.util.Format
import com.mcall.app.util.Insets
import com.mcall.app.util.title
import com.mcall.app.util.OneUiDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One UI 8.5 recordings screen: app bar with quick controls, compact status row, list, mini player. */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var listSubheader: TextView
    private lateinit var controlDot: ImageView
    private lateinit var serviceState: TextView
    private lateinit var btnToggle: Button
    private lateinit var noticeCard: View
    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var playerBar: View
    private lateinit var miniTitle: TextView
    private lateinit var miniTime: TextView
    private lateinit var miniPlay: ImageButton

    private lateinit var adapter: RecordingAdapter
    private var playback: PlaybackService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, svc: IBinder?) {
            playback = (svc as PlaybackService.LocalBinder).service()
            playback?.engine?.setQueue(currentItems)
            observePlayback()
            pending?.let { rec ->
                pending = null
                playback?.engine?.play(rec)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
        }
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            syncControlCard()
            ui.postDelayed(this, 1000)
        }
    }

    private var currentItems: List<Recording> = emptyList()

    /** Set when a track is chosen before the playback service has connected. */
    private var pending: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Insets.enableEdgeToEdge(this)
        setContentView(R.layout.activity_main)

        listSubheader = findViewById(R.id.list_subheader)
        controlDot = findViewById(R.id.control_dot)
        serviceState = findViewById(R.id.service_state)
        btnToggle = findViewById(R.id.btn_toggle_service)
        noticeCard = findViewById(R.id.notice_card)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        playerBar = findViewById(R.id.player_bar)
        miniTitle = playerBar.findViewById(R.id.mini_title)
        miniTime = playerBar.findViewById(R.id.mini_time)
        miniPlay = playerBar.findViewById(R.id.mini_play)

        Insets.padTop(findViewById(R.id.header))
        Insets.padBottom(findViewById(R.id.root))

        adapter = RecordingAdapter(this, emptyList()) { showOptions(it) }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> play(adapter.itemAt(pos)) }

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.btn_set_password).setOnClickListener { openSettings() }

        btnToggle.setOnClickListener { toggleRecording() }

        playerBar.findViewById<View>(R.id.mini_open).setOnClickListener { openPlayer() }
        miniPlay.setOnClickListener { playback?.engine?.toggleCurrent() }
        playerBar.findViewById<ImageButton>(R.id.mini_prev).setOnClickListener { playback?.engine?.previous() }
        playerBar.findViewById<ImageButton>(R.id.mini_next).setOnClickListener { playback?.engine?.next() }

        requestRuntimePermissions()
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        unbindService(conn)
        playback = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.postDelayed(statusTicker, 1000)
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- list & status ----------

    private fun refresh() {
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { RecordingRepository.list() }.getOrDefault(emptyList())
            }
            currentItems = items
            playback?.engine?.setQueue(items)
            adapter.update(items)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            listSubheader.text = getString(R.string.sub_recordings_count, items.size)

            val encryptOn = AppSettings.encryptNew(this@MainActivity)
            val hasPassword = runCatching { PasswordStore(this@MainActivity).has() }.getOrDefault(false)
            noticeCard.visibility = if (encryptOn && !hasPassword) View.VISIBLE else View.GONE
            syncControlCard()
        }
    }

    /** Arming drives the button; an actually running recording drives the dot and text. */
    private fun syncControlCard() {
        val wanted = RecordingController.isEnabled(this)
        val live = RecordingController.isRecording(this)
        btnToggle.text = getString(if (wanted) R.string.btn_stop else R.string.btn_start)
        btnToggle.setBackgroundResource(
            if (wanted) R.drawable.bg_button_danger else R.drawable.bg_button_primary
        )
        // The oval is the view's background, so it needs backgroundTint, not a colour filter.
        controlDot.backgroundTintList = ColorStateList.valueOf(
            getColor(if (live) R.color.fn_green else R.color.text_disabled)
        )
        serviceState.text = getString(
            when {
                !wanted -> R.string.state_off
                live -> R.string.state_recording
                else -> R.string.state_armed
            }
        )
    }

    private fun toggleRecording() {
        val enable = !RecordingController.isEnabled(this)
        if (enable && AppSettings.encryptNew(this) && !requirePassword()) return
        RecordingController.set(this, enable)
        syncControlCard()
    }

    // ---------- playback ----------

    private fun play(rec: Recording) {
        if (rec.encrypted && !requirePassword()) return
        val engine = playback?.engine
        if (engine == null) {
            // Binding is asynchronous: remember the request and play it on connect.
            android.util.Log.w("Mcall", "play before service connected; queueing ${rec.file.name}")
            pending = rec
            bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
            return
        }
        engine.setQueue(currentItems)
        engine.play(rec)
    }

    private fun observePlayback() {
        val svc = playback ?: return
        scope.launch { svc.engine.state.collect { renderState(it) } }
    }

    private fun renderState(st: PlaybackEngine.State) {
        val rec = st.current
        if (rec == null) {
            playerBar.visibility = View.GONE
            return
        }
        playerBar.visibility = View.VISIBLE
        miniTitle.text = rec.title(this)
        miniTime.text = Format.positionRange(st.positionMs, st.durationMs)
        miniPlay.setImageResource(if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun openPlayer() {
        if (playback?.engine?.state?.value?.current != null) {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
    }

    // ---------- row options ----------

    private fun showOptions(rec: Recording) {
        OneUiDialog.show(this, R.layout.dialog_menu) { d ->
            d.findViewById<TextView>(R.id.menu_title).text = rec.title(this)
            val host = d.findViewById<LinearLayout>(R.id.menu_items)
            listOf(
                R.string.btn_play to { play(rec) },
                R.string.btn_details to { showDetails(rec) },
                R.string.btn_dial to { dial(rec) },
                R.string.btn_delete to { confirmDelete(rec) }
            ).forEach { (label, action) ->
                val b = Button(this, null, 0, R.style.Widget_Mcall_MenuItem)
                b.setText(label)
                b.setOnClickListener { d.dismiss(); action() }
                host.addView(b)
            }
        }
    }

    private fun showDetails(rec: Recording) {
        OneUiDialog.show(this, R.layout.dialog_confirm) { d ->
            d.findViewById<TextView>(R.id.dlg_title).setText(R.string.btn_details)
            val protection = getString(
                if (rec.encrypted) R.string.protected_on else R.string.protected_off
            )
            d.findViewById<TextView>(R.id.dlg_message).text =
                "${rec.title(this)}\n${Format.dateTime(rec.date)}\n" +
                    "${Format.size(rec.sizeBytes)}\n$protection\n${rec.file.name}"
            d.findViewById<Button>(R.id.btn_negative).visibility = View.GONE
            d.findViewById<Button>(R.id.btn_positive).setText(R.string.btn_ok)
            d.findViewById<Button>(R.id.btn_positive).setOnClickListener { d.dismiss() }
        }
    }

    private fun dial(rec: Recording) {
        if (rec.number.isNotEmpty()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${rec.number}")))
        }
    }

    private fun confirmDelete(rec: Recording) {
        OneUiDialog.show(this, R.layout.dialog_confirm) { d ->
            d.findViewById<TextView>(R.id.dlg_title).setText(R.string.dlg_delete_title)
            d.findViewById<TextView>(R.id.dlg_message).setText(R.string.dlg_delete_msg)
            d.findViewById<Button>(R.id.btn_positive).setOnClickListener {
                if (playback?.engine?.state?.value?.current?.file?.absolutePath == rec.file.absolutePath) {
                    playback?.engine?.stop()
                }
                rec.file.delete()
                d.dismiss()
                refresh()
            }
            d.findViewById<Button>(R.id.btn_negative).setOnClickListener { d.dismiss() }
        }
    }

    // ---------- permissions ----------

    private fun requirePassword(): Boolean {
        val has = runCatching { PasswordStore(this).has() }.getOrDefault(false)
        if (!has) {
            toast(getString(R.string.set_password_first))
            openSettings()
        }
        return has
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun requestRuntimePermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)

        if (!Environment.isExternalStorageManager()) {
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 7
    }
}
