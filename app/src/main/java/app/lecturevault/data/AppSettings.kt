package app.lecturevault.data

import android.content.Context
import android.content.SharedPreferences

class AppSettings internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    var vaultTreeUri: String?
        get() = preferences.getString(KEY_VAULT_TREE_URI, null)?.takeIf(String::isNotBlank)
        set(value) {
            val normalized = value?.trim()?.takeIf(String::isNotEmpty)
            preferences.edit().apply {
                if (normalized == null) remove(KEY_VAULT_TREE_URI) else putString(KEY_VAULT_TREE_URI, normalized)
            }.apply()
        }

    var notesFolder: String
        get() = normalizeNotesFolder(
            preferences.getString(KEY_NOTES_FOLDER, null),
        )
        set(value) {
            preferences.edit().putString(KEY_NOTES_FOLDER, normalizeNotesFolder(value)).apply()
        }

    var consent: Boolean
        get() = preferences.getBoolean(KEY_CONSENT, false)
        set(value) {
            preferences.edit().putBoolean(KEY_CONSENT, value).apply()
        }

    fun isConfigured(): Boolean = runCatching {
        !vaultTreeUri.isNullOrBlank() && notesFolder.isNotBlank()
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_NOTES_FOLDER = "Лекции"
        private const val PREFERENCES_NAME = "lecture_vault_settings"
        private const val KEY_VAULT_TREE_URI = "vault_tree_uri"
        private const val KEY_NOTES_FOLDER = "notes_folder"
        private const val KEY_CONSENT = "api_data_consent"

        internal fun normalizeNotesFolder(value: String?): String {
            val candidate = value?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_NOTES_FOLDER
            return SessionValues.normalizeVaultRelativePath(candidate)
        }
    }
}
