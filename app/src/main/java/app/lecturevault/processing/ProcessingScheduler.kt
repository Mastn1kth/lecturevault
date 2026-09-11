package app.lecturevault.processing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.lecturevault.data.SessionRepository
import app.lecturevault.data.SessionStatus
import app.lecturevault.util.AppEvents
import java.util.concurrent.TimeUnit

object ProcessingScheduler {
    fun enqueue(context: Context, sessionId: String) {
        val appContext = context.applicationContext
        val repository = SessionRepository(appContext)
        repository.update(sessionId) { session ->
            if (session.status == SessionStatus.COMPLETE) {
                session
            } else {
                session.copy(status = SessionStatus.QUEUED, progress = 0, errorMessage = null)
            }
        }

        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<LectureProcessingWorker>()
            .setInputData(workDataOf(LectureProcessingWorker.KEY_SESSION_ID to sessionId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workTag(sessionId))
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            uniqueWorkName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        )
        AppEvents.sessionChanged(appContext)
    }

    fun cancel(context: Context, sessionId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(sessionId))
    }

    private fun uniqueWorkName(sessionId: String) = "lecture-$sessionId"
    private fun workTag(sessionId: String) = "lecture-tag-$sessionId"
}
