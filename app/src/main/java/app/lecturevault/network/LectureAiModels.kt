package app.lecturevault.network

/** A provider-neutral transcription result returned by the LectureVault gateway. */
data class TranscriptResult(
    val text: String,
    val timestampedText: String,
    val durationSeconds: Double,
)

/** One validated question from the gateway mini-test response. */
data class MultipleChoiceQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
)
