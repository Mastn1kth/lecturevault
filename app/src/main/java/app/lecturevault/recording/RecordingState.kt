package app.lecturevault.recording

import android.content.Context

object RecordingState {
    private const val PREFS = "recording_state"
    private const val KEY_ACTIVE_SESSION = "active_session"

    fun activeSessionId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SESSION, null)

    fun setActiveSessionId(context: Context, sessionId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (sessionId == null) remove(KEY_ACTIVE_SESSION) else putString(KEY_ACTIVE_SESSION, sessionId)
            }
            .apply()
    }
}
