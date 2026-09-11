package app.lecturevault.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import okio.source
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToLong

data class TranscriptResult(
    val text: String,
    val timestampedText: String,
    val durationSeconds: Double,
)

class GroqClient(apiKey: String) {
    private val apiKey = validateApiKey(apiKey, SERVICE_NAME)
    private val httpClient: OkHttpClient = secureHttpClient(
        connectTimeoutSeconds = 20,
        readTimeoutSeconds = 600,
        writeTimeoutSeconds = 600,
        callTimeoutSeconds = 900,
    )

    suspend fun transcribe(audioFile: File): TranscriptResult = withContext(Dispatchers.IO) {
        validateAudioFile(audioFile)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", LANGUAGE)
            .addFormDataPart("prompt", TRANSCRIPTION_CONTEXT)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart(
                "file",
                uploadFileName(audioFile),
                StreamingFileRequestBody(audioFile, mediaTypeFor(audioFile)),
            )
            .build()

        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).awaitResponse().use { response ->
                val responseBody = response.readBodyBounded()
                if (!response.isSuccessful) {
                    throw httpFailure(SERVICE_NAME, response.code, responseBody, apiKey)
                }
                parseTranscript(responseBody)
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ioFailure(SERVICE_NAME, error)
        }
    }

    private fun validateAudioFile(audioFile: File) {
        if (!audioFile.isFile || !audioFile.canRead()) {
            throw ApiException("Аудиофайл не найден или недоступен для чтения")
        }
        val length = audioFile.length()
        if (length <= 0L) {
            throw ApiException("Аудиофайл пуст")
        }
        if (length > MAX_AUDIO_BYTES) {
            throw ApiException(
                "Аудиофайл больше 24 МБ. Запись должна быть разбита на части до отправки",
            )
        }
        if (audioFile.extension.lowercase(Locale.ROOT) !in SUPPORTED_EXTENSIONS) {
            throw ApiException("Формат аудиофайла не поддерживается Groq")
        }
    }

    internal fun parseTranscript(rawJson: String): TranscriptResult {
        try {
            val root = JSONObject(rawJson)
            val segmentsJson = root.optJSONArray("segments")
            val segmentTexts = mutableListOf<String>()
            val timestampedLines = mutableListOf<String>()
            var lastSegmentEnd = 0.0

            if (segmentsJson != null) {
                for (index in 0 until segmentsJson.length()) {
                    val segment = segmentsJson.optJSONObject(index) ?: continue
                    val segmentText = segment.optString("text").trim()
                    if (segmentText.isEmpty()) continue

                    val start = finiteNonNegative(segment.optDouble("start", 0.0))
                    val end = finiteNonNegative(segment.optDouble("end", start)).coerceAtLeast(start)
                    lastSegmentEnd = maxOf(lastSegmentEnd, end)
                    segmentTexts += segmentText
                    timestampedLines += "[${formatTimestamp(start)}–${formatTimestamp(end)}] $segmentText"
                }
            }

            val plainText = root.optString("text").trim().ifEmpty {
                segmentTexts.joinToString(" ")
            }
            if (plainText.isEmpty()) {
                throw ApiException("Groq вернул пустую расшифровку")
            }

            val declaredDuration = finiteNonNegative(root.optDouble("duration", 0.0))
            return TranscriptResult(
                text = plainText,
                timestampedText = timestampedLines.joinToString("\n").ifEmpty { plainText },
                durationSeconds = maxOf(declaredDuration, lastSegmentEnd),
            )
        } catch (error: ApiException) {
            throw error
        } catch (error: JSONException) {
            throw ApiException("Groq вернул некорректный JSON", cause = error)
        }
    }

    private fun finiteNonNegative(value: Double): Double =
        if (value.isFinite() && value >= 0.0) value else 0.0

    private fun formatTimestamp(seconds: Double): String {
        val totalSeconds = seconds.coerceAtLeast(0.0).roundToLong()
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val remainingSeconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainingSeconds)
    }

    private fun uploadFileName(audioFile: File): String =
        "lecture.${audioFile.extension.lowercase(Locale.ROOT)}"

    private fun mediaTypeFor(audioFile: File) = when (audioFile.extension.lowercase(Locale.ROOT)) {
        "m4a", "mp4" -> "audio/mp4"
        "mp3", "mpeg", "mpga" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }.toMediaType()

    private class StreamingFileRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType,
    ) : RequestBody() {
        private val expectedLength = file.length()

        override fun contentType() = mediaType

        override fun contentLength(): Long = expectedLength

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                val written = sink.writeAll(source)
                if (written != expectedLength) {
                    throw IOException("Размер аудиофайла изменился во время отправки")
                }
            }
        }
    }

    companion object {
        const val MODEL = "whisper-large-v3-turbo"
        const val MAX_AUDIO_BYTES = 24L * 1024L * 1024L

        private const val SERVICE_NAME = "Groq"
        private const val TRANSCRIPTION_CONTEXT = "Русская университетская лекция. Термины, определения, формулы и вопросы по теме."
        private const val GROQ_HOST = "api.groq.com"
        private const val LANGUAGE = "ru"
        private val SUPPORTED_EXTENSIONS = setOf(
            "flac",
            "mp3",
            "mp4",
            "mpeg",
            "mpga",
            "m4a",
            "ogg",
            "wav",
            "webm",
        )
        internal val TRANSCRIPTIONS_URL = validateHttpsHost(
            "https://api.groq.com/openai/v1/audio/transcriptions".toHttpUrl(),
            GROQ_HOST,
        )
        internal val MODELS_URL = validateHttpsHost(
            "https://api.groq.com/openai/v1/models".toHttpUrl(),
            GROQ_HOST,
        )
    }
}
