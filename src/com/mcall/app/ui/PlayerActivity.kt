package com.mcall.app.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.mcall.app.R
import com.mcall.app.player.PlaybackEngine
import com.mcall.app.player.PlaybackService
import com.mcall.app.util.Format
import com.mcall.app.util.Insets
import com.mcall.app.util.directionRes
import com.mcall.app.util.title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One UI bottom-sheet player: full transport, scrubbing, ±10s and playback speed. */
class PlayerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var playback: PlaybackService? = null
    private var seeking = false

    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var seek: SeekBar
    private lateinit var tCur: TextView
    private lateinit var tEnd: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var chips: List<Button>

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, svc: IBinder?) {
            playback = (svc as PlaybackService.LocalBinder).service()
            observe()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playback = null
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val w = window
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        w.setGravity(Gravity.BOTTOM)
        w.setBackgroundDrawableResource(android.R.color.transparent)
        w.setDimAmount(0.45f)
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        title = findViewById(R.id.p_title)
        sub = findViewById(R.id.p_sub)
        seek = findViewById(R.id.p_seek)
        tCur = findViewById(R.id.p_cur)
        tEnd = findViewById(R.id.p_end)
        btnPlay = findViewById(R.id.p_play)
        chips = listOf(findViewById(R.id.sp_1), findViewById(R.id.sp_125), findViewById(R.id.sp_15), findViewById(R.id.sp_2))

        Insets.padBottom(findViewById(R.id.sheet))

        findViewById<ImageButton>(R.id.p_close).setOnClickListener { finish() }
        btnPlay.setOnClickListener { playback?.engine?.toggleCurrent() }
        findViewById<ImageButton>(R.id.p_prev).setOnClickListener { playback?.engine?.previous() }
        findViewById<ImageButton>(R.id.p_next).setOnClickListener { playback?.engine?.next() }
        findViewById<ImageButton>(R.id.p_back10).setOnClickListener { playback?.engine?.skipBy(-10_000) }
        findViewById<ImageButton>(R.id.p_fwd10).setOnClickListener { playback?.engine?.skipBy(10_000) }

        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        chips.forEachIndexed { i, chip ->
            chip.setOnClickListener { playback?.engine?.setSpeed(speeds[i]) }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(s: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) {
                seeking = false
                playback?.engine?.seekTo(s?.progress ?: 0)
            }
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tCur.text = Format.duration(p)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PlaybackService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        unbindService(conn)
        playback = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        val svc = playback ?: return
        scope.launch { svc.engine.state.collect { render(it) } }
        render(svc.engine.state.value)
    }

    private fun render(st: PlaybackEngine.State) {
        val rec = st.current ?: return
        title.text = rec.title(this)
        sub.text = "${getString(rec.directionRes())} · ${Format.dateTime(rec.date)} · ${Format.size(rec.sizeBytes)}"
        tEnd.text = Format.duration(st.durationMs)
        if (!seeking) {
            seek.max = st.durationMs.coerceAtLeast(1)
            seek.progress = st.positionMs
            tCur.text = Format.duration(st.positionMs)
        }
        btnPlay.setImageResource(if (st.playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateChips(st.speed)
    }

    private fun updateChips(speed: Float) {
        val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
        chips.forEachIndexed { i, chip ->
            val selected = speeds[i] == speed
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_normal)
            chip.setTextColor(
                getColor(if (selected) R.color.on_accent else R.color.btn_neutral_text)
            )
        }
    }
}
