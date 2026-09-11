package app.lecturevault.util

import android.content.Context
import android.content.Intent

object AppEvents {
    const val SESSION_CHANGED = "app.lecturevault.action.SESSION_CHANGED"

    fun sessionChanged(context: Context) {
        context.sendBroadcast(Intent(SESSION_CHANGED).setPackage(context.packageName))
    }
}
