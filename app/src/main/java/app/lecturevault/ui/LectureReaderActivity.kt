package app.lecturevault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.lecturevault.R
import app.lecturevault.data.AppSettings
import app.lecturevault.data.SessionRepository
import app.lecturevault.databinding.ActivityLectureReaderBinding
import app.lecturevault.obsidian.VaultWriter
import app.lecturevault.processing.ProcessingScheduler
import app.lecturevault.util.applyScreenInsets
import app.lecturevault.util.AudioExporter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class LectureReaderActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLectureReaderBinding
    private val repository by lazy { SessionRepository(applicationContext) }
    private val settings by lazy { AppSettings(applicationContext) }
    private val writer by lazy { VaultWriter(applicationContext) }
    private lateinit var sessionId: String
    private var audioPlayer: LectureAudioPlayer? = null
    private val audioFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) exportAudio(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val session = repository.get(sessionId)
        if (session == null || session.noteRelativePath.isNullOrBlank()) {
            finish()
            return
        }

        binding = ActivityLectureReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets()
        binding.readerToolbar.title = session.title.ifBlank { "Лекция" }
        binding.readerToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.readerToolbar.setNavigationOnClickListener { finish() }
        binding.readerToolbar.inflateMenu(R.menu.reader_menu)
        binding.readerToolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_delete) {
                confirmDelete()
                true
            } else false
        }
        binding.openObsidianButton.setOnClickListener { openInObsidian(session.noteRelativePath) }
        binding.createMiniTestButton.setOnClickListener {
            startActivity(Intent(this, MiniTestActivity::class.java).putExtra(MiniTestActivity.EXTRA_SESSION_ID, session.id))
        }
        binding.saveAudioButton.visibility = if (session.segmentFiles.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.saveAudioButton.setOnClickListener { audioFolderPicker.launch(null) }
        audioPlayer = LectureAudioPlayer(session.segmentFiles, ::renderAudioState).also { player ->
            binding.audioPlayerPanel.visibility = if (player.isAvailable) android.view.View.VISIBLE else android.view.View.GONE
            binding.playAudioButton.setOnClickListener { player.toggle() }
        }
        binding.noteContent.movementMethod = LinkMovementMethod.getInstance()

        runCatching {
            val treeUri = settings.vaultTreeUri ?: error("Папка Obsidian не подключена")
            writer.readLectureNote(treeUri, session.noteRelativePath)
        }.onSuccess { markdown ->
            binding.noteContent.text = MarkdownFormatter.format(this, markdown) { reference ->
                audioPlayer?.seek(reference)
            }
        }
            .onFailure {
                binding.noteContent.text = "Не удалось открыть конспект\n\n${it.message.orEmpty()}"
            }
    }

    override fun onDestroy() {
        audioPlayer?.release()
        audioPlayer = null
        super.onDestroy()
    }

    private fun renderAudioState(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        if (isFinishing || isDestroyed) return
        binding.playAudioButton.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playAudioButton.contentDescription = if (isPlaying) "Поставить аудио на паузу" else "Слушать аудио"
        binding.audioTimeLabel.text = LectureAudioPlayer.positionText(positionMs, durationMs)
        binding.audioProgress.progress = if (durationMs > 0L) ((positionMs * 1_000L) / durationMs).toInt().coerceIn(0, 1_000) else 0
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить лекцию?")
            .setMessage("Конспект будет удалён из Obsidian, а аудио и история — с телефона. Это действие нельзя отменить.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> deleteLecture() }
            .show()
    }

    private fun deleteLecture() {
        runCatching {
            val session = repository.get(sessionId) ?: error("Лекция уже удалена")
            ProcessingScheduler.cancel(this, sessionId)
            session.noteRelativePath?.let { path ->
                val treeUri = settings.vaultTreeUri ?: error("Папка Obsidian не подключена")
                writer.deleteLectureNote(treeUri, path)
            }
            repository.delete(sessionId)
        }.onSuccess { finish() }
            .onFailure { Snackbar.make(binding.root, "Не удалось удалить: ${it.message.orEmpty().take(160)}", Snackbar.LENGTH_LONG).show() }
    }

    private fun openInObsidian(relativePath: String) {
        val vaultName = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it)?.name }
        val uri = if (!vaultName.isNullOrBlank()) {
            Uri.parse("obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(relativePath.removeSuffix(".md"))}")
        } else Uri.parse("obsidian://open")
        val action = Intent(Intent.ACTION_VIEW, uri)
        if (action.resolveActivity(packageManager) == null) {
            Snackbar.make(binding.root, "Приложение Obsidian не найдено", Snackbar.LENGTH_LONG).show()
        } else startActivity(action)
    }

    private fun exportAudio(destinationTreeUri: Uri) {
        val session = repository.get(sessionId) ?: return
        binding.saveAudioButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                AudioExporter.export(
                    context = applicationContext,
                    destinationTreeUri = destinationTreeUri,
                    sourcePaths = session.segmentFiles,
                    lectureTitle = session.title,
                )
            }.onSuccess { count ->
                Snackbar.make(binding.root, "Сохранено аудиофайлов: $count", Snackbar.LENGTH_LONG).show()
            }.onFailure { error ->
                Snackbar.make(binding.root, "Не удалось сохранить аудио: ${error.message.orEmpty().take(160)}", Snackbar.LENGTH_LONG).show()
            }
            binding.saveAudioButton.isEnabled = true
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
