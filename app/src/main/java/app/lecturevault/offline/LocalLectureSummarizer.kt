package app.lecturevault.offline

import app.lecturevault.util.LectureTopic
import java.util.Locale

/** On-device extractive lecture analyser. It deliberately never calls a remote model. */
internal object LocalLectureSummarizer {
    fun summarize(transcript: String, course: String, date: String): String {
        val sentences = splitSentences(transcript)
        val important = selectImportant(sentences)
        val topic = detectTopic(sentences, important)
        val definitions = sentences.filter { definitionMarker.containsMatchIn(it) }.take(8)
        return buildString {
            appendLine("# $topic")
            appendLine()
            appendLine("**Предмет:** ${course.ifBlank { "Не указан" }}")
            appendLine("**Дата записи:** $date")
            appendLine()
            appendLine("## Кратко")
            appendLine(important.take(3).joinToString(" ").ifBlank { "В записи недостаточно распознанного текста." })
            appendLine()
            appendLine("## Основные тезисы")
            important.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Термины и определения")
            if (definitions.isEmpty()) appendLine("- Автоматически не выделены.")
            else definitions.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## Что стоит проверить")
            appendLine("- Сверьте формулы, имена и термины с аудиозаписью: локальная модель речи может ошибаться.")
        }
    }

    private fun detectTopic(sentences: List<String>, important: List<String>): String {
        val cue = sentences.firstOrNull { topicMarker.containsMatchIn(it) }
        val source = cue ?: important.firstOrNull() ?: return "Лекция"
        val cleaned = source
            .replace(topicMarker, "")
            .replace(Regex("[.!?].*"), "")
            .trim(' ', ':', '-', '—')
            .take(100)
        return LectureTopic.fromSummary("# $cleaned")
    }

    private fun selectImportant(sentences: List<String>): List<String> {
        if (sentences.isEmpty()) return emptyList()
        val frequencies = sentences.flatMap(::words)
            .filter { it.length >= 4 && it !in stopWords }
            .groupingBy { it }.eachCount()
        return sentences.mapIndexed { index, sentence ->
            val score = words(sentence).distinct().sumOf { frequencies[it] ?: 0 } +
                if (definitionMarker.containsMatchIn(sentence)) 8 else 0
            index to score
        }.sortedByDescending { it.second }
            .take(MAX_KEY_SENTENCES)
            .sortedBy { it.first }
            .map { sentences[it.first] }
    }

    private fun splitSentences(text: String): List<String> =
        text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim).filter { it.length >= MIN_SENTENCE_CHARS }.take(MAX_SENTENCES)

    private fun words(value: String): List<String> =
        value.lowercase(Locale("ru", "RU")).split(Regex("[^а-яёa-z0-9]+"))
            .filter(String::isNotBlank)

    private const val MAX_KEY_SENTENCES = 12
    private const val MAX_SENTENCES = 500
    private const val MIN_SENTENCE_CHARS = 20
    private val topicMarker = Regex("""(?i).*?(?:тема(?:\s+лекции)?|сегодня\s+(?:мы\s+)?(?:разбер[её]м|изуч[аи]м)|поговорим\s+о)[:\s-]*""")
    private val definitionMarker = Regex("(?i)\\b(?:это|называется|определяется|определение)\\b")
    private val stopWords = setOf("который", "которая", "которые", "потому", "потом", "тогда", "также", "этого", "этот", "эта", "быть", "будет", "нужно", "можно", "просто", "сейчас", "чтобы", "когда", "здесь", "теперь", "первый", "второй")
}
