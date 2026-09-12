package app.lecturevault.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import app.lecturevault.data.AppSettings
import app.lecturevault.databinding.ActivitySettingsBinding
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
            val root = DocumentFile.fromTreeUri(this, uri)
            check(root?.findFile(OBSIDIAN_CONFIG_FOLDER)?.isDirectory == true) {
                "Выберите папку самого vault, например «Учеба»"
            }
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            settings.vaultTreeUri = uri.toString()
        }.onSuccess {
            renderVaultStatus()
            showMessage("Obsidian подключён")
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
                "Если облачный режим включён, аудиофрагменты отправляются в Groq для расшифровки, " +
                    "а полученный текст — в Gemini для создания конспекта. Ключи зашифрованы Android Keystore. " +
                    "Если облако недоступно и локальная модель скачана, обработка выполняется на устройстве.",
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun renderVaultStatus() {
        val root = settings.vaultTreeUri?.let(Uri::parse)?.let { DocumentFile.fromTreeUri(this, it) }
        binding.vaultStatus.text = if (isVaultReady(root)) "Подключено: ${root?.name}" else "Vault не выбран"
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
