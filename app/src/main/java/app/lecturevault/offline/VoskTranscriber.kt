package app.lecturevault.offline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer

internal class VoskTranscriber(private val modelPath: String) {
    data class Result(val text: String, val timestampedText: String)

    fun transcribe(files: List<File>, onPart: (Int, Int) -> Unit): Result {
        Model(modelPath).use { model ->
            Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
                recognizer.setWords(true)
                val allResults = mutableListOf<JSONObject>()
                files.forEachIndexed { index, file ->
                    decodeFile(file, recognizer, allResults)
                    onPart(index + 1, files.size)
                }
                allResults += JSONObject(recognizer.finalResult)
                return results(allResults)
            }
        }
    }

    private fun decodeFile(file: File, recognizer: Recognizer, results: MutableList<JSONObject>) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("В записи нет аудиодорожки")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var converter = converterFor(format)
            var decodedAudio = false
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = codec.outputFormat
                    val encoding = outputFormat.integerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Неподдерживаемый формат декодированного звука" }
                    val updated = converterFor(outputFormat)
                    if (decodedAudio) {
                        check(updated.sourceRate == converter.sourceRate && updated.channelCount == converter.channelCount) {
                            "Формат аудио изменился во время обработки"
                        }
                    } else {
                        converter = updated
                    }
                } else if (outputIndex >= 0) {
                    if (info.size > 0) {
                        val output = checkNotNull(codec.getOutputBuffer(outputIndex))
                        val pcm = ByteArray(info.size)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        output.get(pcm)
                        val speechPcm = converter.convert(pcm)
                        decodedAudio = true
                        if (speechPcm.isNotEmpty() && recognizer.acceptWaveForm(speechPcm, speechPcm.size)) {
                            results += JSONObject(recognizer.result)
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun results(items: List<JSONObject>): Result {
        val words = mutableListOf<Word>()
        items.forEach { item ->
            item.optJSONArray("result")?.let { array ->
                for (i in 0 until array.length()) {
                    val word = array.optJSONObject(i) ?: continue
                    word.optString("word").takeIf(String::isNotBlank)?.let {
                        words += Word(it, word.optDouble("start", 0.0))
                    }
                }
            }
        }
        val text = words.joinToString(" ") { it.value }.trim()
        val timestamped = words.groupBy { (it.start / 30.0).toInt() }.entries.joinToString("\n\n") { (slot, group) ->
            "[${formatTime(slot * 30)}] ${group.joinToString(" ") { it.value }}"
        }
        return Result(text, timestamped)
    }

    private fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
    private fun converterFor(format: MediaFormat): Pcm16Resampler = Pcm16Resampler(
        sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
        channelCount = format.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1),
        targetRate = SAMPLE_RATE,
    )

    private fun MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private data class Word(val value: String, val start: Double)
    private companion object { const val SAMPLE_RATE = 16_000; const val TIMEOUT_US = 10_000L }
}
