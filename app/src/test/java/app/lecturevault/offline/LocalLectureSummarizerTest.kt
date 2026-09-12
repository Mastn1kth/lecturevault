package app.lecturevault.offline

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLectureSummarizerTest {
    @Test
    fun `offline fallback creates an Obsidian-ready lecture note`() {
        val summary = LocalLectureSummarizer.summarize(
            """
            Сегодня разберём матрицы и линейные преобразования.
            Матрица — это прямоугольная таблица чисел со строками и столбцами.
            Определитель называется числом, которое связано с квадратной матрицей.
            Для умножения матриц число столбцов первой матрицы должно совпадать с числом строк второй матрицы.
            """.trimIndent(),
            course = "Линейная алгебра",
            date = "12 сентября 2026, 10:30",
        )

        assertTrue(summary.startsWith("# Матрицы и линейные преобразования"))
        assertTrue(summary.contains("**Предмет:** Линейная алгебра"))
        assertTrue(summary.contains("**Дата записи:** 12 сентября 2026, 10:30"))
        assertTrue(summary.contains("## Основные тезисы"))
        assertTrue(summary.contains("## Термины и определения"))
        assertTrue(summary.contains("Определитель называется"))
    }

    @Test
    fun `offline fallback marks sparse recognition instead of inventing facts`() {
        val summary = LocalLectureSummarizer.summarize(
            transcript = "шум",
            course = "Физика",
            date = "12 сентября 2026",
        )

        assertTrue(summary.contains("В записи недостаточно распознанного текста."))
        assertTrue(summary.contains("Сверьте формулы, имена и термины с аудиозаписью"))
    }
}
