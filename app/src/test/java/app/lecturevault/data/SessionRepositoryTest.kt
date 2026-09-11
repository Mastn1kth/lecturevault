package app.lecturevault.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `repository persists updates and returns newest sessions first`() {
        val root = temporaryFolder.newFolder("sessions")
        var time = 100L
        val ids = ArrayDeque(
            listOf(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
            ),
        )
        val repository = SessionRepository(root, { time }, { ids.removeFirst() })
        val first = repository.create(" First ", " Course ")
        time = 200L
        val second = repository.create("Second", "Course")

        val updated = repository.update(first.id) {
            it.copy(status = SessionStatus.TRANSCRIBING, progress = 35, errorMessage = " temporary ")
        }

        assertEquals("First", updated.title)
        assertEquals("temporary", updated.errorMessage)
        assertEquals(updated, repository.get(first.id))
        assertEquals(listOf(second.id, first.id), repository.list().map(LectureSession::id))
        assertTrue(File(repository.sessionDir(first.id), SessionRepository.SESSION_FILE_NAME).isFile)
    }

    @Test
    fun `imported session keeps source date and starts queued`() {
        val root = temporaryFolder.newFolder("imports")
        val repository = repository(root)

        val imported = repository.createImported(" Линейная алгебра ", 42_000L)

        assertEquals("Линейная алгебра", imported.course)
        assertEquals(42_000L, imported.createdAt)
        assertEquals(42_000L, imported.endedAt)
        assertEquals(SessionStatus.QUEUED, imported.status)
    }

    @Test
    fun `segments must exist inside their UUID session directory`() {
        val root = temporaryFolder.newFolder("sessions")
        val repository = repository(root)
        val session = repository.create("Title", "Course")
        val segment = repository.newSegmentFile(session.id, 0).apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val updated = repository.addSegment(session.id, segment.absolutePath)
        assertEquals(listOf(segment.canonicalPath), updated.segmentFiles)

        val outside = temporaryFolder.newFile("outside.m4a").apply { writeBytes(byteArrayOf(4)) }
        assertThrows(IllegalArgumentException::class.java) {
            repository.addSegment(session.id, outside.absolutePath)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.get("../${session.id}")
        }
    }

    @Test
    fun `two repository instances serialize concurrent writes`() {
        val root = temporaryFolder.newFolder("sessions")
        val firstRepository = repository(root)
        val session = firstRepository.create("Title", "Course")
        val secondRepository = SessionRepository(root)
        val segments = (0 until 24).map { index ->
            firstRepository.newSegmentFile(session.id, index).apply { writeBytes(byteArrayOf(index.toByte())) }
        }
        val executor = Executors.newFixedThreadPool(8)

        try {
            val futures = segments.mapIndexed { index, file ->
                executor.submit {
                    val target = if (index % 2 == 0) firstRepository else secondRepository
                    target.addSegment(session.id, file.absolutePath)
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val stored = checkNotNull(firstRepository.get(session.id))
        assertNotNull(stored)
        assertEquals(segments.map { it.canonicalPath }.toSet(), stored.segmentFiles.toSet())
        assertEquals(segments.size, stored.segmentFiles.size)
    }

    @Test
    fun `list ignores incomplete or corrupt session directories`() {
        val root = temporaryFolder.newFolder("sessions")
        val repository = repository(root)
        val valid = repository.create("Title", "Course")
        File(root, "33333333-3333-4333-8333-333333333333").mkdirs()
        File(root, "44444444-4444-4444-8444-444444444444").apply {
            mkdirs()
            resolve(SessionRepository.SESSION_FILE_NAME).writeText("not json")
        }

        assertEquals(listOf(valid.id), repository.list().map(LectureSession::id))
    }

    @Test
    fun `atomic writer replaces complete UTF-8 content and removes temp file`() {
        val directory = temporaryFolder.newFolder("atomic")
        val target = File(directory, "session.json")
        AtomicUtf8File.write(target, "первый")
        AtomicUtf8File.write(target, "второй")

        assertEquals("второй", target.readText(Charsets.UTF_8))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `atomic writer refuses target without an existing parent`() {
        val target = File(temporaryFolder.root, "missing/session.json")
        assertThrows(IOException::class.java) { AtomicUtf8File.write(target, "{}") }
    }

    @Test
    fun `secret envelope codec round trips binary fields and rejects malformed records`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it * 3).toByte() }
        val decoded = SecretEnvelopeCodec.decode(
            version = 1,
            encodedIv = SecretEnvelopeCodec.encode(iv),
            encodedCiphertext = SecretEnvelopeCodec.encode(ciphertext),
        )

        assertNotNull(decoded)
        assertArrayEquals(iv, decoded!!.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
        assertEquals(null, SecretEnvelopeCodec.decode(2, SecretEnvelopeCodec.encode(iv), SecretEnvelopeCodec.encode(ciphertext)))
        assertEquals(null, SecretEnvelopeCodec.decode(1, "not-base64", SecretEnvelopeCodec.encode(ciphertext)))
        assertEquals(null, SecretEnvelopeCodec.decode(1, SecretEnvelopeCodec.encode(ByteArray(8)), SecretEnvelopeCodec.encode(ciphertext)))
    }

    @Test
    fun `api credential normalization removes pasted whitespace and controls`() {
        assertEquals("gsk_example-key", normalizeApiCredential("  gsk_example-\nkey\t\u0000 "))
        assertEquals("", normalizeApiCredential(" \r\n\t"))
    }

    @Test
    fun `vault path normalization blocks traversal`() {
        assertEquals("Учёба/Конспекты", SessionValues.normalizeVaultRelativePath("/Учёба\\Конспекты/"))
        assertThrows(IllegalArgumentException::class.java) {
            SessionValues.normalizeVaultRelativePath("Учёба/../Другое")
        }
    }

    @Test
    fun `delete removes metadata and audio only for the selected session`() {
        val root = temporaryFolder.newFolder("delete-sessions")
        val ids = ArrayDeque(
            listOf(
                "55555555-5555-4555-8555-555555555555",
                "66666666-6666-4666-8666-666666666666",
            ),
        )
        val repository = SessionRepository(root, { 1_000L }, { ids.removeFirst() })
        val deleted = repository.create("Delete", "Course")
        val kept = repository.create("Keep", "Course")
        repository.newSegmentFile(deleted.id, 0).apply { writeBytes(byteArrayOf(1, 2, 3)) }

        repository.delete(deleted.id)

        assertEquals(null, repository.get(deleted.id))
        assertFalse(File(root, deleted.id).exists())
        assertNotNull(repository.get(kept.id))
    }

    private fun repository(root: File): SessionRepository = SessionRepository(
        sessionsRoot = root,
        clock = { 1_000L },
    )
}
