package app.lecturevault.offline

import android.content.Context
import app.lecturevault.network.ApiException
import app.lecturevault.network.awaitResponse
import app.lecturevault.network.secureHttpClient
import app.lecturevault.network.validateHttpsHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** Installs the speech model once; audio never leaves the device after installation. */
class OfflineModelManager(context: Context) {
    private val appContext = context.applicationContext
    private val modelsRoot = File(appContext.filesDir, "offline_models")
    private val modelDirectory = File(modelsRoot, MODEL_DIRECTORY_NAME)

    fun isInstalled(): Boolean =
        File(modelDirectory, "am/final.mdl").isFile && File(modelDirectory, "conf/model.conf").isFile

    fun statusText(): String = when {
        isInstalled() -> "Русская модель: установлена"
        else -> "Русская модель: не установлена"
    }

    suspend fun download(onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext
        check(appContext.filesDir.usableSpace >= MIN_FREE_SPACE_BYTES) {
            "Для модели нужно освободить не менее 500 МБ памяти"
        }
        if (!modelsRoot.exists()) check(modelsRoot.mkdirs()) { "Не удалось создать папку модели" }
        val archive = File(modelsRoot, "$MODEL_DIRECTORY_NAME.zip.part")
        val staging = File(modelsRoot, "$MODEL_DIRECTORY_NAME.installing")
        archive.delete()
        staging.deleteRecursively()
        try {
            val request = Request.Builder().url(MODEL_URL).get().build()
            httpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) throw ApiException("Не удалось скачать модель: HTTP ${response.code}")
                val total = response.body?.contentLength()?.takeIf { it in 1..MAX_ARCHIVE_BYTES }
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            check(downloaded <= MAX_ARCHIVE_BYTES) { "Архив модели слишком большой" }
                            output.write(buffer, 0, count)
                            onProgress(downloaded, total)
                        }
                        output.fd.sync()
                    }
                } ?: throw ApiException("Сервер не вернул модель")
            }
            unzipModel(archive, staging)
            val extracted = File(staging, MODEL_DIRECTORY_NAME)
            check(File(extracted, "am/final.mdl").isFile && File(extracted, "conf/model.conf").isFile) {
                "Архив не содержит корректную модель речи"
            }
            modelDirectory.deleteRecursively()
            Files.move(extracted.toPath(), modelDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    fun modelPath(): String {
        check(isInstalled()) { "Сначала скачайте русскую модель речи" }
        return modelDirectory.absolutePath
    }

    private fun unzipModel(archive: File, staging: File) {
        check(staging.mkdirs()) { "Не удалось подготовить установку модели" }
        val root = staging.canonicalFile
        var unpacked = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                check(target.toPath().startsWith(root.toPath())) { "Небезопасный путь в архиве модели" }
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory) { "Не удалось распаковать модель" }
                } else {
                    target.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "Не удалось распаковать модель" } }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            unpacked += count
                            check(unpacked <= MAX_UNPACKED_BYTES) { "Модель после распаковки слишком большая" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val MODEL_HOST = "alphacephei.com"
        private const val MODEL_DIRECTORY_NAME = "vosk-model-small-ru-0.22"
        private const val MODEL_URL_STRING = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
        private val MODEL_URL = validateHttpsHost(MODEL_URL_STRING.toHttpUrl(), MODEL_HOST)
        private val httpClient = secureHttpClient(20, 300, 300, 600)
        private const val BUFFER_BYTES = 32 * 1024
        private const val MAX_ARCHIVE_BYTES = 80L * 1024L * 1024L
        private const val MAX_UNPACKED_BYTES = 350L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 500L * 1024L * 1024L
    }
}
