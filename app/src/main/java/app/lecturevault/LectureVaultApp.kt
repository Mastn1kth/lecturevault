package app.lecturevault

import android.app.Application
import app.lecturevault.util.Notifications

class LectureVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
