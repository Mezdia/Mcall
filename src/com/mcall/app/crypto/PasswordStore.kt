package com.mcall.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vault passwords, wrapped by a device-bound AndroidKeyStore AES-GCM key.
 * Keeps the current password plus a short history of previous ones so that
 * files encrypted before a password change still play inside the app,
 * while outside the app each file needs the password of its own era.
 */
class PasswordStore(private val ctx: Context) {
    companion object {
        private const val PREF = "vault_sec2"
        private const val ALIAS = "mcall_master"
        private const val BLOB = "pw_json"
        private const val MAX_HISTORY = 5
    }

    data class Secrets(val current: String, val history: List<String>) {
        fun all(): List<String> = listOf(current) + history
    }

    fun has(): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(BLOB, null) != null

    fun load(): Secrets? {
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(BLOB, null)
            ?: return null
        val all = Base64.decode(s, Base64.NO_WRAP)
        val b = ByteBuffer.wrap(all)
        val n = b.int
        val iv = ByteArray(n); b.get(iv)
        val ct = ByteArray(b.remaining()); b.get(ct)
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val key = ks.getKey(ALIAS, null) as? SecretKey ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val json = JSONObject(String(c.doFinal(ct), Charsets.UTF_8))
        val hist = mutableListOf<String>()
        val arr = json.optJSONArray("history") ?: JSONArray()
        for (i in 0 until arr.length()) hist.add(arr.getString(i))
        return Secrets(json.getString("current"), hist)
    }

    fun store(password: String) {
        require(password.length >= VaultCrypto.MIN_PASSWORD_LEN) { "password too short" }
        val prev = try { load() } catch (e: Exception) { null }
        val hist = (prev?.let { listOf(it.current) + it.history } ?: emptyList())
            .filter { it != password }
            .take(MAX_HISTORY)
        val json = JSONObject()
            .put("current", password)
            .put("history", JSONArray(hist))
        persist(json.toString())
    }

    private fun persist(json: String) {
        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            kg.generateKey()
        }
        val key = ks.getKey(ALIAS, null) as SecretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val ct = c.doFinal(json.toByteArray(Charsets.UTF_8))
        val iv = c.iv
        val b = ByteBuffer.allocate(4 + iv.size + ct.size)
        b.putInt(iv.size); b.put(iv); b.put(ct)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(BLOB, Base64.encodeToString(b.array(), Base64.NO_WRAP)).apply()
    }
}
