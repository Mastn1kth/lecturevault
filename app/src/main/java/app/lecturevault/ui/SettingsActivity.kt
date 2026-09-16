package app.lecturevault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.lecturevault.BuildConfig
import app.lecturevault.data.AppSettings
import app.lecturevault.databinding.ActivitySettingsBinding
import app.lecturevault.network.GatewayClient
import app.lecturevault.obsidian.VaultWriter
import app.lecturevault.offline.OfflineModelManager
import app.lecturevault.util.applyScreenInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { AppSettings(applicationContext) }
    private val modelManager by lazy { OfflineModelManager(applicationContext) }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val root = requireNotNull(DocumentFile.fromTreeUri(this, uri)) { "Выбранная папка недоступна" }
            check(root.exists() && root.isDirectory && root.canWrite()) { "Нет доступа к выбранной папке" }
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val wasNew = root.findFile(OBSIDIAN_CONFIG_FOLDER) == null
            if (wasNew) {
                check(root.listFiles().isEmpty()) {
                    "Выберите пустую папку для нового vault или уже готовое хранилище Obsidian"
                }
            }
            check(root.findFile(OBSIDIAN_CONFIG_FOLDER)?.let { it.isDirectory } != false) {
                "В выбранной папке уже есть файл «.obsidian»"
            }
            if (wasNew) {
                check(root.createDirectory(OBSIDIAN_CONFIG_FOLDER) != null) { "Не удалось создать служебную папку Obsidian" }
            }
            val notesFolder = binding.notesFolderInput.text?.toString().orEmpty().ifBlank { "Лекции" }
            binding.notesFolderInput.setText(notesFolder)
            ensureNotesFolder(root, notesFolder)
            settings.vaultTreeUri = uri.toString()
            wasNew
        }.onSuccess {
            renderVaultStatus()
            showMessage(if (it) "Хранилище Obsidian создано и подключено" else "Obsidian подключён")
        }.onFailure { showMessage(it.message ?: "Нет доступа к папке") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyScreenInsets()

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.notesFolderInput.setText(settings.notesFolder)
        binding.consentCheck.isChecked = settings.consent
        binding.selectVaultButton.setOnClickListener { folderPicker.launch(null) }
        binding.downloadModelButton.setOnClickListener { downloadModel() }
        binding.healthButton.setOnClickListener { checkGateway() }
        binding.privacyButton.setOnClickListener { showPrivacyExplanation() }
        binding.saveButton.setOnClickListener { save() }
        renderModelStatus()
        renderCloudStatus()
        renderVaultStatus()
    }

    private fun downloadModel() {
        binding.downloadModelButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                modelManager.download { current, total ->
                    runOnUiThread {
                        val totalMb = total?.div(1_048_576)?.takeIf { it > 0 }
                        binding.modelStatus.text = if (totalMb == null) {
                            "Скачано ${current / 1_048_576} МБ"
                        } else {
                            "Скачано ${current / 1_048_576} из $totalMb МБ"
                        }
                    }
                }
            }.onSuccess {
                renderModelStatus()
                showMessage("Русская модель готова")
            }.onFailure { showMessage(it.message ?: "Не удалось скачать модель") }
            binding.downloadModelButton.isEnabled = !modelManager.isInstalled()
        }
    }

    private fun checkGateway() {
        binding.healthButton.isEnabled = false
        binding.cloudStatus.text = "Проверяем сервер ИИ…"
        lifecycleScope.launch {
            runCatching { GatewayClient().checkHealth() }
                .onSuccess { health ->
                    binding.cloudStatus.text = if (health.speechReady && health.textProviders.isNotEmpty()) {
                        "Сервер ИИ готов · речь + ${health.textProviders.joinToString(", ")}"
                    } else {
                        "Сервер доступен, но ИИ настроен не полностью"
                    }
                }
                .onFailure { binding.cloudStatus.text = "Сервер недоступен: ${it.message.orEmpty().take(140)}" }
            binding.healthButton.isEnabled = true
        }
    }

    private fun save() {
        val folder = binding.notesFolderInput.text?.toString().orEmpty()
        runCatching { VaultWriter(this).validateFolderPath(folder) }
            .onFailure { showMessage(it.message ?: "Некорректная папка"); return }
        if (!isVaultReady()) {
            showMessage("Выберите папку самого vault Obsidian")
            return
        }

        settings.notesFolder = folder
        settings.consent = binding.consentCheck.isChecked
        renderCloudStatus()
        showMessage("Настройки сохранены")
    }


    private fun renderModelStatus() {
        binding.modelStatus.text = if (modelManager.isInstalled()) {
            "Русская модель установлена · работает без интернета"
        } else {
            "Не установлена · можно скачать позже"
        }
        binding.downloadModelButton.text = if (modelManager.isInstalled()) "Модель установлена" else "Скачать русскую модель"
        binding.downloadModelButton.isEnabled = !modelManager.isInstalled()
    }

    private fun renderCloudStatus() {
        binding.cloudStatus.text = if (settings.consent) "Облачный ИИ подключён · ключи защищены на сервере" else "Подтвердите отправку аудио и текста на сервер ИИ"
    }


    private fun showPrivacyExplanation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Как используются данные")
            .setMessage(
                "При включённом облачном режиме аудио и текст передаются на защищённый сервер приложения " +
                    "для расшифровки и создания конспекта. Ключи ИИ находятся только на сервере и не сохраняются " +
                    "на телефоне. Если облако недоступно и локальная модель скачана, обработка выполняется на устройстве.",
            )
            .setNegativeButton("Понятно", null)
            .setPositiveButton("Открыть политику") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.GATEWAY_URL.trimEnd('/')}/privacy")))
            }
            .show()
    }

    private fun renderVaultStatus() {
        val root = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it) }
        binding.vaultStatus.text = if (isVaultReady(root)) "Подключено: ${root?.name}" else "Vault не выбран"
    }

    private fun ensureNotesFolder(root: DocumentFile, rawPath: String) {
        var folder = root
        for (segment in VaultWriter(this).validateFolderPath(rawPath.ifBlank { "Лекции" })) {
            folder = folder.findFile(segment)?.also {
                check(it.isDirectory) { "В пути лекций «$segment» уже есть файл" }
            } ?: checkNotNull(folder.createDirectory(segment)) { "Не удалось создать папку «$segment»" }
        }
    }

    private fun isVaultReady(
        root: DocumentFile? = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it) },
    ): Boolean = root?.exists() == true && root.isDirectory && root.canWrite() &&
        root.findFile(OBSIDIAN_CONFIG_FOLDER)?.isDirectory == true

    private fun showMessage(message: String) =
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()

    companion object {
        private const val OBSIDIAN_CONFIG_FOLDER = ".obsidian"
    }
}
