package com.mcall.app.data

import android.os.Environment
import java.io.File

/** Scans the shared /sdcard/Calls folder for recordings (encrypted and plain). */
object RecordingRepository {

    fun vaultDir(): File = File(Environment.getExternalStorageDirectory(), "Calls")

    fun isReadable(): Boolean = Environment.isExternalStorageManager()

    fun list(): List<Recording> {
        val dir = vaultDir()
        if (!isReadable() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && !f.name.endsWith(".tmp") &&
                (f.name.endsWith(".m4a.enc") || f.name.endsWith(".m4a"))
        }?.map { Recording.parse(it) }
            ?.sortedByDescending { it.date }
            ?: emptyList()
    }
}
