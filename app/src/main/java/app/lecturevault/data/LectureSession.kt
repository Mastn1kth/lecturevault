package app.lecturevault.data

import java.util.UUID

enum class SessionStatus {
    RECORDING,
    QUEUED,
    TRANSCRIBING,
    SUMMARIZING,
    SAVING,
    COMPLETE,
    FAILED,
}

data class LectureSession(
    val id: String,
    val title: String,
    val course: String,
    val createdAt: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val status: SessionStatus,
    val segmentFiles: List<String>,
    val progress: Int,
    val errorMessage: String?,
    val noteRelativePath: String?,
) {
    init {
        require(SessionValues.isCanonicalUuid(id)) { "Session id must be a canonical UUID" }
        require(createdAt >= 0L) { "createdAt must not be negative" }
        require(startedAt >= 0L) { "startedAt must not be negative" }
        require(endedAt == null || endedAt >= 0L) { "endedAt must not be negative" }
        require(progress in MIN_PROGRESS..MAX_PROGRESS) { "progress must be between 0 and 100" }
    }

    companion object {
        const val MIN_PROGRESS = 0
        const val MAX_PROGRESS = 100
    }
}

/** Pure validation helpers kept independent from Android for local unit tests. */
internal object SessionValues {
    fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString().equals(value, ignoreCase = true)
    }.getOrDefault(false)

    fun normalized(session: LectureSession): LectureSession = session.copy(
        title = session.title.trim(),
        course = session.course.trim(),
        segmentFiles = session.segmentFiles.toList(),
        errorMessage = session.errorMessage?.trim()?.takeIf(String::isNotEmpty),
        noteRelativePath = session.noteRelativePath
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::normalizeVaultRelativePath),
    )

    fun normalizeVaultRelativePath(value: String): String {
        val normalized = value.trim().replace('\\', '/').trim('/')
        require(normalized.isNotEmpty()) { "Vault path must not be empty" }
        require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Vault path must be a safe relative path"
        }
        return normalized
    }
}
