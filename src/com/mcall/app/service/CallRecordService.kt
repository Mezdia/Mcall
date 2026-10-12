package com.mcall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.mcall.app.R
import com.mcall.app.crypto.PasswordStore
import com.mcall.app.crypto.VaultCrypto
import com.mcall.app.data.AppSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records exactly one call and then exits.
 *
 * Nothing here runs between calls: [PhoneStateReceiver] starts this service when a call
 * begins, and the first IDLE state after saving tears it down again — which also removes
 * the notification.
 */
class CallRecordService : Service() {

    companion object {
        const val TAG = "Mcall"
        const val ACT_SELFTEST = "com.mcall.app.SELFTEST"

        @Volatile
        var recordingNow = false
            private set

        private const val NOTIF_ID = 41
        private const val CHANNEL = "rec2"

        /** Backstop in case the IDLE broadcast never arrives. */
        private const val IDLE_TIMEOUT_MS = 45_000L
    }

    private var tm: TelephonyManager? = null
    private var cb: CallCb? = null
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    private var recorder: MediaRecorder? = null
    private var recording = false
    private var tmpFile: File? = null
    private var selfTest = false
    private var finished = false

    private inner class CallCb : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> if (!selfTest) startRecording()
                TelephonyManager.CALL_STATE_IDLE -> finishAfterCall()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; not starting")
            stopSelf()
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN)
            )
        }
        // specialUse rather than microphone: microphone is a while-in-use type and Android
        // rejects it when the service was woken by a background broadcast. phoneCall would
        // require the dialer role. No actions and no CALL category, so the notification stays
        // deferrable and a short call may never display one.
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground rejected", e)
            stopSelf()
            return
        }
        recordingNow = true

        tm = getSystemService(TelephonyManager::class.java)
        cb = CallCb()
        runCatching { tm?.registerTelephonyCallback({ r -> main.post(r) }, cb!!) }
            .onFailure { Log.e(TAG, "telephony callback failed", it) }

        // The callback reports changes only, so the call that woke us has to be read directly.
        when (runCatching { tm?.callState }.getOrNull() ?: TelephonyManager.CALL_STATE_IDLE) {
            TelephonyManager.CALL_STATE_OFFHOOK -> startRecording()
            TelephonyManager.CALL_STATE_RINGING -> Log.i(TAG, "ringing; waiting for answer")
            else -> Log.w(TAG, "started outside a call")
        }
        main.postDelayed({ if (!selfTest) finishAfterCall() }, IDLE_TIMEOUT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_SELFTEST) {
            selfTest = true
            main.post { runSelfTest() }
        }
        // The system must not resurrect this; the receiver starts it per call instead.
        return START_NOT_STICKY
    }

    @Synchronized
    private fun startRecording() {
        if (recording) return
        if (AppSettings.encryptNew(this) && runCatching { PasswordStore(this).load() }.getOrNull() == null) {
            Log.w(TAG, "encryption on but no password set; skipping this call")
            finishAfterCall()
            return
        }
        val tmp = runCatching { File.createTempFile("call_", ".m4a", cacheDir) }
            .getOrElse { Log.e(TAG, "temp file failed", it); return }
        tmpFile = tmp
        val r = buildRecorder(tmp.absolutePath, true) ?: buildRecorder(tmp.absolutePath, false)
        if (r == null) {
            tmp.delete()
            finishAfterCall()
            return
        }
        if (!startOrRetry(r)) {
            runCatching { r.release() }
            tmp.delete()
            tmpFile = null
            finishAfterCall()
            return
        }
        recorder = r
        recording = true
        setNotifText(getString(R.string.notif_recording))
    }

    private fun startOrRetry(r: MediaRecorder): Boolean {
        if (runCatching { r.prepare(); r.start() }.isSuccess) return true
        runCatching { Thread.sleep(400) }
        return runCatching { r.prepare(); r.start() }.also {
            if (it.isFailure) Log.e(TAG, "recorder start failed", it.exceptionOrNull())
        }.isSuccess
    }

    private fun buildRecorder(path: String, voiceComm: Boolean): MediaRecorder? = try {
        MediaRecorder().apply {
            setAudioSource(
                if (voiceComm) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC
            )
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(path)
        }
    } catch (e: Exception) {
        Log.e(TAG, "buildRecorder vc=$voiceComm failed", e)
        null
    }

    /** Stops the recorder, saves what it captured, then shuts the service down. */
    @Synchronized
    private fun finishAfterCall() {
        if (finished) return
        finished = true
        val r = recorder
        val tmp = tmpFile
        val wasRecording = recording
        recorder = null
        tmpFile = null
        recording = false
        bg.execute {
            if (wasRecording) {
                runCatching { r?.stop() }
                    .onFailure { Log.w(TAG, "nothing recorded", it) }
            }
            runCatching { r?.release() }
            if (tmp != null && tmp.exists() && tmp.length() > 0L) save(tmp) else tmp?.delete()
            main.post { stopSelf() }
        }
    }

    private fun save(tmp: File) {
        try {
            val raw = readAll(tmp)
            wipe(tmp)
            val encrypt = AppSettings.encryptNew(this)
            val payload = if (encrypt) {
                val pw = runCatching { PasswordStore(this).load()?.current }.getOrNull()
                if (pw == null) {
                    Log.w(TAG, "encryption on but no password; file dropped")
                    return
                }
                VaultCrypto.encrypt(pw, raw)
            } else raw
            val vault = vaultDir() ?: return
            val stem = if (selfTest) "selftest_${timestamp()}" else callStem()
            val dst = File(vault, stem + if (encrypt) ".m4a.enc" else ".m4a")
            val part = File(vault, "$stem.tmp")
            FileOutputStream(part).use { it.write(payload) }
            if (!part.renameTo(dst)) {
                Log.e(TAG, "rename failed for ${dst.name}")
                part.delete()
            } else {
                Log.i(TAG, "saved ${dst.absolutePath} (${payload.size}B encrypted=$encrypt)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    private fun runSelfTest() {
        startRecording()
        main.postDelayed({ finishAfterCall() }, 5000)
    }

    private fun callStem(): String {
        var dir = "UNK"
        var num = "unknown"
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                null, null, CallLog.Calls.DATE + " DESC LIMIT 1"
            )?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.takeIf { s -> s.isNotEmpty() }?.let { s ->
                        num = s.replace(Regex("[^0-9+]"), "_")
                    }
                    dir = when (it.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "IN"
                        CallLog.Calls.OUTGOING_TYPE -> "OUT"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "UNK"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "call log query failed", e)
        }
        return "${timestamp()}_${dir}_${num}"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun vaultDir(): File? {
        if (!Environment.isExternalStorageManager()) {
            Log.w(TAG, "all-files access missing")
            return null
        }
        val d = File(Environment.getExternalStorageDirectory(), "Calls")
        if (!d.exists() && !d.mkdirs()) {
            Log.e(TAG, "cannot create Calls folder")
            return null
        }
        return d
    }

    private fun readAll(f: File): ByteArray {
        FileInputStream(f).use { fi ->
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var n: Int
            while (fi.read(buf).also { n = it } > 0) bo.write(buf, 0, n)
            return bo.toByteArray()
        }
    }

    private fun wipe(f: File) {
        runCatching {
            val len = f.length()
            FileOutputStream(f).use { fo ->
                val z = ByteArray(8192)
                var w = 0L
                while (w < len) {
                    val k = minOf(z.size.toLong(), len - w).toInt()
                    fo.write(z, 0, k)
                    w += k
                }
            }
        }
        f.delete()
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_recording))
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

    private fun setNotifText(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(
                NOTIF_ID,
                Notification.Builder(this, CHANNEL)
                    .setContentTitle(text)
                    .setContentText(getString(R.string.app_name))
                    .setSmallIcon(R.drawable.ic_mic)
                    .setOngoing(true)
                    .build()
            )
        }
    }

    override fun onDestroy() {
        runCatching { cb?.let { tm?.unregisterTelephonyCallback(it) } }
        if (recording) {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recording = false
        }
        main.removeCallbacksAndMessages(null)
        bg.shutdown()
        recordingNow = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
