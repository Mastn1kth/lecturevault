package app.lecturevault.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultBootstrapTest {
    @Test
    fun `creates an Obsidian vault and keeps existing vaults untouched`() {
        val base = Files.createTempDirectory("lecturevault-bootstrap").toFile()

        val first = createLectureVault(base)
        val second = createLectureVault(base)

        assertEquals("LectureVault", first.name)
        assertEquals("LectureVault (2)", second.name)
        listOf(first, second).forEach { vault ->
            assertTrue(vault.resolve(".obsidian").isDirectory)
            assertTrue(vault.resolve("Лекции").isDirectory)
        }
    }
}
