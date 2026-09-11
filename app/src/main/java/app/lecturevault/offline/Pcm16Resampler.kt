package app.lecturevault.offline

import java.io.ByteArrayOutputStream

/** Streaming little-endian PCM16 downmixer/resampler for speech recognition input. */
internal class Pcm16Resampler(
    val sourceRate: Int,
    val channelCount: Int,
    private val targetRate: Int,
) {
    private val frameBytes = channelCount * PCM_BYTES
    private var remainder = ByteArray(0)
    private var sourceFrameIndex = 0L
    private var nextOutputNumerator = 0L

    init {
        require(sourceRate > 0 && targetRate > 0) { "Sample rates must be positive" }
        require(channelCount in 1..MAX_CHANNELS) { "Unsupported channel count" }
    }

    fun convert(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty()) return ByteArray(0)
        val data = if (remainder.isEmpty()) chunk else remainder + chunk
        val completeBytes = data.size - data.size % frameBytes
        remainder = data.copyOfRange(completeBytes, data.size)
        val output = ByteArrayOutputStream(((completeBytes / frameBytes) * targetRate / sourceRate + 2) * PCM_BYTES)

        var offset = 0
        while (offset < completeBytes) {
            var sum = 0
            repeat(channelCount) { channel ->
                val sampleOffset = offset + channel * PCM_BYTES
                val low = data[sampleOffset].toInt() and 0xff
                val high = data[sampleOffset + 1].toInt()
                sum += (high shl 8) or low
            }
            val mono = (sum / channelCount).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val frameBoundary = (sourceFrameIndex + 1L) * targetRate
            while (nextOutputNumerator < frameBoundary) {
                output.write(mono and 0xff)
                output.write((mono shr 8) and 0xff)
                nextOutputNumerator += sourceRate.toLong()
            }
            sourceFrameIndex += 1L
            offset += frameBytes
        }
        return output.toByteArray()
    }

    companion object {
        private const val PCM_BYTES = 2
        private const val MAX_CHANNELS = 8
    }
}
