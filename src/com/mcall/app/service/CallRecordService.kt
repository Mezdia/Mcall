package com.mcall.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
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

/** Foreground auto-recorder: OFFHOOK starts, IDLE encrypts (CVAULT2) into /sdcard/Calls. */
class CallRecordService : Service() {
    companion object {
        const val TAG = "Mcall"
        const val ACT_SELFTEST = "com.mcall.app.SELFTEST"
        @Volatile var running = false
        private const val NOTIF_ID = 41
        private const val CH = "rec"
    }

    private var tm: TelephonyManager? = null
    private var cb: CallCb? = null
    private var legacy: PhoneStateListener? = null
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var rec: MediaRecorder? = null
    private var recording = false
    private var tmpFile: File? = null

    private inner class CallCb : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handleState(state)
    }

    override fun onCreate() {
        super.onCreate()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; service will not start")
            stopSelf()
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Mcall", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_standby))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, n, types)
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground denied", e)
            stopSelf()
            return
        }
        running = true

        tm = getSystemService(TelephonyManager::class.java)
        if (Build.VERSION.SDK_INT >= 31) {
            cb = CallCb()
            try {
                tm?.registerTelephonyCallback({ r -> main.post(r) }, cb!!)
            } catch (e: SecurityException) {
                Log.e(TAG, "telephony permission missing", e)
            }
        } else {
            legacy = object : PhoneStateListener() {
                @Deprecated("legacy path")
                override fun onCallStateChanged(state: Int, num: String?) = handleState(state)
            }
            try {
                @Suppress("DEPRECATION")
                tm?.listen(legacy, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: SecurityException) {
                Log.e(TAG, "telephony permission missing", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_SELFTEST) bg.execute { doSelfTest() }
        return START_STICKY
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK ->
                if (!recording && AppSettings.autoRecord(this)) startRecording()
            TelephonyManager.CALL_STATE_IDLE -> if (recording) stopAndSave(false)
        }
    }

    @Synchronized
    private fun startRecording() {
        try {
            if (AppSettings.encryptNew(this) && PasswordStore(this).load() == null) {
                Log.w(TAG, "encryption on but no password, skip")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "password load failed", e)
            return
        }
        try {
            tmpFile = File.createTempFile("call_", ".m4a", cacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "temp failed", e)
            return
        }
        var r = buildRecorder(tmpFile!!.absolutePath, voiceComm = true)
            ?: buildRecorder(tmpFile!!.absolutePath, voiceComm = false)
        if (r == null) {
            tmpFile?.delete()
            return
        }
        try {
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try {
                Thread.sleep(400)
                r.prepare()
                r.start()
            } catch (e2: Exception) {
                Log.e(TAG, "recorder start failed", e2)
                try { r.release() } catch (ignored: Exception) { }
                r = null
                tmpFile?.delete()
                return
            }
        }
        rec = r
        recording = true
        setNotif(getString(R.string.notif_recording))
    }

    private fun buildRecorder(path: String, voiceComm: Boolean): MediaRecorder? {
        return try {
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
    }

    @Synchronized
    private fun stopAndSave(selfTest: Boolean) {
        if (!recording) return
        recording = false
        val r = rec
        rec = null
        val tmp = tmpFile
        tmpFile = null
        bg.execute {
            try { r?.stop() } catch (e: RuntimeException) { Log.w(TAG, "empty recording", e) }
            try { r?.release() } catch (ignored: Exception) { }
            setNotif(getString(R.string.notif_standby))
            if (tmp == null || !tmp.exists() || tmp.length() == 0L) {
                tmp?.delete()
                return@execute
            }
            try {
                val raw = readAll(tmp)
                wipe(tmp)
                val encrypt = AppSettings.encryptNew(this@CallRecordService)
                val payload: ByteArray = if (encrypt) {
                    val pw = PasswordStore(this@CallRecordService).load()?.current
                    if (pw == null) {
                        Log.w(TAG, "encryption on but no password, dropped")
                        return@execute
                    }
                    VaultCrypto.encrypt(pw, raw)
                } else {
                    raw
                }
                val vault = vaultDir() ?: return@execute
                val stem = if (selfTest) selfTestStem() else callStem()
                val dst = File(vault, stem + if (encrypt) ".m4a.enc" else ".m4a")
                val t2 = File(vault, "$stem.tmp")
                FileOutputStream(t2).use { it.write(payload) }
                t2.renameTo(dst)
                Log.i(TAG, "saved ${dst.absolutePath} (${payload.size}B encrypted=$encrypt)")
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
            }
        }
    }

    private fun doSelfTest() {
        main.post {
            if (!recording) {
                startRecording()
                main.postDelayed({ stopAndSave(true) }, 5000)
            }
        }
    }

    private fun selfTestStem(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "selftest_$ts"
    }

    private fun callStem(): String {
        var dir = "UNK"
        var num = "unknown"
        try {
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
            val cu: Cursor? = contentResolver.query(
                CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC LIMIT 1"
            )
            cu?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrEmpty()) num = n.replace(Regex("[^0-9+]"), "_")
                    dir = when (it.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "IN"
                        CallLog.Calls.OUTGOING_TYPE -> "OUT"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        else -> "UNK"
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "calllog query failed", e)
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${ts}_${dir}_${num}"
    }

    private fun vaultDir(): File? {
        if (!Environment.isExternalStorageManager()) {
            Log.w(TAG, "all-files access missing")
            return null
        }
        val d = File(Environment.getExternalStorageDirectory(), "Calls")
        if (!d.exists() && !d.mkdirs()) {
            Log.e(TAG, "mkdir Calls failed")
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
        try {
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
        } catch (ignored: Exception) { }
        f.delete()
    }

    private fun setNotif(text: String) {
        try {
            val n = Notification.Builder(this, CH)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_mic)
                .setOngoing(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
        } catch (ignored: Exception) { }
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && cb != null) tm?.unregisterTelephonyCallback(cb!!)
            @Suppress("DEPRECATION")
            if (legacy != null) tm?.listen(legacy, PhoneStateListener.LISTEN_NONE)
        } catch (ignored: Exception) { }
        if (recording) {
            try { rec?.stop() } catch (ignored: Exception) { }
            try { rec?.release() } catch (ignored: Exception) { }
            recording = false
        }
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
