package app.lecturevault.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GatewayClientTest {
    @Test fun `transcription uses gateway multipart endpoint`() { MockWebServer().use { server ->
        server.enqueue(MockResponse().setBody("""{"text":"привет","segments":[{"start":1,"end":2,"text":"привет"}]}"""))
        val audio = File.createTempFile("lecture", ".m4a").apply { writeBytes(byteArrayOf(1, 2)) }
        val result = runBlocking { GatewayClient(server.url("").toString().removeSuffix("/")).transcribe(audio) }
        assertEquals("привет", result.text); assertTrue(result.timestampedText.contains("00:00:01"))
        assertEquals("/v1/transcribe", server.takeRequest().path); audio.delete()
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
}
