package app.lecturevault

import android.app.Application
import app.lecturevault.data.LegacyKeyCleanup
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
        runCatching { LegacyKeyCleanup.clear(this) }
            .onSuccess { migration.edit().putBoolean("legacy_provider_keys_removed", true).apply() }
    }
}
