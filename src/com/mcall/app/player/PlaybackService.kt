package com.mcall.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import com.mcall.app.R
import com.mcall.app.util.title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the [PlaybackEngine] in a foreground service so encrypted recordings keep
 * playing in the background and expose lock-screen / notification / headset controls.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        const val ACT_TOGGLE = "com.mcall.app.playback.TOGGLE"
        const val ACT_NEXT = "com.mcall.app.playback.NEXT"
        const val ACT_PREV = "com.mcall.app.playback.PREV"
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    lateinit var engine: PlaybackEngine
        private set
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private var lastNotifKey: String = ""
    private var boundCount = 0

    private val idle: Boolean get() = engine.state.value.current == null

    override fun onCreate() {
        super.onCreate()
        engine = PlaybackEngine(this)
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel_playback), NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSession(this, "McallPlayback").apply {
            setCallback(sessionCallback)
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        scope.launch {
            engine.state.collect { st ->
                pushSessionState(st)
                val key = "${st.current?.file?.name}|${st.playing}|${st.loading}"
                if (key != lastNotifKey) {
                    lastNotifKey = key
                    if (st.current != null) {
                        startForeground(NOTIF_ID, buildNotification(st))
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (boundCount == 0) stopSelf()
                    }
                }
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() = engine.resume()
        override fun onPause() = engine.pause()
        override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {}
        override fun onSkipToNext() = engine.next()
        override fun onSkipToPrevious() = engine.previous()
        override fun onSeekTo(pos: Long) = engine.seekTo(pos.toInt())
        override fun onFastForward() = engine.skipBy(10_000)
        override fun onRewind() = engine.skipBy(-10_000)
        override fun onSetPlaybackSpeed(speed: Float) = engine.setSpeed(speed)
        override fun onStop() = engine.stop()
    }

    private fun pushSessionState(st: PlaybackEngine.State) {
        st.current?.let { rec ->
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, rec.title(this@PlaybackService))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs.toLong())
                    .build()
            )
        }
        val stateCode = when {
            st.loading -> PlaybackState.STATE_BUFFERING
            st.playing -> PlaybackState.STATE_PLAYING
            st.current != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setState(stateCode, st.positionMs.toLong(), st.speed)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_SET_PLAYBACK_SPEED or PlaybackState.ACTION_STOP
                )
                .build()
        )
    }

    private fun buildNotification(st: PlaybackEngine.State): Notification {
        val playIcon = if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playTitle = getString(if (st.playing) R.string.btn_pause else R.string.btn_play)
        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wave)
            .setContentTitle(st.current?.title(this) ?: getString(R.string.app_name))
            .setContentText(getString(R.string.notif_playing))
            .setStyle(style)
            .setOngoing(st.playing)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(android.R.drawable.ic_media_previous, getString(R.string.player_prev), pendingAction(ACT_PREV))
            .addAction(playIcon, playTitle, pendingAction(ACT_TOGGLE))
            .addAction(android.R.drawable.ic_media_next, getString(R.string.player_next), pendingAction(ACT_NEXT))
            .build()
    }

    private fun pendingAction(action: String): android.app.PendingIntent {
        val i = Intent(this, PlaybackService::class.java).setAction(action)
        return android.app.PendingIntent.getService(
            this, action.hashCode(), i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> if (engine.isPlaying) engine.pause() else engine.resume()
            ACT_NEXT -> engine.next()
            ACT_PREV -> engine.previous()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        boundCount++
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        boundCount--
        if (idle && boundCount == 0) stopSelf()
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        engine.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        engine.release()
        session.release()
        scope.cancel()
        super.onDestroy()
    }
}
