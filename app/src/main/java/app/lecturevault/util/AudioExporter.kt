package app.lecturevault.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioExporter {
    suspend fun export(
        context: Context,
        destinationTreeUri: Uri,
        sourcePaths: List<String>,
        lectureTitle: String,
    ): Int = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, destinationTreeUri)
            ?: error("Папка для аудио недоступна")
        check(root.exists() && root.isDirectory && root.canWrite()) {
            "Нет доступа к папке для аудио"
        }
        val files = sourcePaths.map(::File).filter { it.isFile && it.canRead() }
        check(files.isNotEmpty()) { "Исходные аудиофайлы не найдены" }

        files.forEachIndexed { index, source ->
            val extension = source.extension.lowercase().takeIf { it in AUDIO_EXTENSIONS } ?: "m4a"
            val fileName = "${safeName(lectureTitle)} — ${String.format("%02d", index + 1)}.$extension"
            val target = uniqueFile(root, fileName, mimeType(extension))
            try {
                context.contentResolver.openOutputStream(target.uri)?.buffered().use { output ->
                    checkNotNull(output) { "Не удалось открыть файл для сохранения" }
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                }
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }
        files.size
    }

    internal fun safeName(value: String): String = value
        .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(64)
        .ifBlank { "Лекция" }

    private fun uniqueFile(directory: DocumentFile, desiredName: String, mimeType: String): DocumentFile {
        var number = 1
        var candidate = desiredName
        val dot = desiredName.lastIndexOf('.')
        val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val extension = if (dot > 0) desiredName.substring(dot) else ""
        while (directory.findFile(candidate) != null) {
            number += 1
            candidate = "$stem ($number)$extension"
        }
        return checkNotNull(directory.createFile(mimeType, candidate)) { "Не удалось создать аудиофайл" }
    }

    private fun mimeType(extension: String): String = when (extension) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3", "mpeg", "mpga" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }

    private val AUDIO_EXTENSIONS = setOf("flac", "m4a", "mp3", "mp4", "mpeg", "mpga", "ogg", "wav", "webm")
}
