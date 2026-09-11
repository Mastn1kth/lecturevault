package app.lecturevault.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoteWriterTest {
    @Test
    fun `writes only inside vault and removes dangerous generated markup`() {
        val vault = Files.createTempDirectory("lecture-vault-test").toFile()
        try {
            val note = NoteWriter.save(
                vault = vault,
                notesFolder = "Лекции",
                course = "Алгебра",
                summary = "# Матрицы\n<script>alert(1)</script>\n![x](https://example.com/x.png)\n[j](javascript:alert(1))",
                transcript = "Текст",
            )
            assertTrue(note.canonicalPath.startsWith(vault.canonicalPath))
            val content = note.readText()
            assertFalse(content.contains("<script", ignoreCase = true))
            assertFalse(content.contains("https://example.com"))
            assertFalse(content.contains("javascript:", ignoreCase = true))
        } finally {
            vault.deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal and obsidian service folder`() {
        val vault = Files.createTempDirectory("lecture-vault-test").toFile()
        try {
            assertFailsWith<IllegalArgumentException> { NoteWriter.save(vault, "../outside", "Курс", "# Тема", "Текст") }
            assertFailsWith<IllegalArgumentException> { NoteWriter.save(vault, ".obsidian", "Курс", "# Тема", "Текст") }
        } finally {
            vault.deleteRecursively()
        }
    }
}
