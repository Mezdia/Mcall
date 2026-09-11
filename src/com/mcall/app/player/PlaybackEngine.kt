package com.mcall.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import com.mcall.app.crypto.PasswordStore
import com.mcall.app.crypto.VaultCrypto
import com.mcall.app.data.AppSettings
import com.mcall.app.data.Recording
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decrypts vault recordings into an app-private cache file and plays them there.
 * Plaintext never touches shared storage and is wiped as soon as playback leaves
 * the file, so recordings can only ever be heard through this app.
 */
class PlaybackEngine(private val ctx: Context) {

    data class State(
        val current: Recording? = null,
        val playing: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val speed: Float = 1.0f,
        val loading: Boolean = false,
        val failed: Boolean = false
    )

    companion object {
        private const val TAG = "McallPlayer"
        val SPEEDS = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)
        private const val TICK_MS = 250L
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var temp: File? = null
    private var tempOwned = false
    private var queue: List<Recording> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    fun setQueue(items: List<Recording>) {
        queue = items
    }

    val isPlaying: Boolean get() = _state.value.playing

    fun toggle(rec: Recording) {
        val cur = _state.value.current
        if (cur?.file?.absolutePath == rec.file.absolutePath && player != null) {
            if (_state.value.playing) pause() else resume()
        } else {
            play(rec)
        }
    }

    fun toggleCurrent() {
        _state.value.current?.let { toggle(it) }
    }

    fun play(rec: Recording) {
        teardownPlayer()
        _state.value = State(
            current = rec,
            loading = true,
            speed = AppSettings.defaultSpeed(ctx)
        )
        scope.launch {
            val tmp = withContext(Dispatchers.IO) { decryptToTemp(rec) }
            if (tmp == null) {
                _state.value = _state.value.copy(loading = false, failed = true)
                return@launch
            }
            startMediaPlayer(tmp, rec)
        }
    }

    private suspend fun decryptToTemp(rec: Recording): File? = withContext(Dispatchers.IO) {
        try {
            if (!rec.encrypted) return@withContext rec.file
            val secrets = PasswordStore(ctx).load() ?: return@withContext null
            val blob = rec.file.readBytes()
            val raw = VaultCrypto.decryptWithAny(blob, secrets.all())
            val tmp = File.createTempFile("mcall_play_", ".m4a", ctx.cacheDir)
            FileOutputStream(tmp).use { it.write(raw) }
            raw.fill(0)
            tmp
        } catch (e: Exception) {
            Log.w(TAG, "prepare failed for ${rec.file.name}", e)
            null
        }
    }

    private fun startMediaPlayer(tmp: File, rec: Recording) {
        temp = tmp
        // A plaintext recording is played in place; only decrypted copies are ours to wipe.
        tempOwned = tmp.absolutePath != rec.file.absolutePath
        val mp = MediaPlayer()
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setDataSource(tmp.absolutePath)
        mp.setOnPreparedListener { p ->
            player = p
            applySpeed()
            requestFocus()
            p.start()
            _state.value = _state.value.copy(
                current = rec, loading = false, failed = false,
                playing = true, durationMs = p.duration, positionMs = 0
            )
            startTicker()
        }
        mp.setOnCompletionListener {
            _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs)
            next(auto = true)
        }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "player error what=$what extra=$extra")
            teardownPlayer()
            _state.value = _state.value.copy(loading = false, failed = true, playing = false)
            true
        }
        mp.prepareAsync()
        player = mp
    }

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                val p = player
                if (p != null && _state.value.playing) {
                    val pos = runCatching { p.currentPosition }.getOrDefault(0)
                    _state.value = _state.value.copy(positionMs = pos)
                }
                delay(TICK_MS)
            }
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        _state.value = _state.value.copy(playing = false)
        abandonFocus()
    }

    fun resume() {
        if (player == null) {
            _state.value.current?.let { play(it) }
            return
        }
        requestFocus()
        runCatching { player?.start() }
        _state.value = _state.value.copy(playing = true)
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms.coerceIn(0, _state.value.durationMs)) }
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun skipBy(deltaMs: Int) = seekTo(_state.value.positionMs + deltaMs)

    fun next(auto: Boolean = false) = step(1, auto)
    fun previous() = step(-1, false)

    private fun step(dir: Int, auto: Boolean) {
        val cur = _state.value.current ?: return
        val idx = queue.indexOfFirst { it.file.absolutePath == cur.file.absolutePath }
        if (idx < 0) return
        val target = idx + dir
        if (target !in queue.indices) {
            if (auto) stop()
            return
        }
        play(queue[target])
    }

    fun cycleSpeed(): Float {
        val cur = _state.value.speed
        val idx = SPEEDS.indexOfFirst { it == cur }
        val nextSpeed = SPEEDS[(idx + 1) % SPEEDS.size]
        setSpeed(nextSpeed)
        return nextSpeed
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(speed = speed)
        applySpeed()
    }

    private fun applySpeed() {
        val p = player ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                p.playbackParams = (p.playbackParams ?: PlaybackParams()).setSpeed(_state.value.speed)
            }
        }
    }

    fun stop() {
        teardownPlayer()
        _state.value = State(speed = _state.value.speed)
        abandonFocus()
    }

    fun release() {
        teardownPlayer()
        abandonFocus()
        scope.cancel()
    }

    private fun teardownPlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        wipeTemp()
    }

    private fun wipeTemp() {
        val t = temp
        val owned = tempOwned
        temp = null
        tempOwned = false
        if (t == null || !owned) return
        runCatching {
            val len = t.length()
            FileOutputStream(t).use { fo ->
                val z = ByteArray(8192)
                var w = 0L
                while (w < len) {
                    val k = minOf(z.size.toLong(), len - w).toInt()
                    fo.write(z, 0, k)
                    w += k
                }
            }
        }
        t.delete()
    }

    private fun requestFocus() {
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener { f ->
                    if (f == AudioManager.AUDIOFOCUS_LOSS) pause()
                }
                .build()
        }
        focus?.let { audio?.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        focus?.let { audio?.abandonAudioFocusRequest(it) }
    }
}
