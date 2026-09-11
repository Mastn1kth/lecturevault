package app.lecturevault.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioExporterTest {
    @Test
    fun `audio export name is portable and has a fallback`() {
        assertEquals("Линейная алгебра матрицы", AudioExporter.safeName(" Линейная алгебра: матрицы? "))
        assertEquals("Лекция", AudioExporter.safeName("..."))
    }
}
