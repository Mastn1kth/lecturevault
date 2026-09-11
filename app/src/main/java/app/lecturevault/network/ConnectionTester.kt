package app.lecturevault.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
)

class ConnectionTester {
    private val httpClient: OkHttpClient = secureHttpClient(
        connectTimeoutSeconds = 15,
        readTimeoutSeconds = 30,
        writeTimeoutSeconds = 30,
        callTimeoutSeconds = 45,
    )

    suspend fun testGroq(apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        testConnection {
            val checkedApiKey = validateApiKey(apiKey, "Groq")
            val request = Request.Builder()
                .url(GroqClient.MODELS_URL)
                .header("Authorization", "Bearer $checkedApiKey")
                .get()
                .build()

            execute(request, "Groq", checkedApiKey) { body ->
                val models = JSONObject(body).optJSONArray("data")
                    ?: throw ApiException("Groq вернул некорректный список моделей")
                val whisperAvailable = (0 until models.length()).any { index ->
                    models.optJSONObject(index)?.optString("id") == GroqClient.MODEL
                }
                if (!whisperAvailable) {
                    throw ApiException("Ключ принят, но модель ${GroqClient.MODEL} недоступна")
                }
                "Соединение с Groq работает"
            }
        }
    }

    suspend fun testGemini(
        apiKey: String,
        modelId: String = GeminiClient.DEFAULT_MODEL,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        testConnection {
            val checkedApiKey = validateApiKey(apiKey, "Gemini")
            val checkedModelId = GeminiClient.validateModelId(modelId)
            val request = Request.Builder()
                .url(GeminiClient.modelInfoUrl(checkedModelId))
                .header("x-goog-api-key", checkedApiKey)
                .get()
                .build()

            execute(request, "Gemini", checkedApiKey) { body ->
                val response = JSONObject(body)
                val methods = response.optJSONArray("supportedGenerationMethods")
                    ?: throw ApiException("Gemini вернул неполные сведения о модели")
                val supportsGenerateContent = (0 until methods.length()).any { index ->
                    methods.optString(index) == "generateContent"
                }
                if (!supportsGenerateContent) {
                    throw ApiException("Модель $checkedModelId не поддерживает generateContent")
                }
                val inputTokenLimit = response.optInt("inputTokenLimit", 0)
                val outputTokenLimit = response.optInt("outputTokenLimit", 0)
                if (inputTokenLimit < GeminiClient.REQUIRED_INPUT_TOKEN_LIMIT) {
                    throw ApiException(
                        "У модели $checkedModelId слишком маленькое контекстное окно",
                    )
                }
                if (outputTokenLimit < GeminiClient.FINAL_OUTPUT_TOKENS) {
                    throw ApiException(
                        "Модель $checkedModelId поддерживает слишком короткие ответы",
                    )
                }
                "Соединение с Gemini работает, модель $checkedModelId доступна"
            }
        }
    }

    private suspend fun testConnection(block: suspend () -> String): ConnectionTestResult = try {
        ConnectionTestResult(success = true, message = block())
    } catch (error: ApiException) {
        ConnectionTestResult(
            success = false,
            message = error.message ?: "Не удалось проверить соединение",
            statusCode = error.statusCode,
            retryable = error.retryable,
        )
    } catch (error: JSONException) {
        ConnectionTestResult(
            success = false,
            message = "Сервер вернул некорректный ответ",
        )
    }

    private suspend fun <T> execute(
        request: Request,
        serviceName: String,
        apiKey: String,
        parse: (String) -> T,
    ): T {
        try {
            httpClient.newCall(request).awaitResponse().use { response ->
                val responseBody = response.readBodyBounded()
                if (!response.isSuccessful) {
                    throw httpFailure(serviceName, response.code, responseBody, apiKey)
                }
                return parse(responseBody)
            }
        } catch (error: ApiException) {
            throw error
        } catch (error: IOException) {
            throw ioFailure(serviceName, error)
        }
    }
}
