package app.lecturevault.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SessionRepository internal constructor(
    private val sessionsRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context) : this(
        sessionsRoot = File(context.applicationContext.filesDir, SESSIONS_DIRECTORY),
    )

    private val lock: Any = lockFor(sessionsRoot)

    init {
        synchronized(lock) {
            ensureDirectory(sessionsRoot)
        }
    }

    fun create(title: String, course: String): LectureSession = synchronized(lock) {
        createSession(title, course, clock(), SessionStatus.RECORDING, null)
    }

    fun createImported(course: String, createdAt: Long): LectureSession = synchronized(lock) {
        require(createdAt >= 0L) { "createdAt must not be negative" }
        createSession("", course, createdAt, SessionStatus.QUEUED, createdAt)
    }

    private fun createSession(
        title: String,
        course: String,
        createdAt: Long,
        status: SessionStatus,
        endedAt: Long?,
    ): LectureSession {
        val id = nextAvailableId()
        val directory = sessionPath(id)
        check(directory.mkdir()) { "Could not create session directory" }

        return try {
            val session = LectureSession(
                id = id,
                title = title.trim(),
                course = course.trim(),
                createdAt = createdAt,
                startedAt = createdAt,
                endedAt = endedAt,
                status = status,
                segmentFiles = emptyList(),
                progress = 0,
                errorMessage = null,
                noteRelativePath = null,
            )
            writeSession(directory, session)
            session
        } catch (error: Exception) {
            directory.delete()
            throw error
        }
    }

    fun get(id: String): LectureSession? = synchronized(lock) {
        val directory = sessionPath(id)
        val metadata = File(directory, SESSION_FILE_NAME)
        if (!metadata.isFile) return@synchronized null
        readSession(directory, expectedId = id)
    }

    /** Newest sessions are returned first; UUID provides a stable tie-breaker. */
    fun list(): List<LectureSession> = synchronized(lock) {
        sessionsRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .filter { SessionValues.isCanonicalUuid(it.name) }
            .mapNotNull { directory ->
                runCatching { readSession(directory, expectedId = directory.name) }.getOrNull()
            }
            .sortedWith(compareByDescending<LectureSession> { it.createdAt }.thenBy { it.id })
            .toList()
    }

    fun update(
        id: String,
        transform: (LectureSession) -> LectureSession,
    ): LectureSession = synchronized(lock) {
        val directory = existingSessionDirectory(id)
        val current = readSession(directory, expectedId = id)
        val updated = SessionValues.normalized(transform(current))

        require(updated.id == current.id) { "Session id cannot be changed" }
        require(updated.createdAt == current.createdAt) { "createdAt cannot be changed" }
        require(updated.startedAt == current.startedAt) { "startedAt cannot be changed" }
        validateSegmentPaths(directory, updated.segmentFiles)
        writeSession(directory, updated)
        updated
    }

    fun addSegment(id: String, absolutePath: String): LectureSession = synchronized(lock) {
        val directory = existingSessionDirectory(id)
        val segment = File(absolutePath)
        require(segment.isAbsolute) { "Segment path must be absolute" }
        val canonicalSegment = segment.canonicalFile
        require(canonicalSegment.isFile) { "Segment file does not exist" }
        require(isStrictDescendant(directory.canonicalFile, canonicalSegment)) {
            "Segment file must be inside its session directory"
        }

        update(id) { session ->
            if (canonicalSegment.path in session.segmentFiles) {
                session
            } else {
                session.copy(segmentFiles = session.segmentFiles + canonicalSegment.path)
            }
        }
    }

    fun sessionDir(id: String): File = synchronized(lock) {
        existingSessionDirectory(id)
    }

    fun delete(id: String) = synchronized(lock) {
        val directory = existingSessionDirectory(id)
        if (!directory.deleteRecursively() || directory.exists()) {
            throw IOException("Не удалось удалить локальные данные лекции")
        }
    }

    fun newSegmentFile(id: String, index: Int): File = synchronized(lock) {
        require(index >= 0) { "Segment index must not be negative" }
        val directory = existingSessionDirectory(id)
        File(directory, "segment_${index.toString().padStart(3, '0')}.m4a")
    }

    private fun nextAvailableId(): String {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = idFactory()
            require(SessionValues.isCanonicalUuid(candidate)) { "idFactory must return a canonical UUID" }
            if (!sessionPath(candidate).exists()) return candidate.lowercase()
        }
        throw IllegalStateException("Could not allocate a unique session id")
    }

    private fun existingSessionDirectory(id: String): File {
        val directory = sessionPath(id)
        require(File(directory, SESSION_FILE_NAME).isFile) { "Unknown session id" }
        return directory
    }

    private fun sessionPath(id: String): File {
        require(SessionValues.isCanonicalUuid(id)) { "Invalid session id" }
        return File(sessionsRoot, id.lowercase())
    }

    private fun readSession(directory: File, expectedId: String): LectureSession {
        val metadata = File(directory, SESSION_FILE_NAME)
        if (!metadata.isFile) throw IOException("Session metadata is missing")
        if (metadata.length() > MAX_METADATA_BYTES) throw IOException("Session metadata is too large")

        val session = try {
            SessionJsonCodec.decode(metadata.readText(StandardCharsets.UTF_8))
        } catch (error: IllegalArgumentException) {
            throw IOException("Session metadata is invalid", error)
        }
        if (!session.id.equals(expectedId, ignoreCase = true)) {
            throw IOException("Session metadata id does not match its directory")
        }
        validateSegmentPaths(directory, session.segmentFiles)
        return session.copy(segmentFiles = session.segmentFiles.toList())
    }

    private fun writeSession(directory: File, session: LectureSession) {
        validateSegmentPaths(directory, session.segmentFiles)
        AtomicUtf8File.write(
            target = File(directory, SESSION_FILE_NAME),
            contents = SessionJsonCodec.encode(session),
        )
    }

    private fun validateSegmentPaths(directory: File, paths: List<String>) {
        val canonicalDirectory = directory.canonicalFile
        paths.forEach { path ->
            val file = File(path)
            require(file.isAbsolute) { "Stored segment path must be absolute" }
            require(isStrictDescendant(canonicalDirectory, file.canonicalFile)) {
                "Stored segment path escapes its session directory"
            }
        }
    }

    companion object {
        const val SESSIONS_DIRECTORY = "lecture_sessions"
        const val SESSION_FILE_NAME = "session.json"

        private const val MAX_ID_ATTEMPTS = 8
        private const val MAX_METADATA_BYTES = 1_048_576L
        private val lockGuard = Any()
        private val repositoryLocks = mutableMapOf<String, Any>()

        private fun lockFor(root: File): Any = synchronized(lockGuard) {
            repositoryLocks.getOrPut(root.canonicalFile.path) { Any() }
        }

        private fun ensureDirectory(directory: File) {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not create session storage")
            }
            if (!directory.isDirectory) throw IOException("Session storage is not a directory")
        }
    }
}

/** File/path primitive is Android-free so atomicity and traversal behavior can be unit tested. */
internal fun isStrictDescendant(parent: File, child: File): Boolean {
    val parentPath = parent.canonicalFile.toPath()
    val childPath = child.canonicalFile.toPath()
    return childPath != parentPath && childPath.startsWith(parentPath)
}

internal object AtomicUtf8File {
    fun write(target: File, contents: String) {
        val parent = target.parentFile ?: throw IOException("Atomic file needs a parent directory")
        if (!parent.isDirectory) throw IOException("Atomic file parent is not a directory")
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")

        try {
            FileOutputStream(temporary).use { output ->
                output.write(contents.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
