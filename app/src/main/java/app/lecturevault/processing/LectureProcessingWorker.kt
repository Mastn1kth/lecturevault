package app.lecturevault.processing

import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lecturevault.data.AppSettings
import app.lecturevault.data.AtomicUtf8File
import app.lecturevault.data.SessionRepository
import app.lecturevault.data.SessionStatus
import app.lecturevault.network.GatewayClient
import app.lecturevault.obsidian.VaultWriter
import app.lecturevault.offline.LocalLectureSummarizer
import app.lecturevault.offline.OfflineModelManager
import app.lecturevault.offline.VoskTranscriber
import app.lecturevault.util.AppEvents
import app.lecturevault.util.Formatters
import app.lecturevault.util.LectureTopic
import app.lecturevault.util.Notifications
import kotlinx.coroutines.CancellationException
import java.io.File

class LectureProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = SessionRepository(appContext)
    private val settings = AppSettings(appContext)
    private val notificationId = NOTIFICATION_ID_BASE + (id.hashCode() and 0x0FFF)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Подготовка…", null)

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
        val initialSession = repository.get(sessionId) ?: return Result.failure()
        if (initialSession.status == SessionStatus.COMPLETE) return Result.success()
        if (initialSession.segmentFiles.isEmpty()) {
            return fail(sessionId, "В записи нет готовых аудиофрагментов")
        }
        if (runAttemptCount >= MAX_ATTEMPTS) {
            return fail(sessionId, "Обработка не удалась после $MAX_ATTEMPTS попыток")
        }

        val vaultUri = settings.vaultTreeUri
            ?: return fail(sessionId, "Папка Obsidian не выбрана")
        return try {
            val cloudAttempt = tryCloud(sessionId)
            val cloud = cloudAttempt.output
            val transcript = cloud?.transcript ?: run {
                val offlineModel = OfflineModelManager(applicationContext)
                if (!offlineModel.isInstalled()) {
                    val reason = cloudAttempt.failureReason ?: "Облачная обработка не настроена"
                    return fail(
                        sessionId,
                        "$reason. Скачайте локальную модель или проверьте подключение к интернету.",
                    )
                }
                cloudAttempt.failureReason?.let { reason ->
                    setForeground(foregroundInfo("Облако недоступно: $reason. Продолжаем локально…", 3))
                }
                transcribeSession(sessionId, offlineModel)
            }
            ensureNotStopped()

            update(sessionId, SessionStatus.SUMMARIZING, 74)
            setForeground(foregroundInfo(if (cloud != null) "Создаём конспект через облачный ИИ…" else "Локально создаём конспект…", 74))
            val session = checkNotNull(repository.get(sessionId))
            val summary = cloud?.summary ?: LocalLectureSummarizer.summarize(transcript.text, session.course, Formatters.dateTime(session.createdAt))
            val detectedTitle = LectureTopic.fromSummary(summary)
            repository.update(sessionId) { it.copy(title = detectedTitle) }
            ensureNotStopped()

            update(sessionId, SessionStatus.SAVING, 92)
            setForeground(foregroundInfo("Сохраняем заметку в Obsidian…", 92))
            val saved = VaultWriter(applicationContext).writeLectureNote(
                treeUri = vaultUri,
                notesFolder = settings.notesFolder,
                sessionId = session.id,
                title = detectedTitle,
                course = session.course,
                createdAt = session.createdAt,
                summaryMarkdown = summary,
                timestampedTranscript = transcript.timestampedText,
            )
            repository.update(sessionId) {
                it.copy(
                    status = SessionStatus.COMPLETE,
                    progress = 100,
                    errorMessage = null,
                    noteRelativePath = saved.relativePath,
                )
            }
            AppEvents.sessionChanged(applicationContext)
            Notifications.showLectureReady(applicationContext, sessionId, saved.displayName)
            Result.success()
        } catch (cancelled: CancellationException) {
            fail(sessionId, "Обработка отменена")
        } catch (error: SecurityException) {
            fail(sessionId, "Доступ к папке Obsidian отозван. Выберите её заново.")
        } catch (error: Exception) {
            fail(sessionId, safeMessage(error))
        }
    }

    private suspend fun transcribeSession(sessionId: String, offlineModel: OfflineModelManager): CombinedTranscript {
        val session = checkNotNull(repository.get(sessionId))
        val total = session.segmentFiles.size
        val result = VoskTranscriber(offlineModel.modelPath()).transcribe(session.segmentFiles.map(::File)) { index, count ->
            ensureNotStopped()
            val percent = 5 + ((index * 65) / count)
            update(sessionId, SessionStatus.TRANSCRIBING, percent)
        }
        check(result.text.isNotBlank()) { "Локальная модель не распознала речь" }
        return CombinedTranscript(result.text, result.timestampedText)
    }

    private suspend fun tryCloud(sessionId: String): CloudAttempt {
        if (!settings.consent) return CloudAttempt(failureReason = "Нет согласия на облачную обработку")
        return try {
            val session = checkNotNull(repository.get(sessionId))
            val gateway = GatewayClient()
            val parts = session.segmentFiles.mapIndexed { index, path ->
                ensureNotStopped()
                val percent = 5 + ((index * 65) / session.segmentFiles.size)
                update(sessionId, SessionStatus.TRANSCRIBING, percent)
                setForeground(foregroundInfo("Расшифровываем: ${index + 1} из ${session.segmentFiles.size}", percent))
                gateway.transcribe(File(path))
            }
            val text = parts.joinToString("\n\n") { it.text.trim() }.trim()
            val timestamps = parts.mapIndexed { index, item -> "### Часть ${index + 1}\n${item.timestampedText.trim()}" }.joinToString("\n\n")
            check(text.isNotBlank()) { "Groq вернул пустую расшифровку" }
            val summary = gateway.summarize(text, session.course)
            CloudAttempt(output = CloudOutput(CombinedTranscript(text, timestamps), summary))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            update(sessionId, SessionStatus.QUEUED, 1)
            CloudAttempt(failureReason = "Ошибка сервера ИИ: ${safeMessage(error)}")
        }
    }

    private fun update(sessionId: String, status: SessionStatus, progress: Int) {
        repository.update(sessionId) {
            it.copy(status = status, progress = progress, errorMessage = null)
        }
        AppEvents.sessionChanged(applicationContext)
    }

    private fun fail(sessionId: String, message: String): Result {
        repository.get(sessionId)?.let {
            repository.update(sessionId) { session ->
                session.copy(status = SessionStatus.FAILED, errorMessage = message.take(240))
            }
        }
        AppEvents.sessionChanged(applicationContext)
        Notifications.showResult(applicationContext, "Лекция не обработана", message.take(180))
        return Result.failure()
    }

    private fun foregroundInfo(message: String, progress: Int?): ForegroundInfo {
        val cancelIntent: PendingIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = Notifications.processing(
            applicationContext,
            message,
            progress,
            cancelIntent,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotStopped() {
        if (isStopped) throw CancellationException("Worker stopped")
    }

    private fun safeMessage(error: Throwable): String =
        error.message
            ?.replace(Regex("[\r\n\t]+"), " ")
            ?.take(240)
            ?: "Неизвестная ошибка обработки"

    private data class CombinedTranscript(val text: String, val timestampedText: String)
    private data class CloudOutput(val transcript: CombinedTranscript, val summary: String)
    private data class CloudAttempt(val output: CloudOutput? = null, val failureReason: String? = null)

    companion object {
        const val KEY_SESSION_ID = "session_id"
        private const val NOTIFICATION_ID_BASE = 2_000
        private const val MAX_ATTEMPTS = 5
    }
}
