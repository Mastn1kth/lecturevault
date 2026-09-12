package app.lecturevault.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.lecturevault.R
import app.lecturevault.data.AppSettings
import app.lecturevault.data.LectureSession
import app.lecturevault.data.SessionRepository
import app.lecturevault.data.SessionStatus
import app.lecturevault.databinding.ActivityMainBinding
import app.lecturevault.databinding.ItemSessionBinding
import app.lecturevault.importing.AudioImporter
import app.lecturevault.offline.OfflineModelManager
import app.lecturevault.processing.ProcessingScheduler
import app.lecturevault.recording.RecordingService
import app.lecturevault.recording.RecordingState
import app.lecturevault.util.AppEvents
import app.lecturevault.util.Formatters
import app.lecturevault.util.applyScreenInsets
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository by lazy { SessionRepository(applicationContext) }
    private val settings by lazy { AppSettings(applicationContext) }
    private val timerHandler = Handler(Looper.getMainLooper())
    private var pendingStart = false
    private var importInProgress = false
    private var libraryMode = false
    private var filter = HistoryFilter.ALL

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importAudio(uris)
    }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true && pendingStart) {
            pendingStart = false
            startRecordingService()
        } else if (pendingStart) {
            pendingStart = false
            showMessage("Без доступа к микрофону запись невозможна")
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val session = RecordingState.activeSessionId(this@MainActivity)?.let(repository::get)
            if (session?.status == SessionStatus.RECORDING) {
                binding.timerLabel.text = Formatters.duration(System.currentTimeMillis() - session.startedAt)
                timerHandler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets()

        val russian = Locale.forLanguageTag("ru")
        binding.todayLabel.text = SimpleDateFormat("EEEE, d MMMM", russian)
            .format(Date()).replaceFirstChar { it.titlecase(russian) }
        binding.courseInput.setText(getSharedPreferences(UI_PREFS, MODE_PRIVATE).getString(KEY_LAST_COURSE, ""))
        binding.setupButton.setOnClickListener { openSettings() }
        binding.importAudioButton.setOnClickListener { requestImport() }
        binding.recordButton.setOnClickListener {
            if (RecordingState.activeSessionId(this) == null) requestStart() else stopRecordingService()
        }
        binding.searchInput.doAfterTextChanged { renderHistory(repository.list()) }
        binding.filterGroup.setOnCheckedStateChangeListener { _, checked ->
            filter = when (checked.firstOrNull()) {
                R.id.filterReady -> HistoryFilter.READY
                R.id.filterPending -> HistoryFilter.PENDING
                else -> HistoryFilter.ALL
            }
            renderHistory(repository.list())
        }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_record -> { setLibraryMode(false); true }
                R.id.nav_library -> { setLibraryMode(true); true }
                R.id.nav_settings -> { openSettings(); false }
                else -> false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_record
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, eventReceiver, IntentFilter(AppEvents.SESSION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(eventReceiver)
        timerHandler.removeCallbacks(timerRunnable)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setLibraryMode(enabled: Boolean) {
        libraryMode = enabled
        binding.recordingPanel.visibility = if (enabled) View.GONE else View.VISIBLE
        binding.searchLayout.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.filterGroup.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.historyTitle.text = if (enabled) "Все лекции" else "Последние лекции"
        binding.contentScroll.post { binding.contentScroll.smoothScrollTo(0, 0) }
        renderHistory(repository.list())
    }

    private fun requestStart() {
        if (!isSetupReady()) {
            showMessage(setupMessage())
            openSettings()
            return
        }
        val course = binding.courseInput.text?.toString()?.trim().orEmpty()
        if (course.isBlank()) {
            binding.courseLayout.error = "Введите предмет — он станет названием папки"
            return
        }
        binding.courseLayout.error = null
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_COURSE, course).apply()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecordingService()
            return
        }
        pendingStart = true
        permissionLauncher.launch(buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray())
    }

    private fun requestImport() {
        if (!isSetupReady()) {
            showMessage(setupMessage())
            openSettings()
            return
        }
        val course = binding.courseInput.text?.toString()?.trim().orEmpty()
        if (course.isBlank()) {
            binding.courseLayout.error = "Введите предмет"
            return
        }
        binding.courseLayout.error = null
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_COURSE, course).apply()
        audioPicker.launch(arrayOf("audio/*"))
    }

    private fun importAudio(uris: List<Uri>) {
        val course = binding.courseInput.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch {
            importInProgress = true
            render()
            val importer = AudioImporter(applicationContext)
            var imported = 0
            var lastError: Throwable? = null
            uris.forEach { uri ->
                runCatching { importer.import(uri, course) }
                    .onSuccess { ProcessingScheduler.enqueue(this@MainActivity, it.id); imported++ }
                    .onFailure { lastError = it }
            }
            importInProgress = false
            render()
            showMessage(when {
                imported == uris.size -> if (imported == 1) "Аудио добавлено в обработку" else "Добавлено записей: $imported"
                imported > 0 -> "Добавлено: $imported, ошибок: ${uris.size - imported}"
                else -> "Импорт не удался: ${lastError?.let(::safeMessage) ?: "неизвестная ошибка"}"
            })
        }
    }

    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_TITLE, "")
            putExtra(RecordingService.EXTRA_COURSE, binding.courseInput.text?.toString().orEmpty())
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { showMessage("Не удалось начать запись: ${safeMessage(it)}") }
        render()
    }

    private fun stopRecordingService() {
        startService(Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP })
        binding.recordButton.isEnabled = false
        binding.statusLabel.text = "ЗАВЕРШАЕМ"
    }

    private fun render() {
        val sessions = repository.list()
        val active = RecordingState.activeSessionId(this)?.let(repository::get)
        val isRecording = active?.status == SessionStatus.RECORDING
        val processing = sessions.firstOrNull { it.status.isProcessing() }

        binding.courseInput.isEnabled = !isRecording
        binding.recordButton.isEnabled = !importInProgress
        binding.importAudioButton.isEnabled = !isRecording && !importInProgress
        binding.recordButton.contentDescription = if (isRecording) "Остановить запись" else "Начать запись"
        binding.recordButton.icon = ContextCompat.getDrawable(this, if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic)
        binding.recordButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (isRecording) R.color.recording_red else R.color.brand_primary))
        binding.pulseView.recording = isRecording
        binding.statusLabel.text = when {
            isRecording -> "ИДЁТ ЗАПИСЬ"
            importInProgress -> "ИМПОРТ АУДИО"
            processing != null -> "ОБРАБОТКА · ${processing.progress}%"
            else -> "ГОТОВО"
        }
        binding.recordActionLabel.text = if (isRecording) "Нажмите, чтобы завершить" else "Нажмите, чтобы начать"
        if (isRecording) {
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        } else {
            timerHandler.removeCallbacks(timerRunnable)
            binding.timerLabel.text = "00:00:00"
        }
        binding.setupCard.visibility = if (runCatching(::isSetupReady).getOrDefault(false)) View.GONE else View.VISIBLE
        renderHistory(sessions)
    }

    private fun renderHistory(sessions: List<LectureSession>) {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        val filtered = sessions.filter { session ->
            val matchesText = query.isBlank() || session.title.contains(query, true) || session.course.contains(query, true)
            val matchesState = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.READY -> session.status == SessionStatus.COMPLETE
                HistoryFilter.PENDING -> session.status != SessionStatus.COMPLETE
            }
            matchesText && matchesState
        }
        val visible = if (libraryMode) filtered else filtered.take(HOME_SESSION_LIMIT)
        binding.historyCount.text = if (sessions.isEmpty()) "" else sessions.size.toString()
        binding.historyContainer.removeAllViews()
        binding.emptyHistory.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        visible.forEach { session ->
            val item = ItemSessionBinding.inflate(LayoutInflater.from(this), binding.historyContainer, false)
            item.sessionTitle.text = session.title.ifBlank { "Новая лекция" }
            item.sessionMeta.text = listOfNotNull(session.course.takeIf(String::isNotBlank), Formatters.dateTime(session.createdAt)).joinToString(" · ")
            item.sessionStatus.text = statusText(session)
            item.sessionStatus.setTextColor(ContextCompat.getColor(this, when (session.status) {
                SessionStatus.COMPLETE -> R.color.success_green
                SessionStatus.FAILED -> R.color.recording_red
                else -> R.color.brand_primary_dark
            }))
            item.sessionProgress.visibility = if (session.status.isProcessing()) View.VISIBLE else View.GONE
            item.sessionProgress.progress = session.progress
            item.sessionError.visibility = if (session.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
            item.sessionError.text = session.errorMessage
            item.sessionAction.visibility = View.GONE
            item.sessionDelete.setOnClickListener { confirmDelete(session) }
            when {
                session.status == SessionStatus.COMPLETE && !session.noteRelativePath.isNullOrBlank() -> {
                    item.sessionAction.visibility = View.VISIBLE
                    item.sessionAction.text = "Читать"
                    item.sessionAction.setOnClickListener { openReader(session.id) }
                    item.root.setOnClickListener { openReader(session.id) }
                }
                session.status == SessionStatus.FAILED && session.segmentFiles.isNotEmpty() -> {
                    item.sessionAction.visibility = View.VISIBLE
                    item.sessionAction.text = "Повторить"
                    item.sessionAction.setOnClickListener {
                        ProcessingScheduler.enqueue(this, session.id)
                        showMessage("Снова поставлено в очередь")
                        render()
                    }
                }
            }
            binding.historyContainer.addView(item.root)
        }
    }

    private fun statusText(session: LectureSession): String = when (session.status) {
        SessionStatus.RECORDING -> "Записывается"
        SessionStatus.QUEUED -> "В очереди"
        SessionStatus.TRANSCRIBING -> "Расшифровка · ${session.progress}%"
        SessionStatus.SUMMARIZING -> "Создание конспекта · ${session.progress}%"
        SessionStatus.SAVING -> "Сохранение в Obsidian"
        SessionStatus.COMPLETE -> "Конспект готов"
        SessionStatus.FAILED -> "Не обработано"
    }

    private fun SessionStatus.isProcessing() = this in setOf(SessionStatus.QUEUED, SessionStatus.TRANSCRIBING, SessionStatus.SUMMARIZING, SessionStatus.SAVING)

    private fun openReader(sessionId: String) {
        startActivity(Intent(this, LectureReaderActivity::class.java).putExtra(LectureReaderActivity.EXTRA_SESSION_ID, sessionId))
    }

    private fun confirmDelete(session: LectureSession) {
        if (session.status == SessionStatus.RECORDING) {
            showMessage("Сначала остановите запись")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить лекцию?")
            .setMessage("Конспект будет удалён из Obsidian, а аудио и история — с телефона. Это действие нельзя отменить.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                runCatching {
                    ProcessingScheduler.cancel(this, session.id)
                    val notePath = session.noteRelativePath
                    if (!notePath.isNullOrBlank()) {
                        val treeUri = settings.vaultTreeUri ?: error("Папка Obsidian не подключена")
                        app.lecturevault.obsidian.VaultWriter(this).deleteLectureNote(treeUri, notePath)
                    }
                    repository.delete(session.id)
                }.onSuccess {
                    showMessage("Лекция удалена")
                    render()
                }.onFailure { showMessage("Не удалось удалить: ${safeMessage(it)}") }
            }
            .show()
    }

    private fun openInObsidian(relativePath: String) {
        val vaultName = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it)?.name }
        val uri = if (!vaultName.isNullOrBlank()) Uri.parse("obsidian://open?vault=${Uri.encode(vaultName)}&file=${Uri.encode(relativePath.removeSuffix(".md"))}") else Uri.parse("obsidian://open")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(packageManager) == null) showMessage("Приложение Obsidian не найдено") else startActivity(intent)
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun isSetupReady(): Boolean {
        val root = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it) }
        val vaultReady = settings.isConfigured() && root?.exists() == true && root.isDirectory && root.canWrite() && root.findFile(".obsidian")?.isDirectory == true
        // Provider keys never live on the phone; consent enables the secured gateway.
        val cloudReady = settings.consent
        val localReady = OfflineModelManager(applicationContext).isInstalled()
        return vaultReady && (cloudReady || localReady)
    }

    private fun setupMessage(): String = when {
        !settings.isConfigured() -> "Сначала подключите папку Obsidian"
        else -> "Подключите облако или скачайте локальную русскую модель"
    }

    private fun showMessage(message: String) = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    private fun safeMessage(error: Throwable) = error.message?.replace(Regex("[\\r\\n]+"), " ")?.take(180) ?: "неизвестная ошибка"

    private enum class HistoryFilter { ALL, READY, PENDING }

    companion object {
        private const val UI_PREFS = "ui_state"
        private const val KEY_LAST_COURSE = "last_course"
        private const val HOME_SESSION_LIMIT = 4
    }
}
