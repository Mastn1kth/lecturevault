package app.lecturevault.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LectureTopicTest {
    @Test
    fun `takes the first top-level heading as topic`() {
        assertEquals(
            "Собственные значения и собственные векторы",
            LectureTopic.fromSummary("# Собственные значения и собственные векторы\n\n## Кратко"),
        )
    }

    @Test
    fun `ignores subheadings and uses a safe fallback`() {
        assertEquals("Лекция", LectureTopic.fromSummary("## Кратко\nТекст без темы"))
    }

    @Test
    fun `removes markdown decoration from topic`() {
        assertEquals("Определители", LectureTopic.fromSummary("# **Определители**\nТекст"))
    }
}
