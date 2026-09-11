package app.lecturevault.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionJsonCodecTest {
    @Test
    fun `round trip preserves every session field and escaped text`() {
        val original = LectureSession(
            id = "67d45bf2-3109-4d1a-8e44-889338ac9b01",
            title = "Лекция \"один\"\nстрока",
            course = "Физика \\ оптика",
            createdAt = 1_725_000_000_000L,
            startedAt = 1_725_000_000_100L,
            endedAt = 1_725_003_600_000L,
            status = SessionStatus.COMPLETE,
            segmentFiles = listOf("D:\\private\\segment_000.m4a"),
            progress = 100,
            errorMessage = null,
            noteRelativePath = "Учёба\\Конспекты\\Оптика.md",
        )

        val decoded = SessionJsonCodec.decode(SessionJsonCodec.encode(original))

        assertEquals(
            original.copy(noteRelativePath = "Учёба/Конспекты/Оптика.md"),
            decoded,
        )
    }

    @Test
    fun `decode rejects unknown schema status and duplicate fields`() {
        val valid = SessionJsonCodec.encode(sampleSession())

        assertThrows(IllegalArgumentException::class.java) {
            SessionJsonCodec.decode(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionJsonCodec.decode(valid.replace("\"status\": \"QUEUED\"", "\"status\": \"UNKNOWN\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionJsonCodec.decode(valid.replaceFirst("{", "{\"id\":\"67d45bf2-3109-4d1a-8e44-889338ac9b01\","))
        }
    }

    @Test
    fun `session validation rejects invalid UUID and progress`() {
        assertThrows(IllegalArgumentException::class.java) {
            sampleSession().copy(id = "../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleSession().copy(progress = 101)
        }
    }

    private fun sampleSession() = LectureSession(
        id = "67d45bf2-3109-4d1a-8e44-889338ac9b01",
        title = "Лекция",
        course = "Курс",
        createdAt = 10L,
        startedAt = 11L,
        endedAt = null,
        status = SessionStatus.QUEUED,
        segmentFiles = emptyList(),
        progress = 0,
        errorMessage = null,
        noteRelativePath = null,
    )
}
