package app.lecturevault.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun getGroqKey(): String? = get(GROQ_API_KEY)

    fun getGeminiKey(): String? = get(GEMINI_API_KEY)

    fun saveKeys(groqKey: String, geminiKey: String) = synchronized(secretLock) {
        val normalizedGroqKey = requireSecretValue(groqKey)
        val normalizedGeminiKey = requireSecretValue(geminiKey)
        val envelopes = encryptWithFreshKeyRetry(
            mapOf(
                GROQ_API_KEY to normalizedGroqKey,
                GEMINI_API_KEY to normalizedGeminiKey,
            ),
        )
        persistEnvelopes(envelopes)
    }

    fun put(name: String, value: String) = synchronized(secretLock) {
        requireSupportedName(name)
        val envelope = encryptWithFreshKeyRetry(mapOf(name to requireSecretValue(value))).getValue(name)
        persistEnvelopes(mapOf(name to envelope))
    }

    fun get(name: String): String? = synchronized(secretLock) {
        requireSupportedName(name)
        val envelope = readEnvelope(name) ?: return@synchronized null
        val key = try {
            existingKey()
        } catch (error: GeneralSecurityException) {
            clearInvalidatedState()
            return@synchronized null
        } catch (error: RuntimeException) {
            if (!error.hasInvalidatedKeyCause()) throw error
            clearInvalidatedState()
            return@synchronized null
        }
        if (key == null) {
            clearInvalidatedState()
            return@synchronized null
        }

        try {
            val decrypted = decrypt(name, envelope, key)
            val normalized = normalizeApiCredential(decrypted)
            if (normalized.isEmpty()) {
                removeEnvelopes(setOf(name))
                null
            } else {
                // Repair credentials pasted with an embedded line break or tab and
                // immediately replace the old encrypted envelope on disk.
                if (normalized != decrypted) {
                    persistEnvelopes(mapOf(name to encrypt(name, normalized, key)))
                }
                normalized
            }
        } catch (error: GeneralSecurityException) {
            clearInvalidatedState()
            null
        } catch (error: RuntimeException) {
            if (error.hasInvalidatedKeyCause()) {
                clearInvalidatedState()
                null
            } else {
                throw error
            }
        }
    }

    fun delete(name: String) = synchronized(secretLock) {
        requireSupportedName(name)
        removeEnvelopes(setOf(name))
    }

    fun clear() = synchronized(secretLock) {
        removeEnvelopes(SUPPORTED_NAMES)
        runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }.getOrElse { error ->
            throw SecurityException("Could not clear the AndroidKeyStore entry", error)
        }
    }

    private fun encryptWithFreshKeyRetry(values: Map<String, String>): Map<String, SecretEnvelope> {
        return try {
            val key = getOrCreateKey()
            values.mapValues { (name, value) -> encrypt(name, value, key) }
        } catch (error: GeneralSecurityException) {
            if (!error.hasInvalidatedKeyCause()) throw SecurityException("Could not encrypt API credentials", error)
            clearInvalidatedState()
            val key = getOrCreateKey()
            values.mapValues { (name, value) -> encrypt(name, value, key) }
        } catch (error: RuntimeException) {
            if (!error.hasInvalidatedKeyCause()) throw error
            clearInvalidatedState()
            val key = getOrCreateKey()
            values.mapValues { (name, value) -> encrypt(name, value, key) }
        }
    }

    private fun encrypt(name: String, value: String, key: SecretKey): SecretEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(additionalAuthenticatedData(name))
        val plainBytes = value.toByteArray(StandardCharsets.UTF_8)
        return try {
            SecretEnvelope(
                version = ENVELOPE_VERSION,
                iv = cipher.iv.copyOf(),
                ciphertext = cipher.doFinal(plainBytes),
            )
        } finally {
            plainBytes.fill(0)
        }
    }

    private fun decrypt(name: String, envelope: SecretEnvelope, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, envelope.iv),
        )
        cipher.updateAAD(additionalAuthenticatedData(name))
        val plainBytes = cipher.doFinal(envelope.ciphertext)
        return try {
            plainBytes.toString(StandardCharsets.UTF_8)
        } finally {
            plainBytes.fill(0)
        }
    }

    private fun existingKey(): SecretKey? = keyStore.getKey(KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        if (hasStoredEnvelope()) removeEnvelopes(SUPPORTED_NAMES)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun hasStoredEnvelope(): Boolean = SUPPORTED_NAMES.any { name ->
        val keys = preferenceKeys(name)
        preferences.contains(keys.version) ||
            preferences.contains(keys.iv) ||
            preferences.contains(keys.ciphertext)
    }

    private fun readEnvelope(name: String): SecretEnvelope? {
        val keys = preferenceKeys(name)
        val anyPresent = preferences.contains(keys.version) ||
            preferences.contains(keys.iv) ||
            preferences.contains(keys.ciphertext)
        if (!anyPresent) return null

        val envelope = try {
            val version = preferences.getInt(keys.version, -1)
            val iv = preferences.getString(keys.iv, null)
            val ciphertext = preferences.getString(keys.ciphertext, null)
            SecretEnvelopeCodec.decode(version, iv, ciphertext)
        } catch (_: ClassCastException) {
            null
        }
        if (envelope == null) removeEnvelopes(setOf(name))
        return envelope
    }

    private fun persistEnvelopes(envelopes: Map<String, SecretEnvelope>) {
        val editor = preferences.edit()
        envelopes.forEach { (name, envelope) ->
            val keys = preferenceKeys(name)
            editor
                .putInt(keys.version, envelope.version)
                .putString(keys.iv, SecretEnvelopeCodec.encode(envelope.iv))
                .putString(keys.ciphertext, SecretEnvelopeCodec.encode(envelope.ciphertext))
        }
        if (!editor.commit()) throw IOException("Could not persist encrypted API credentials")
    }

    private fun removeEnvelopes(names: Set<String>) {
        val editor = preferences.edit()
        names.forEach { name ->
            val keys = preferenceKeys(name)
            editor.remove(keys.version).remove(keys.iv).remove(keys.ciphertext)
        }
        if (!editor.commit()) throw IOException("Could not clear encrypted API credentials")
    }

    private fun clearInvalidatedState() {
        removeEnvelopes(SUPPORTED_NAMES)
        runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    private fun preferenceKeys(name: String): PreferenceKeys = PreferenceKeys(
        version = "secret.$name.version",
        iv = "secret.$name.iv",
        ciphertext = "secret.$name.ciphertext",
    )

    private fun additionalAuthenticatedData(name: String): ByteArray =
        "LectureVault|$ENVELOPE_VERSION|$name".toByteArray(StandardCharsets.UTF_8)

    private fun requireSupportedName(name: String) {
        require(name in SUPPORTED_NAMES) { "Unsupported secret name" }
    }

    private fun requireSecretValue(value: String): String {
        val normalized = normalizeApiCredential(value)
        require(normalized.isNotEmpty()) { "API credential must not be blank" }
        return normalized
    }

    private data class PreferenceKeys(
        val version: String,
        val iv: String,
        val ciphertext: String,
    )

    companion object {
        const val GROQ_API_KEY = "groq_api_key"
        const val GEMINI_API_KEY = "gemini_api_key"

        private const val PREFERENCES_NAME = "lecture_vault_encrypted_secrets"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "lecture_vault_api_credentials_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = 1
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private val SUPPORTED_NAMES = setOf(GROQ_API_KEY, GEMINI_API_KEY)
        private val secretLock = Any()
    }
}

/** API credentials never contain whitespace; paste operations commonly add a line break. */
internal fun normalizeApiCredential(value: String): String =
    value.filterNot { character -> character.isWhitespace() || character.isISOControl() }

internal class SecretEnvelope(
    val version: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

/** Encoding validation is pure and can be tested without AndroidKeyStore. */
internal object SecretEnvelopeCodec {
    private const val CURRENT_VERSION = 1
    private const val GCM_IV_BYTES = 12
    private const val MIN_GCM_CIPHERTEXT_BYTES = 17

    fun encode(bytes: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(bytes)

    fun decode(version: Int, encodedIv: String?, encodedCiphertext: String?): SecretEnvelope? {
        if (version != CURRENT_VERSION || encodedIv == null || encodedCiphertext == null) return null
        val iv = runCatching { Base64.getDecoder().decode(encodedIv) }.getOrNull() ?: return null
        val ciphertext = runCatching { Base64.getDecoder().decode(encodedCiphertext) }.getOrNull() ?: return null
        if (iv.size != GCM_IV_BYTES || ciphertext.size < MIN_GCM_CIPHERTEXT_BYTES) return null
        return SecretEnvelope(version, iv, ciphertext)
    }
}

private fun Throwable.hasInvalidatedKeyCause(): Boolean {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is KeyPermanentlyInvalidatedException || current is UnrecoverableKeyException) return true
        current = current.cause
    }
    return false
}
