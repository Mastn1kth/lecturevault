package app.lecturevault.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GatewayClientTest {
    @Test fun `transcription streams raw audio to gateway`() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"text":"привет","segments":[{"start":1,"end":2,"text":"привет"}]}"""))
        val audio = File.createTempFile("lecture", ".m4a").apply { writeBytes(byteArrayOf(1, 2)) }
        val result = runBlocking { GatewayClient(server.url("").toString().removeSuffix("/")).transcribe(audio) }
        assertEquals("привет", result.text); assertTrue(result.timestampedText.contains("00:00:01"))
        val request = server.takeRequest()
        assertEquals("/v1/transcribe", request.path)
        assertEquals("application/octet-stream", request.getHeader("Content-Type"))
        assertEquals("lecture.m4a", request.getHeader("X-Audio-Filename"))
        assertEquals(2L, request.body.size)
        audio.delete()
    } }

    @Test fun `summary sends only gateway request`() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"text":"# Тема"}"""))
        assertEquals("# Тема", runBlocking { GatewayClient(server.url("").toString().removeSuffix("/")).summarize("текст", "Алгебра") })
        val request = server.takeRequest(); assertEquals("/v1/generate", request.path); assertTrue(request.body.readUtf8().contains("summary"))
    } }

    @Test fun `gateway rate limit is retryable`() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"Дневной лимит"}"""))
        val error = runCatching { runBlocking { GatewayClient(server.url("").toString().removeSuffix("/")).summarize("текст", "Курс") } }.exceptionOrNull() as ApiException
        assertEquals(429, error.statusCode); assertTrue(error.retryable)
    } }

    @Test fun `quiz accepts exactly ten valid gateway questions`() { MockWebServer().use { server ->
        val questions = (1..10).joinToString(",") { index ->
            """{"question":"Вопрос $index","options":["А","Б","В"],"correctIndex":${index % 3}}"""
        }
        server.enqueue(MockResponse().setBody("""{"text":[$questions]}"""))
        val result = runBlocking { GatewayClient(server.url("").toString().removeSuffix("/")).generateMiniTest("# Конспект") }
        assertEquals(10, result.size); assertEquals("Вопрос 1", result.first().question)
        assertTrue(server.takeRequest().body.readUtf8().contains("quiz"))
    } }
}
