package app.lecturevault.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.getSystemService
import app.lecturevault.data.SessionRepository
import app.lecturevault.data.SessionStatus
import app.lecturevault.processing.ProcessingScheduler
import app.lecturevault.util.AppEvents
import app.lecturevault.util.Formatters
import app.lecturevault.util.Notifications
import java.io.File
import java.util.concurrent.TimeUnit

class RecordingService : Service() {
    private val repository by lazy { SessionRepository(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var sessionId: String? = null
    private var currentPartFile: File? = null
    private var nextSegmentIndex = 0
    private var recordingStartedAt = 0L
    private var stopping = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val rotateRunnable = Runnable { rotateSegment() }
    private val notificationRunnable = object : Runnable {
        override fun run() {
            if (recorder == null || stopping) return
            updateNotification()
            handler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startRecording(
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    course = intent.getStringExtra(EXTRA_COURSE).orEmpty(),
                )
                ACTION_STOP -> finishRecordingAndQueue()
            }
        } catch (error: Exception) {
            failRecording("Не удалось начать запись: ${safeMessage(error)}")
        }
        return START_NOT_STICKY
    }

    private fun startRecording(title: String, course: String) {
        if (recorder != null || RecordingState.activeSessionId(this) != null) return

        val availableBytes = StatFs(filesDir.absolutePath).availableBytes
        check(availableBytes >= MIN_FREE_STORAGE_BYTES) {
            "Недостаточно свободного места — освободите хотя бы 100 МБ"
        }

        startForeground(
            Notifications.RECORDING_NOTIFICATION_ID,
            Notifications.recording(this, Formatters.duration(0)),
        )

        val session = repository.create(title.trim(), course.trim())
        sessionId = session.id
        recordingStartedAt = session.startedAt
        RecordingState.setActiveSessionId(this, session.id)
        acquireWakeLock()

        try {
            startSegment()
            handler.post(notificationRunnable)
            AppEvents.sessionChanged(this)
        } catch (error: Exception) {
            failRecording("Не удалось открыть микрофон: ${safeMessage(error)}")
        }
    }

    private fun startSegment() {
        val id = checkNotNull(sessionId)
        val partFile = File(
            repository.sessionDir(id),
            "segment_${nextSegmentIndex.toString().padStart(3, '0')}.m4a.part",
        )
        if (partFile.exists()) partFile.delete()

        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        newRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(partFile.absolutePath)
            prepare()
            start()
        }
        currentPartFile = partFile
        recorder = newRecorder
        handler.postDelayed(rotateRunnable, SEGMENT_DURATION_MS)
    }

    private fun rotateSegment() {
        if (stopping || recorder == null) return
        try {
            finalizeCurrentSegment()
            startSegment()
        } catch (error: Exception) {
            failRecording("Запись прервана: ${safeMessage(error)}")
        }
    }

    private fun finalizeCurrentSegment() {
        handler.removeCallbacks(rotateRunnable)
        val activeRecorder = recorder ?: return
        val partFile = currentPartFile
        recorder = null
        currentPartFile = null

        var stoppedCleanly = false
        try {
            activeRecorder.stop()
            stoppedCleanly = true
        } finally {
            activeRecorder.reset()
            activeRecorder.release()
            if (!stoppedCleanly) partFile?.delete()
        }

        if (partFile == null || !partFile.exists() || partFile.length() == 0L) {
            partFile?.delete()
            return
        }
        val finalFile = File(partFile.parentFile, partFile.name.removeSuffix(".part"))
        check(partFile.renameTo(finalFile)) { "Не удалось завершить аудиофайл" }
        repository.addSegment(checkNotNull(sessionId), finalFile.absolutePath)
        nextSegmentIndex += 1
        AppEvents.sessionChanged(this)
    }

    private fun finishRecordingAndQueue() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val id = sessionId ?: RecordingState.activeSessionId(this)
        try {
            finalizeCurrentSegment()
            if (id != null) {
                val completedAt = System.currentTimeMillis()
                repository.update(id) { session ->
                    session.copy(
                        endedAt = completedAt,
                        status = SessionStatus.QUEUED,
                        progress = 0,
                        errorMessage = null,
                    )
                }
                ProcessingScheduler.enqueue(this, id)
            }
        } catch (error: Exception) {
            if (id != null) {
                repository.update(id) { session ->
                    session.copy(
                        endedAt = System.currentTimeMillis(),
                        status = SessionStatus.FAILED,
                        errorMessage = "Не удалось завершить запись: ${safeMessage(error)}",
                    )
                }
            }
        } finally {
            cleanupAndStop()
        }
    }

    private fun failRecording(message: String) {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        runCatching {
            recorder?.stop()
        }
        recorder?.release()
        recorder = null
        currentPartFile?.delete()
        sessionId?.let { id ->
            repository.update(id) { session ->
                session.copy(
                    endedAt = System.currentTimeMillis(),
                    status = SessionStatus.FAILED,
                    errorMessage = message,
                )
            }
        }
        Notifications.showResult(this, "Запись остановлена", message)
        cleanupAndStop()
    }

    private fun cleanupAndStop() {
        RecordingState.setActiveSessionId(this, null)
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        sessionId = null
        AppEvents.sessionChanged(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService<PowerManager>() ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:lecture-recording",
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun updateNotification() {
        val elapsed = System.currentTimeMillis() - recordingStartedAt
        getSystemService<NotificationManager>()?.notify(
            Notifications.RECORDING_NOTIFICATION_ID,
            Notifications.recording(this, Formatters.duration(elapsed)),
        )
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(160)?.replace(Regex("[\r\n]+"), " ") ?: error.javaClass.simpleName

    companion object {
        const val ACTION_START = "app.lecturevault.action.START_RECORDING"
        const val ACTION_STOP = "app.lecturevault.action.STOP_RECORDING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COURSE = "course"

        private val SEGMENT_DURATION_MS = TimeUnit.MINUTES.toMillis(20)
        private val MAX_WAKE_LOCK_MS = TimeUnit.HOURS.toMillis(4)
        private const val MIN_FREE_STORAGE_BYTES = 100L * 1024L * 1024L
        private const val NOTIFICATION_REFRESH_MS = 10_000L
    }
}
