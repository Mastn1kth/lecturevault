package app.lecturevault.ui

data class TimestampReference(
    val seconds: Long,
    val partNumber: Int?,
)

object TimestampReferenceParser {
    private val partHeading = Regex("^#{1,3}\\s*Часть\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)
    private val timestamp = Regex("\\[(\\d{1,2}:\\d{2}(?::\\d{2})?)(?:\\s*[–-]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?)?]")

    fun findInLine(line: String, currentPart: Int?): TimestampReference? {
        val match = timestamp.find(line) ?: return null
        return parseTime(match.groupValues[1])?.let { TimestampReference(it, currentPart) }
    }

    fun partNumber(line: String): Int? = partHeading.matchEntire(line.trim())
        ?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun parseTime(value: String): Long? {
        val pieces = value.split(':').map { it.toLongOrNull() ?: return null }
        return when (pieces.size) {
            2 -> (pieces[0] * 60) + pieces[1]
            3 -> (pieces[0] * 3_600) + (pieces[1] * 60) + pieces[2]
            else -> null
        }?.takeIf { it >= 0L }
    }
}

data class MiniTestCard(
    val question: String,
    val answer: String,
)

/** Builds revision cards locally from the compact part of a saved lecture note. */
object MiniTestGenerator {
    private const val MAX_CARDS = 6

    fun generate(markdown: String): List<MiniTestCard> {
        val summary = markdown
            .removeFrontMatter()
            .substringBefore("\n---\n\n## Полная расшифровка")
            .trim()
        var currentHeading = "основной материал лекции"
        val cards = linkedSetOf<MiniTestCard>()
        summary.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#") -> currentHeading = line.removePrefix("#").trimStart('#', ' ', '*').trim().ifBlank { currentHeading }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val answer = clean(line.drop(2))
                    if (isUseful(answer) && cards.size < MAX_CARDS) {
                        cards += MiniTestCard("Что важно запомнить в разделе «$currentHeading»?", answer)
                    }
                }
            }
        }
        if (cards.isEmpty()) {
            summary.split(Regex("(?<=[.!?])\\s+|\\n+"))
                .map(::clean)
                .filter(::isUseful)
                .take(MAX_CARDS)
                .forEach { answer -> cards += MiniTestCard("Какой тезис был в лекции?", answer) }
        }
        return cards.toList()
    }

    private fun String.removeFrontMatter(): String {
        if (!startsWith("---")) return this
        val end = indexOf("\n---", startIndex = 3)
        return if (end >= 0) substring(end + 4).trimStart() else this
    }

    private fun clean(value: String): String = value
        .replace(Regex("[`*_]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isUseful(value: String): Boolean = value.length in 24..420 && value.count(Char::isLetter) >= 12
}
