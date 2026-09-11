package app.lecturevault.util

/** Extracts the model-selected lecture topic from the required first-level Markdown heading. */
internal object LectureTopic {
    fun fromSummary(markdown: String): String {
        val heading = markdown.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.replace(Regex("[`*_]+"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TOPIC_CHARS)
        return heading.orEmpty().ifBlank { DEFAULT_TOPIC }
    }

    private const val MAX_TOPIC_CHARS = 120
    private const val DEFAULT_TOPIC = "Лекция"
}
