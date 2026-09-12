package app.lecturevault.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

open class ApiException(
    message: String,
    val statusCode: Int? = null,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

class RetryableApiException(
    message: String,
    statusCode: Int? = null,
    cause: Throwable? = null,
) : ApiException(
    message = message,
    statusCode = statusCode,
    retryable = true,
    cause = cause,
)

internal fun validateHttpsHost(url: HttpUrl, expectedHost: String): HttpUrl {
    if (!url.isHttps || url.host != expectedHost) {
        throw ApiException("Заблокирован небезопасный адрес API")
    }
    return url
}

internal fun secureHttpClient(
    connectTimeoutSeconds: Long,
    readTimeoutSeconds: Long,
    writeTimeoutSeconds: Long,
    callTimeoutSeconds: Long,
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, responseToClose, _ ->
                responseToClose.close()
            }
        }
    })
}

/** Bounds provider-controlled response data before it reaches a JSON parser or error UI. */
internal fun Response.readBodyBounded(): String {
    val body = body ?: return ""
    if (body.contentLength() > MAX_RESPONSE_CHARS) {
        throw ApiException("Сервер вернул слишком большой ответ")
    }
    body.charStream().use { reader ->
        val buffer = CharArray(RESPONSE_READ_BUFFER_CHARS)
        val output = StringBuilder()
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > MAX_RESPONSE_CHARS) {
                throw ApiException("Сервер вернул слишком большой ответ")
            }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }
}

internal fun ioFailure(serviceName: String, cause: IOException): RetryableApiException =
    RetryableApiException(
        message = "$serviceName: не удалось связаться с сервером",
        cause = cause,
    )

private const val MAX_RESPONSE_CHARS = 2_000_000
private const val RESPONSE_READ_BUFFER_CHARS = 8_192
