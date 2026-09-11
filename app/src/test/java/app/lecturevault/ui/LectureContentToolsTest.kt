package app.lecturevault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LectureContentToolsTest {
    @Test
    fun `timestamp parser handles compact and full timestamps`() {
        assertEquals(754L, TimestampReferenceParser.parseTime("12:34"))
        assertEquals(3_754L, TimestampReferenceParser.parseTime("01:02:34"))
        assertEquals(2, TimestampReferenceParser.partNumber("### Часть 2"))
        assertEquals(
            TimestampReference(125L, 2),
            TimestampReferenceParser.findInLine("[02:05–02:14] Определение", 2),
        )
    }

    @Test
    fun `mini test uses summary bullets and excludes full transcript`() {
        val cards = MiniTestGenerator.generate(
            """
            ---
            type: lecture
            ---

            # Матрицы

            ## Определения
            - Матрица это прямоугольная таблица чисел, у которой есть строки и столбцы.
            - Определитель квадратной матрицы помогает определить её обратимость.

            ---

            ## Полная расшифровка
            Здесь не должно появиться в карточках длинное предложение преподавателя.
            """.trimIndent(),
        )

        assertEquals(2, cards.size)
        assertTrue(cards.all { it.question.contains("Определения") })
        assertTrue(cards.none { it.answer.contains("преподавателя") })
    }
}
