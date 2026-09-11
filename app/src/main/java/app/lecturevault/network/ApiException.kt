package app.lecturevault.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
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

internal fun validateApiKey(rawApiKey: String, serviceName: String): String {
    val apiKey = rawApiKey.trim()
    if (apiKey.isEmpty()) {
        throw ApiException("Не указан API-ключ $serviceName")
    }
    if (apiKey.any(Char::isISOControl)) {
        throw ApiException("API-ключ $serviceName содержит недопустимые символы")
    }
    return apiKey
}

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

internal fun httpFailure(
    serviceName: String,
    statusCode: Int,
    responseBody: String,
    apiKey: String,
): ApiException {
    val detail = extractApiError(responseBody)
        ?.let { redactSecrets(it, apiKey) }
        ?.takeIf(String::isNotBlank)
    val message = buildString {
        append(serviceName)
        append(": ошибка HTTP ")
        append(statusCode)
        if (detail != null) {
            append(" — ")
            append(detail)
        }
    }

    return if (
        statusCode == 408 ||
        statusCode == 425 ||
        statusCode == 429 ||
        statusCode == 498 ||
        statusCode >= 500
    ) {
        RetryableApiException(message, statusCode)
    } else {
        ApiException(message, statusCode)
    }
}

internal fun ioFailure(serviceName: String, cause: IOException): RetryableApiException =
    RetryableApiException(
        message = "$serviceName: не удалось связаться с сервером",
        cause = cause,
    )

private fun extractApiError(responseBody: String): String? {
    if (responseBody.isBlank()) return null
    return runCatching {
        val root = JSONObject(responseBody)
        val error = root.optJSONObject("error")
        when {
            error != null && error.optString("message").isNotBlank() -> error.optString("message")
            root.optString("message").isNotBlank() -> root.optString("message")
            root.optString("error").isNotBlank() -> root.optString("error")
            else -> null
        }
    }.getOrNull()
}

private fun redactSecrets(message: String, apiKey: String): String = message
    .replace(apiKey, "[скрыто]")
    .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [скрыто]")
    .replace(Regex("\\b(?:gsk_|AIza)[A-Za-z0-9_-]{8,}\\b"), "[скрыто]")
    .replace(Regex("[\\r\\n\\t]+"), " ")
    .trim()
    .take(MAX_ERROR_DETAIL_CHARS)

private const val MAX_ERROR_DETAIL_CHARS = 240
private const val MAX_RESPONSE_CHARS = 2_000_000
private const val RESPONSE_READ_BUFFER_CHARS = 8_192
