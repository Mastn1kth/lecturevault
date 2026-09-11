package app.lecturevault.offline

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Pcm16ResamplerTest {
    @Test
    fun `downsamples 48 kHz mono to 16 kHz across chunks`() {
        val converter = Pcm16Resampler(48_000, 1, 16_000)

        val first = converter.convert(pcm(100, 200, 300, 400).copyOfRange(0, 7))
        val second = converter.convert(pcm(100, 200, 300, 400).copyOfRange(7, 8) + pcm(500, 600))

        assertArrayEquals(pcm(100), first)
        assertArrayEquals(pcm(400), second)
    }

    @Test
    fun `downmixes stereo before resampling`() {
        val converter = Pcm16Resampler(16_000, 2, 16_000)

        assertArrayEquals(pcm(0, 2_000), converter.convert(pcm(-1_000, 1_000, 1_000, 3_000)))
    }

    @Test
    fun `upsamples 8 kHz mono`() {
        val converter = Pcm16Resampler(8_000, 1, 16_000)

        assertArrayEquals(pcm(7, 7, 9, 9), converter.convert(pcm(7, 9)))
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample and 0xff).toByte()
            bytes[index * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
    }
}
