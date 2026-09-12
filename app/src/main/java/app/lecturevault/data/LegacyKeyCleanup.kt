package app.lecturevault.data

import android.content.Context
import java.security.KeyStore

/** Removes credentials written by pre-gateway versions of LectureVault. */
object LegacyKeyCleanup {
    private const val PREFERENCES_NAME = "lecture_vault_encrypted_secrets"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "lecture_vault_api_credentials_v1"

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }
}
