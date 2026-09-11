package app.lecturevault.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import app.lecturevault.util.Formatters
import java.io.File

class LectureAudioPlayer(
    files: List<String>,
    private val onState: (isPlaying: Boolean, positionMs: Long, durationMs: Long) -> Unit,
) {
    private val segments = files.map(::File).filter { it.isFile && it.canRead() }
    private val durations = segments.map(::durationOf)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var segmentIndex = 0
    private var prepared = false
    private var released = false
    private val progressTicker = object : Runnable {
        override fun run() {
            dispatchState()
            if (player?.isPlaying == true) mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    val isAvailable: Boolean get() = segments.isNotEmpty()

    fun toggle() {
        if (released || segments.isEmpty()) return
        val active = player
        when {
            active == null -> openSegment(segmentIndex, 0L, shouldPlay = true)
            active.isPlaying -> {
                active.pause()
                dispatchState()
            }
            prepared -> {
                active.start()
                startTicker()
                dispatchState()
            }
            else -> openSegment(segmentIndex, 0L, shouldPlay = true)
        }
    }

    fun seek(reference: TimestampReference) {
        if (released || segments.isEmpty()) return
        val targetMilliseconds = reference.seconds * 1_000L
        val directPart = reference.partNumber?.minus(1)?.takeIf { it in segments.indices }
        if (directPart != null) {
            openSegment(directPart, targetMilliseconds, shouldPlay = true)
            return
        }
        var remaining = targetMilliseconds
        durations.forEachIndexed { index, duration ->
            if (duration <= 0L || remaining <= duration || index == durations.lastIndex) {
                openSegment(index, remaining, shouldPlay = true)
                return
            }
            remaining -= duration
        }
    }

    fun release() {
        released = true
        mainHandler.removeCallbacks(progressTicker)
        player?.release()
        player = null
    }

    private fun openSegment(index: Int, requestedPositionMs: Long, shouldPlay: Boolean) {
        player?.release()
        mainHandler.removeCallbacks(progressTicker)
        segmentIndex = index.coerceIn(segments.indices)
        prepared = false
        player = MediaPlayer().apply {
            setDataSource(segments[segmentIndex].absolutePath)
            setOnPreparedListener { mediaPlayer ->
                if (released) return@setOnPreparedListener
                prepared = true
                val maximum = durations[segmentIndex].takeIf { it > 0L } ?: mediaPlayer.duration.toLong().coerceAtLeast(0L)
                mediaPlayer.seekTo(requestedPositionMs.coerceIn(0L, maximum).toInt())
                if (shouldPlay) {
                    mediaPlayer.start()
                    startTicker()
                }
                dispatchState()
            }
            setOnCompletionListener {
                if (segmentIndex < segments.lastIndex) openSegment(segmentIndex + 1, 0L, shouldPlay = true)
                else dispatchState()
            }
            prepareAsync()
        }
        dispatchState()
    }

    private fun dispatchState() {
        val localPosition = player?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
        val offset = durations.take(segmentIndex).sum()
        onState(player?.isPlaying == true, offset + localPosition, durations.sum())
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun durationOf(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    companion object {
        private const val UPDATE_INTERVAL_MS = 400L
        fun positionText(positionMs: Long, durationMs: Long): String =
            "${Formatters.duration(positionMs)} / ${Formatters.duration(durationMs)}"
    }
}
