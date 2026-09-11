package app.lecturevault.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lecturevault.R
import app.lecturevault.recording.RecordingService
import app.lecturevault.ui.MainActivity
import app.lecturevault.ui.LectureReaderActivity

object Notifications {
    const val CHANNEL_RECORDING = "lecture_recording"
    const val CHANNEL_PROCESSING = "lecture_processing"
    const val CHANNEL_RESULTS = "lecture_results"
    const val RECORDING_NOTIFICATION_ID = 1101
    const val PROCESSING_NOTIFICATION_ID = 1102

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_RECORDING,
                    context.getString(R.string.channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Постоянное уведомление во время записи"
                    setSound(null, null)
                },
                NotificationChannel(
                    CHANNEL_PROCESSING,
                    context.getString(R.string.channel_processing),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Расшифровка и создание конспекта"
                    setSound(null, null)
                },
                NotificationChannel(
                    CHANNEL_RESULTS,
                    context.getString(R.string.channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    fun recording(context: Context, elapsedText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            11,
            Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Идёт запись лекции")
            .setContentText(elapsedText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Остановить", stopIntent)
            .build()
    }

    fun processing(
        context: Context,
        message: String,
        progress: Int? = null,
        cancelIntent: PendingIntent? = null,
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            20,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Обрабатываем лекцию")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                if (progress == null) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(100, progress.coerceIn(0, 100), false)
                }
                if (cancelIntent != null) addAction(0, "Отменить", cancelIntent)
            }
            .build()
    }

    fun showResult(context: Context, title: String, message: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openIntent = PendingIntent.getActivity(
            context,
            title.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_RESULTS)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("LectureVault")
                    .setContentText("Откройте приложение, чтобы посмотреть результат")
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(title.hashCode(), notification)
    }

    fun showLectureReady(context: Context, sessionId: String, lectureTitle: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val openIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, LectureReaderActivity::class.java).putExtra(LectureReaderActivity.EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Конспект готов")
            .setContentText(lectureTitle)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(sessionId.hashCode(), notification)
    }
}
