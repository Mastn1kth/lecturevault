package app.lecturevault

import android.app.Application
import app.lecturevault.data.SecretStore
import app.lecturevault.util.Notifications

class LectureVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        removeLegacyDeviceKeys()
    }

    private fun removeLegacyDeviceKeys() {
        val migration = getSharedPreferences("gateway_migration", MODE_PRIVATE)
        if (migration.getBoolean("legacy_provider_keys_removed", false)) return
        runCatching { SecretStore(this).clear() }
            .onSuccess { migration.edit().putBoolean("legacy_provider_keys_removed", true).apply() }
    }
}
