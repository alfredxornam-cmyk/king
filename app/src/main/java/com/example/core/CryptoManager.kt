package com.example.core

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM encryption engine using PBKDF2WithHmacSHA256 key derivation.
 * Encrypts sensitive task titles, voice notes, and media vault payloads before Firestore sync,
 * enabling seamless multi-device decryption for the signed-in Google account.
 */
class CryptoManager {

    companion object {
        private const val CIPHER_PREFIX = "AGENC1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
        private const val PBKDF2_ITERATIONS = 12_000
        private const val KEY_LENGTH_BITS = 256
    }

    private val random = SecureRandom()
    private var cachedCacheKey: String? = null
    private var cachedSecretKey: SecretKey? = null

    fun generateSaltBase64(): String {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun resolvePassphrase(userId: String, customPassphrase: String?): String {
        return if (!customPassphrase.isNullOrBlank()) {
            customPassphrase
        } else {
            "alfred-vault-default-$userId"
        }
    }

    private fun deriveKey(passphrase: String, saltBase64: String): SecretKey {
        val cacheId = "$passphrase|$saltBase64"
        val existing = cachedSecretKey
        if (existing != null && cachedCacheKey == cacheId) {
            return existing
        }
        val saltBytes = try {
            Base64.decode(saltBase64, Base64.DEFAULT)
        } catch (_: Exception) {
            saltBase64.toByteArray(Charsets.UTF_8)
        }
        val spec = PBEKeySpec(passphrase.toCharArray(), saltBytes, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded
        val secretKey = SecretKeySpec(derived, "AES")
        cachedCacheKey = cacheId
        cachedSecretKey = secretKey
        return secretKey
    }

    fun encryptText(plainText: String, passphrase: String, saltBase64: String): String {
        if (plainText.isEmpty()) return ""
        val key = deriveKey(passphrase, saltBase64)
        val encrypted = encryptBytes(plainText.toByteArray(Charsets.UTF_8), key)
        return CIPHER_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptText(cipherOrPlain: String, passphrase: String, saltBase64: String): String {
        if (cipherOrPlain.isEmpty()) return ""
        if (!cipherOrPlain.startsWith(CIPHER_PREFIX)) return cipherOrPlain
        return try {
            val payload = Base64.decode(cipherOrPlain.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            val key = deriveKey(passphrase, saltBase64)
            String(decryptBytes(payload, key), Charsets.UTF_8)
        } catch (_: Exception) {
            "[Locked — Check Vault Passphrase]"
        }
    }

    fun encryptBytesToPayload(rawBytes: ByteArray, passphrase: String, saltBase64: String): String {
        val key = deriveKey(passphrase, saltBase64)
        val encrypted = encryptBytes(rawBytes, key)
        return CIPHER_PREFIX + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    fun decryptPayloadToBytes(cipherPayload: String, passphrase: String, saltBase64: String): ByteArray? {
        if (!cipherPayload.startsWith(CIPHER_PREFIX)) return null
        return try {
            val payload = Base64.decode(cipherPayload.removePrefix(CIPHER_PREFIX), Base64.NO_WRAP)
            val key = deriveKey(passphrase, saltBase64)
            decryptBytes(payload, key)
        } catch (_: Exception) {
            null
        }
    }

    private fun encryptBytes(data: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val cipherText = cipher.doFinal(data)
        return iv + cipherText
    }

    private fun decryptBytes(ivAndCipher: ByteArray, key: SecretKey): ByteArray {
        require(ivAndCipher.size > IV_LENGTH_BYTES) { "Invalid cipher payload" }
        val iv = ivAndCipher.copyOfRange(0, IV_LENGTH_BYTES)
        val cipherText = ivAndCipher.copyOfRange(IV_LENGTH_BYTES, ivAndCipher.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(cipherText)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
