package app.lecturevault.obsidian

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VaultWriter(private val context: Context) {
    data class SavedNote(
        val relativePath: String,
        val displayName: String,
        val vaultName: String,
    )

    fun writeLectureNote(
        treeUri: String,
        notesFolder: String,
        sessionId: String,
        title: String,
        course: String,
        createdAt: Long,
        summaryMarkdown: String,
        timestampedTranscript: String,
    ): SavedNote {
        val rootUri = Uri.parse(treeUri)
        val persisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == rootUri && it.isWritePermission
        }
        check(persisted) { "Доступ к папке Obsidian отозван. Выберите её заново." }

        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Выбранная папка Obsidian недоступна")
        check(root.exists() && root.isDirectory && root.canWrite()) {
            "Нет доступа на запись в выбранную папку Obsidian"
        }

        val folderSegments = validateFolderPath(notesFolder) + subjectFolderName(course)
        var destination = root
        for (segment in folderSegments) {
            destination = destination.findFile(segment)?.also {
                check(it.isDirectory) { "В пути конспектов '$segment' уже существует как файл" }
            } ?: checkNotNull(destination.createDirectory(segment)) {
                "Не удалось создать папку '$segment'"
            }
        }

        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(createdAt))
        val safeTitle = sanitizeFilePart(title.ifBlank { "Лекция" })
        destination.listFiles().firstOrNull { it.isFile && noteBelongsToSession(it, sessionId) }?.let { existing ->
            val existingName = checkNotNull(existing.name)
            return SavedNote((folderSegments + existingName).joinToString("/"), existingName, root.name.orEmpty())
        }
        val displayName = uniqueDisplayName(destination, "$date — $safeTitle")

        val completeMarkdown = buildMarkdown(
            sessionId = sessionId,
            title = title.ifBlank { "Лекция" },
            course = course,
            createdAt = createdAt,
            summaryMarkdown = summaryMarkdown,
            timestampedTranscript = timestampedTranscript,
        )
        val document = checkNotNull(destination.createFile("text/markdown", displayName)) {
            "Не удалось создать заметку в Obsidian"
        }
        try {
            context.contentResolver.openOutputStream(document.uri, "wt")?.use { output ->
                output.write(completeMarkdown.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: error("Не удалось открыть заметку для записи")
        } catch (error: Exception) {
            document.delete()
            throw error
        }

        val relativePath = (folderSegments + displayName).joinToString("/")
        return SavedNote(relativePath, displayName, root.name.orEmpty())
    }

    fun readLectureNote(treeUri: String, relativePath: String): String {
        val document = resolveNote(treeUri, relativePath, requireWrite = false)
        return context.contentResolver.openInputStream(document.uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: error("Не удалось открыть конспект")
    }

    fun deleteLectureNote(treeUri: String, relativePath: String) {
        val document = resolveNote(treeUri, relativePath, requireWrite = true)
        check(document.delete()) { "Не удалось удалить конспект из Obsidian" }
    }

    private fun resolveNote(treeUri: String, relativePath: String, requireWrite: Boolean): DocumentFile {
        val rootUri = Uri.parse(treeUri)
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == rootUri }
        check(permission?.isReadPermission == true && (!requireWrite || permission.isWritePermission)) {
            "Доступ к папке Obsidian отозван. Выберите её заново."
        }
        val root = DocumentFile.fromTreeUri(context, rootUri)
            ?: error("Выбранная папка Obsidian недоступна")
        check(root.exists() && root.isDirectory) { "Выбранная папка Obsidian недоступна" }
        if (requireWrite) check(root.canWrite()) { "Нет доступа на удаление в выбранной папке Obsidian" }

        val segments = relativePath.replace('\\', '/').trim('/').split('/')
        require(segments.isNotEmpty() && segments.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Некорректный путь конспекта"
        }
        require(segments.none { it.equals(".obsidian", ignoreCase = true) }) {
            "Доступ к служебной папке .obsidian запрещён"
        }
        var current = root
        for ((index, segment) in segments.withIndex()) {
            current = current.findFile(segment) ?: error("Файл конспекта не найден")
            if (index < segments.lastIndex) check(current.isDirectory) { "Некорректный путь конспекта" }
        }
        check(current.isFile && current.name?.endsWith(".md", ignoreCase = true) == true) {
            "Выбранный файл не является конспектом"
        }
        return current
    }

    private fun noteBelongsToSession(document: DocumentFile, sessionId: String): Boolean {
        val marker = "lecturevault_id: ${yamlString(sessionId)}"
        return runCatching {
            context.contentResolver.openInputStream(document.uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val prefix = CharArray(MAX_IDEMPOTENCY_SCAN_CHARS)
                val count = reader.read(prefix)
                count > 0 && String(prefix, 0, count).contains(marker)
            } ?: false
        }.getOrDefault(false)
    }

    internal fun buildMarkdown(
        sessionId: String,
        title: String,
        course: String,
        createdAt: Long,
        summaryMarkdown: String,
        timestampedTranscript: String,
    ): String {
        val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date(createdAt))
        val safeSummary = sanitizeGeneratedMarkdown(summaryMarkdown)
        val safeTranscript = sanitizeTranscript(timestampedTranscript)
        return buildString {
            appendLine("---")
            appendLine("type: lecture")
            appendLine("lecturevault_id: ${yamlString(sessionId)}")
            appendLine("title: ${yamlString(title)}")
            appendLine("course: ${yamlString(course)}")
            appendLine("created: ${yamlString(isoDate)}")
            appendLine("tags:")
            appendLine("  - lecturevault")
            appendLine("---")
            appendLine()
            appendLine(safeSummary.trim())
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Полная расшифровка")
            appendLine()
            appendLine(safeTranscript.trim())
            appendLine()
        }
    }

    internal fun validateFolderPath(rawPath: String): List<String> {
        if (rawPath.isBlank()) return emptyList()
        return rawPath.replace('\\', '/').split('/').map { it.trim() }.also { parts ->
            require(parts.all { it.isNotBlank() }) { "Путь к конспектам содержит пустую папку" }
            require(parts.none { it == "." || it == ".." }) { "Недопустимый путь к конспектам" }
            require(parts.none { it.equals(".obsidian", ignoreCase = true) }) {
                "Запись в служебную папку .obsidian запрещена"
            }
            require(parts.all { SAFE_FOLDER.matches(it) }) {
                "В названии папки есть недопустимые символы"
            }
        }
    }

    internal fun sanitizeFilePart(value: String): String {
        val cleaned = value
            .replace(CONTROL_OR_FILE_CHARS, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
            .take(72)
        return cleaned.ifBlank { "Лекция" }
    }

    internal fun subjectFolderName(course: String): String =
        sanitizeFilePart(course).takeIf(String::isNotBlank) ?: "Без предмета"

    private fun uniqueDisplayName(destination: DocumentFile, stem: String): String {
        var suffix = 1
        var candidate = "$stem.md"
        while (destination.findFile(candidate) != null) {
            suffix += 1
            candidate = "$stem ($suffix).md"
        }
        return candidate
    }

    internal fun sanitizeGeneratedMarkdown(markdown: String): String {
        var safe = markdown.trim()
        if (safe.startsWith("```markdown") && safe.endsWith("```")) {
            safe = safe.removePrefix("```markdown").removeSuffix("```").trim()
        }
        safe = safe.replace(DANGEROUS_HTML, "")
        safe = safe.replace(EXTERNAL_IMAGE) { match ->
            val alt = match.groupValues[1].take(100)
            "[Внешнее изображение удалено${if (alt.isBlank()) "" else ": $alt"}]"
        }
        safe = safe.replace(UNSAFE_SCHEME, "заблокированная-ссылка:")
        return safe
    }

    private fun sanitizeTranscript(transcript: String): String =
        transcript.replace('\u0000', ' ').replace(DANGEROUS_HTML, "")

    private fun yamlString(value: String): String = JSONObject.quote(value)

    companion object {
        private val SAFE_FOLDER = Regex("^[^\\p{Cntrl}/\\:*?\"<>|.][^\\p{Cntrl}/\\:*?\"<>|]{0,79}$")
        private val CONTROL_OR_FILE_CHARS = Regex("[\\p{Cntrl}/\\:*?\"<>|]+")
        private val DANGEROUS_HTML = Regex(
            "<\\s*/?\\s*(script|iframe|object|embed|style|link|meta)\\b[^>]*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val EXTERNAL_IMAGE = Regex("!\\[([^]]*)]\\(\\s*https?://[^)]+\\)", RegexOption.IGNORE_CASE)
        private val UNSAFE_SCHEME = Regex("(?i)(javascript|file|obsidian)\\s*:")
        private const val MAX_IDEMPOTENCY_SCAN_CHARS = 2_048
    }
}
