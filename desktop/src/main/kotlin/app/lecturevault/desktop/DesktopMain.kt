package app.lecturevault.desktop

import com.formdev.flatlaf.FlatDarkLaf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread

import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    System.setProperty("flatlaf.useWindowDecorations", "true")
    FlatDarkLaf.setup()
    javax.swing.JFrame.setDefaultLookAndFeelDecorated(true)
    configureDesktopTheme()
    if (args.firstOrNull() == "--render-previews") {
        renderDesktopPreviews(File(args.getOrElse(1) { "desktop/build/previews" }))
        return
    }
    SwingUtilities.invokeLater { LectureVaultWindow().isVisible = true }
}

internal class DesktopSettings {
    private val root = File(System.getProperty("user.home"), ".lecturevault").apply { mkdirs() }
    private val file = File(root, "settings.properties")
    private val properties = Properties().apply { if (file.isFile) file.inputStream().use(::load) }

    var subject: String
        get() = properties.getProperty("subject", "")
        set(value) { properties.setProperty("subject", value); persist() }
    val vault: File? get() = properties.getProperty("vault")?.let(::File)?.takeIf(File::isDirectory)
    val notesFolder: String get() = properties.getProperty("notes", "Лекции")
    val cloudConsent: Boolean get() = properties.getProperty("consent", "false").toBoolean()
    fun workingDirectory(): File = File(root, "recordings").apply { mkdirs() }

    fun save(vault: File, notes: String, consent: Boolean) {
        require(".." !in notes.replace('\\', '/').split('/')) { "Недопустимый путь конспектов" }
        require(consent) { "Подтвердите отправку аудио и текста на сервер ИИ" }
        properties.setProperty("vault", vault.absolutePath)
        properties.setProperty("notes", notes.ifBlank { "Лекции" })
        properties.setProperty("consent", "true")
        // These were used by older desktop releases. Provider keys now stay on the gateway.
        properties.remove("groq")
        properties.remove("gemini")
        persist()
    }

    fun disableCloudProcessing() {
        properties.setProperty("consent", "false")
        persist()
    }

    private fun persist() = file.outputStream().use { properties.store(it, "LectureVault desktop") }
}

internal class SegmentedRecorder(private val directory: File) {
    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
    private val files = mutableListOf<File>()
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        line.open(format)
        line.start()
        running = true
        worker = thread(name = "lecture-recorder", isDaemon = true) {
            var output = newSegment()
            var bytes = 0L
            val buffer = ByteArray(8_192)
            while (running) {
                val count = line.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                if (bytes + count > MAX_SEGMENT_BYTES) {
                    output.close(); patchWave(files.last(), bytes); output = newSegment(); bytes = 0
                }
                output.write(buffer, 0, count); bytes += count
            }
            output.close(); patchWave(files.last(), bytes)
        }
    }

    fun stop(): List<File> {
        running = false
        line.stop(); line.close(); worker?.join(5_000)
        return files.filter { it.length() > 44 }
    }

    private fun newSegment(): java.io.RandomAccessFile {
        val file = File(directory, "${System.currentTimeMillis()}-${files.size + 1}.wav")
        files += file
        return java.io.RandomAccessFile(file, "rw").apply { write(ByteArray(44)) }
    }

    private fun patchWave(file: File, dataSize: Long) = java.io.RandomAccessFile(file, "rw").use { out ->
        fun int(value: Long) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())) }
        fun short(value: Int) { out.write(byteArrayOf(value.toByte(), (value shr 8).toByte())) }
        out.seek(0); out.writeBytes("RIFF"); int(36 + dataSize); out.writeBytes("WAVEfmt "); int(16)
        short(1); short(1); int(16_000); int(32_000); short(2); short(16); out.writeBytes("data"); int(dataSize)
    }

    companion object { private const val MAX_SEGMENT_BYTES = 18L * 1024L * 1024L }
}

internal object CloudApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES).followRedirects(false).build()

    fun transcribe(file: File): Pair<String, String> {
        require(file.isFile && file.length() in 1..(24L * 1024 * 1024)) { "Файл пустой или больше 24 МБ: ${file.name}" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("application/octet-stream".toMediaType())).build()
        val request = Request.Builder().url("$GATEWAY/v1/transcribe").post(body).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Сервер ИИ HTTP ${response.code}: ${safeError(raw)}" }
            val json = JSONObject(raw)
            val text = json.optString("text").trim()
            check(text.isNotEmpty()) { "Сервер ИИ вернул пустую расшифровку" }
            val lines = buildList {
                val segments = json.optJSONArray("segments") ?: JSONArray()
                for (index in 0 until segments.length()) {
                    val segment = segments.optJSONObject(index) ?: continue
                    add("[${clock(segment.optDouble("start"))}–${clock(segment.optDouble("end"))}] ${segment.optString("text").trim()}")
                }
            }
            return text to lines.joinToString("\n").ifBlank { text }
        }
    }

    fun summarize(transcript: String, course: String): String {
        val payload = JSONObject().put("task", "summary").put("course", course).put("transcript", transcript)
        val request = Request.Builder().url("$GATEWAY/v1/generate")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Сервер ИИ HTTP ${response.code}: ${safeError(raw)}" }
            return JSONObject(raw).optString("text").trim().removePrefix("```markdown").removeSuffix("```").trim()
        }
    }

    private fun safeError(raw: String): String = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().replace(Regex("[\r\n\t]+"), " ").take(180)
    private fun clock(seconds: Double): String { val s = seconds.toLong().coerceAtLeast(0); return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }
    private const val GATEWAY = "https://lecturevault-ai-gateway.aleksandrsimunin828.workers.dev"
}

internal object NoteWriter {
    fun save(vault: File, notesFolder: String, course: String, summary: String, transcript: String): File {
        val safeCourse = safe(course)
        val safeSummary = sanitizeMarkdown(summary)
        val title = safeSummary.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.let(::safe) ?: "Лекция"
        val folderParts = notesFolder.replace('\\', '/').split('/').map(String::trim).filter(String::isNotBlank)
        require(folderParts.none { it == "." || it == ".." || it.equals(".obsidian", true) }) { "Недопустимый путь конспектов" }
        val destination = folderParts.fold(vault) { folder, part -> File(folder, safe(part)).apply { mkdirs() } }
            .let { File(it, safeCourse).apply { mkdirs() } }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        var target = File(destination, "$date — $title.md")
        var suffix = 2
        while (target.exists()) target = File(destination, "$date — $title ($suffix).md").also { suffix++ }
        val markdown = """---
type: lecture
lecturevault_id: "${UUID.randomUUID()}"
title: "${title.replace("\"", "\\\"")}"
course: "${safeCourse.replace("\"", "\\\"")}"
created: "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).format(Date())}"
tags:
  - lecturevault
---

$safeSummary

---

## Полная расшифровка

$transcript
"""
        target.writeText(markdown, Charsets.UTF_8)
        return target
    }
    private fun safe(value: String): String = value.replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]+"), " ").replace(Regex("\\s+"), " ").trim(' ', '.').take(80).ifBlank { "Лекция" }
    private fun sanitizeMarkdown(value: String): String = value
        .replace(Regex("<\\s*/?\\s*(script|iframe|object|embed|style|link|meta)\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("!\\[([^]]*)]\\(\\s*https?://[^)]+\\)", RegexOption.IGNORE_CASE), "[Внешнее изображение удалено]")
        .replace(Regex("(?i)(javascript|file|obsidian)\\s*:"), "заблокированная-ссылка:")
        .trim()
}
