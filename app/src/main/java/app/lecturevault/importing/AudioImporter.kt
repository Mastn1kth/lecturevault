package app.lecturevault.importing

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lecturevault.data.LectureSession
import app.lecturevault.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class AudioImporter(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val repository = SessionRepository(appContext)

    suspend fun import(uri: Uri, course: String): LectureSession = withContext(Dispatchers.IO) {
        require(course.isNotBlank()) { "Введите предмет" }
        val document = DocumentFile.fromSingleUri(appContext, uri)
            ?: throw IOException("Не удалось открыть аудиофайл")
        val declaredSize = document.length().takeIf { it > 0L }
        if (declaredSize != null && declaredSize > MAX_AUDIO_BYTES) {
            throw IOException("Аудиофайл больше 1 ГБ")
        }
        if (declaredSize != null && appContext.filesDir.usableSpace < declaredSize + MIN_FREE_SPACE_BYTES) {
            throw IOException("Недостаточно памяти для импорта")
        }

        val now = System.currentTimeMillis()
        val sourceDate = document.lastModified().takeIf { it in 1..(now + MAX_CLOCK_SKEW_MS) } ?: now
        val extension = chooseExtension(document.name, resolver.getType(uri))
        val session = repository.createImported(course.trim(), sourceDate)
        val temporary = File(repository.sessionDir(session.id), ".audio-import.part")
        val destination = File(repository.sessionDir(session.id), "imported.$extension")

        try {
            copyBounded(uri, temporary)
            validateAudioTrack(temporary)
            check(temporary.renameTo(destination)) { "Не удалось завершить импорт аудио" }
            repository.addSegment(session.id, destination.absolutePath)
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            repository.update(session.id) {
                it.copy(status = app.lecturevault.data.SessionStatus.FAILED, errorMessage = safeMessage(error))
            }
            throw error
        }
    }

    private fun copyBounded(uri: Uri, target: File) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось прочитать аудиофайл")
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_AUDIO_BYTES) throw IOException("Аудиофайл больше 1 ГБ")
                    output.write(buffer, 0, count)
                }
                if (copied == 0L) throw IOException("Аудиофайл пуст")
                output.fd.sync()
            }
        }
    }

    private fun validateAudioTrack(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check((0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }) { "В выбранном файле нет аудиодорожки" }
        } finally {
            extractor.release()
        }
    }

    private fun chooseExtension(name: String?, mimeType: String?): String {
        val fromName = name.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (fromName in SUPPORTED_EXTENSIONS) return fromName
        return MIME_EXTENSIONS[mimeType?.lowercase(Locale.ROOT)]
            ?: throw IOException("Формат аудиофайла не поддерживается")
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.replace(Regex("[\r\n]+"), " ")?.take(160) ?: "Ошибка импорта аудио"

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_AUDIO_BYTES = 1024L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
        private const val MAX_CLOCK_SKEW_MS = 24L * 60L * 60L * 1000L
        private val SUPPORTED_EXTENSIONS = setOf("m4a", "mp3", "mp4", "mpeg", "mpga", "wav", "flac", "ogg", "webm", "aac", "3gp", "amr")
        private val MIME_EXTENSIONS = mapOf(
            "audio/mp4" to "m4a",
            "audio/mpeg" to "mp3",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/flac" to "flac",
            "audio/ogg" to "ogg",
            "audio/webm" to "webm",
            "audio/aac" to "aac",
            "audio/3gpp" to "3gp",
            "audio/amr" to "amr",
        )
    }
}
