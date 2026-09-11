package com.mcall.app.crypto

import android.util.Base64
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Vault file codec, byte-compatible with Documents/calls-decrypt/callvault.py.
 * Format: MAGIC(7) + salt(16) + base64url(0x80 || time(8) || iv(16) || ct || hmac(32))
 * V1: PBKDF2-HMAC-SHA256 200k (read only). V2: 600k (read + write).
 */
object VaultCrypto {
    private val MAGIC_V1 = "CVAULT1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "CVAULT2".toByteArray(Charsets.US_ASCII)
    private const val ITER_V1 = 200_000
    private const val ITER_V2 = 600_000
    private const val SALT_LEN = 16
    const val MIN_PASSWORD_LEN = 12

    private fun derive(password: String, salt: ByteArray, iters: Int): ByteArray {
        val kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iters, 256)
        return kf.generateSecret(spec).encoded
    }

    fun encrypt(password: String, plain: ByteArray): ByteArray {
        require(password.length >= MIN_PASSWORD_LEN) { "password too short" }
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(rnd::nextBytes)
        val key = derive(password, salt, ITER_V2)
        val signKey = key.copyOfRange(0, 16)
        val encKey = key.copyOfRange(16, 32)
        val iv = ByteArray(16).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain)
        val body = ByteBuffer.allocate(1 + 8 + 16 + ct.size)
        body.put(0x80.toByte())
        body.putLong(System.currentTimeMillis() / 1000L)
        body.put(iv)
        body.put(ct)
        val bodyBytes = body.array()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signKey, "HmacSHA256"))
        val sig = mac.doFinal(bodyBytes)
        val token = ByteBuffer.allocate(bodyBytes.size + 32)
        token.put(bodyBytes)
        token.put(sig)
        val b64 = Base64.encodeToString(token.array(), Base64.URL_SAFE or Base64.NO_WRAP)
        val tb = b64.toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(MAGIC_V2.size + SALT_LEN + tb.size)
            .put(MAGIC_V2).put(salt).put(tb).array()
    }

    /** Tries every candidate password; returns decrypted bytes with the first that works. */
    fun decryptWithAny(blob: ByteArray, passwords: List<String>): ByteArray {
        var lastErr: Exception? = null
        for (pw in passwords) {
            try {
                return decrypt(blob, pw)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw IllegalArgumentException("wrong password or damaged file", lastErr)
    }

    fun decrypt(blob: ByteArray, password: String): ByteArray {
        val iters = when {
            blob.startsWith(MAGIC_V2) -> ITER_V2
            blob.startsWith(MAGIC_V1) -> ITER_V1
            else -> throw IllegalArgumentException("not a vault file")
        }
        val off = 7
        val salt = blob.copyOfRange(off, off + SALT_LEN)
        val tokenB64 = blob.copyOfRange(off + SALT_LEN, blob.size).toString(Charsets.US_ASCII)
        val raw = Base64.decode(tokenB64, Base64.URL_SAFE)
        require(raw.size > 1 + 8 + 16 + 32) { "damaged file" }
        val body = raw.copyOfRange(0, raw.size - 32)
        val sig = raw.copyOfRange(raw.size - 32, raw.size)
        require(body[0] == 0x80.toByte()) { "damaged file" }
        val key = derive(password, salt, iters)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.copyOfRange(0, 16), "HmacSHA256"))
        require(mac.doFinal(body).contentEquals(sig)) { "wrong password or damaged file" }
        val iv = body.copyOfRange(9, 25)
        val ct = body.copyOfRange(25, body.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOfRange(16, 32), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
