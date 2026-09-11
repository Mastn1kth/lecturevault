package app.lecturevault.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class NetworkClientsTest {
    @Test
    fun `Groq verbose JSON is converted to plain and timestamped text`() {
        val result = GroqClient("gsk_test_key").parseTranscript(
            """
                {
                  "text": "Первый тезис. Второй тезис.",
                  "duration": 12.4,
                  "segments": [
                    {"start": 0.2, "end": 4.6, "text": " Первый тезис. "},
                    {"start": 5.0, "end": 12.4, "text": " Второй тезис. "}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("Первый тезис. Второй тезис.", result.text)
        assertEquals(
            "[00:00:00–00:00:05] Первый тезис.\n" +
                "[00:00:05–00:00:12] Второй тезис.",
            result.timestampedText,
        )
        assertEquals(12.4, result.durationSeconds, 0.0001)
    }

    @Test
    fun `Groq builds plain text and duration from segments when top-level fields are absent`() {
        val result = GroqClient("gsk_test_key").parseTranscript(
            """{"segments":[{"start":1,"end":3.5,"text":"Тезис"}]}""",
        )

        assertEquals("Тезис", result.text)
        assertEquals(3.5, result.durationSeconds, 0.0001)
    }

    @Test
    fun `Gemini collects every non-thought text part`() {
        val result = GeminiClient("test_key").parseGeneratedText(
            """
                {
                  "candidates": [
                    {
                      "finishReason": "STOP",
                      "content": {"parts": [
                        {"text": "# Заголовок"},
                        {"thought": true, "text": "Скрытое рассуждение"},
                        {"text": "## Кратко"}
                      ]}
                    },
                    {
                      "finishReason": "STOP",
                      "content": {"parts": [{"text": "Итог"}]}
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals("# Заголовок\n\n## Кратко\n\nИтог", result)
        assertFalse(result.contains("Скрытое рассуждение"))
    }

    @Test
    fun `Gemini reports prompt blocking and incomplete candidates`() {
        val client = GeminiClient("test_key")

        val blocked = assertThrows(ApiException::class.java) {
            client.parseGeneratedText(
                """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            )
        }
        val truncated = assertThrows(ApiException::class.java) {
            client.parseGeneratedText(
                """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"часть"}]}}]}""",
            )
        }
        val unfinished = assertThrows(ApiException::class.java) {
            client.parseGeneratedText(
                """{"candidates":[{"content":{"parts":[{"text":"часть"}]}}]}""",
            )
        }

        assertTrue(blocked.message.orEmpty().contains("SAFETY"))
        assertTrue(truncated.message.orEmpty().contains("MAX_TOKENS"))
        assertTrue(unfinished.message.orEmpty().contains("UNKNOWN"))
    }

    @Test
    fun `Gemini quiz parser accepts exactly ten three-option questions`() {
        val question = "{\"question\":\"Что изучает линейная алгебра?\",\"options\":[\"Векторные пространства\",\"Только орфографию\",\"Погоду\"],\"correct_index\":0}"
        val raw = "{\"questions\":[${List(10) { index -> question.replace("алгебра?", "алгебра $index?") }.joinToString(",") }]}"

        val parsed = GeminiClient("test_key").parseMiniTest(raw)

        assertEquals(10, parsed.size)
        assertEquals(3, parsed.first().options.size)
        assertEquals(0, parsed.first().correctIndex)
    }

    @Test
    fun `Gemini quiz parser rejects questions with a wrong option count`() {
        val question = "{\"question\":\"Что изучает линейная алгебра?\",\"options\":[\"Векторы\",\"Погоду\"],\"correct_index\":0}"
        val raw = "{\"questions\":[${List(10) { index -> question.replace("алгебра?", "алгебра $index?") }.joinToString(",") }]}"

        assertThrows(ApiException::class.java) {
            GeminiClient("test_key").parseMiniTest(raw)
        }
    }

    @Test
    fun `Gemini quiz parser accepts a JSON code fence`() {
        val question = "{\"question\":\"Что изучает линейная алгебра?\",\"options\":[\"Векторы\",\"Погоду\",\"Орфографию\"],\"correct_index\":0}"
        val body = "{\"questions\":[${List(10) { index -> question.replace("алгебра?", "алгебра $index?") }.joinToString(",") }]}"

        assertEquals(10, GeminiClient("test_key").parseMiniTest("```JSON\n$body\n```").size)
    }

    @Test
    fun `long transcript splitting keeps content and respects requested bound`() {
        val client = GeminiClient("test_key")
        val original = "А".repeat(90_000) + "\n\n" + "Б".repeat(80_000)

        val chunks = client.splitText(original, 120_000)

        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 120_000 })
        assertEquals(original.filterNot(Char::isWhitespace), chunks.joinToString("").filterNot(Char::isWhitespace))
    }

    @Test
    fun `retryable statuses are classified and API keys are redacted`() {
        val apiKey = "gsk_never_show_this_value"
        val retryable = httpFailure("Groq", 503, "", apiKey)
        val permanent = httpFailure(
            "Groq",
            401,
            """{"error":{"message":"Недействительный ключ $apiKey"}}""",
            apiKey,
        )

        assertTrue(retryable is RetryableApiException)
        assertTrue(retryable.retryable)
        assertEquals(503, retryable.statusCode)
        assertFalse(permanent.retryable)
        assertEquals(401, permanent.statusCode)
        assertFalse(permanent.message.orEmpty().contains(apiKey))
        assertTrue(httpFailure("Groq", 498, "", apiKey).retryable)
    }

    @Test
    fun `only expected HTTPS host and safe Gemini model IDs are accepted`() {
        val validUrl = "https://api.groq.com/openai/v1/models".toHttpUrl()
        assertEquals(validUrl, validateHttpsHost(validUrl, "api.groq.com"))
        assertEquals(
            "gemini-3.5-flash-lite",
            GeminiClient.validateModelId("models/gemini-3.5-flash-lite"),
        )

        assertThrows(ApiException::class.java) {
            validateHttpsHost("http://api.groq.com/openai/v1/models".toHttpUrl(), "api.groq.com")
        }
        assertThrows(ApiException::class.java) {
            validateHttpsHost("https://evil.example/openai/v1/models".toHttpUrl(), "api.groq.com")
        }
        assertThrows(ApiException::class.java) {
            GeminiClient.validateModelId("gemini-safe:generateContent?key=stolen")
        }
    }

    @Test
    fun `merge grouping splits oversized summaries to guarantee reducible groups`() {
        val client = GeminiClient("test_key")
        val groups = client.groupByCharacterLimit(
            items = listOf("А".repeat(90_000), "Б".repeat(90_000)),
            maxChars = 120_000,
        )

        assertTrue(groups.all { group -> group.sumOf(String::length) <= 120_000 })
        assertTrue(groups.all { it.size >= 2 })
    }

    @Test
    fun `cancelling coroutine cancels active OkHttp call`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val call = OkHttpClient().newCall(
                Request.Builder().url(server.url("/slow")).build(),
            )
            val job = launch { call.awaitResponse().close() }
            delay(100)

            job.cancelAndJoin()

            assertTrue(call.isCanceled())
        } finally {
            server.shutdown()
        }
    }
}
