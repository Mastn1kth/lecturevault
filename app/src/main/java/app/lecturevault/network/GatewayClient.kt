package app.lecturevault.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/** Public application client. Provider keys stay only in the Cloudflare Worker. */
class GatewayClient(private val baseUrl: String = BASE) {
    private val http = secureHttpClient(20, 600, 600, 900)

    suspend fun transcribe(file: File): TranscriptResult = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() in 1..MAX_AUDIO_BYTES) { "Аудиофайл пуст или больше 24 МБ" }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "lecture.${file.extension.lowercase(Locale.ROOT)}", file.asRequestBody("application/octet-stream".toMediaType())).build()
        val raw = execute(Request.Builder().url("$baseUrl/v1/transcribe").post(form).build())
        val root = JSONObject(raw); val text = root.optString("text").trim()
        if (text.isEmpty()) throw ApiException("Сервер вернул пустую расшифровку")
        val lines = buildList {
            val segments = root.optJSONArray("segments")
            for (i in 0 until (segments?.length() ?: 0)) {
                val item = segments?.optJSONObject(i) ?: continue
                add("[${clock(item.optDouble("start"))}–${clock(item.optDouble("end"))}] ${item.optString("text").trim()}")
            }
        }
        TranscriptResult(text, lines.joinToString("\n").ifBlank { text }, 0.0)
    }

    suspend fun summarize(transcript: String, course: String): String = generate("summary", JSONObject().put("transcript", transcript).put("course", course))

    suspend fun generateMiniTest(markdown: String): List<MultipleChoiceQuestion> = withContext(Dispatchers.IO) {
        val raw = generate("quiz", JSONObject().put("markdown", markdown))
        val array = org.json.JSONArray(raw.removePrefix("```json").removeSuffix("```").trim())
        if (array.length() != 10) throw ApiException("Сервер вернул не 10 вопросов")
        List(10) { index ->
            val item = array.getJSONObject(index); val optionsJson = item.getJSONArray("options")
            val options = List(3) { option -> optionsJson.getString(option).trim() }
            val correct = item.optInt("correctIndex", -1)
            if (item.optString("question").trim().isEmpty() || options.any(String::isBlank) || correct !in 0..2) throw ApiException("Сервер вернул некорректный тест")
            MultipleChoiceQuestion(item.getString("question").trim(), options, correct)
        }
    }

    private suspend fun generate(task: String, payload: JSONObject): String = withContext(Dispatchers.IO) {
        payload.put("task", task)
        val raw = execute(Request.Builder().url("$baseUrl/v1/generate").header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build())
        JSONObject(raw).optString("text").trim().ifBlank { throw ApiException("Сервер вернул пустой конспект") }
    }

    private fun execute(request: Request): String = try {
        http.newCall(request).execute().use { response ->
            val body = response.readBodyBounded()
            if (!response.isSuccessful) throw ApiException("Сервер ИИ: ${JSONObject(body).optString("error", "HTTP ${response.code}")}", response.code, response.code == 429 || response.code >= 500)
            body
        }
    } catch (error: ApiException) { throw error } catch (error: IOException) { throw ioFailure("Сервер ИИ", error) }

    private fun clock(seconds: Double): String { val s = seconds.toLong().coerceAtLeast(0); return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }
    companion object { private const val BASE = "https://lecturevault-ai-gateway.aleksandrsimunin828.workers.dev"; private const val MAX_AUDIO_BYTES = 24L * 1024 * 1024 }
}
