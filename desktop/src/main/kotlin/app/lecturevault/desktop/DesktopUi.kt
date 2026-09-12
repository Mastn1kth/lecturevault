package app.lecturevault.desktop

import com.formdev.flatlaf.util.SystemFileChooser
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.event.*
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.math.min

internal object Ink {
    val background = Color(0x0D0F12)
    val surface = Color(0x171A20)
    val elevated = Color(0x20242B)
    val text = Color(0xF3F1EC)
    val muted = Color(0xAAB0BC)
    val subtle = Color(0x777E8B)
    val line = Color(0x2A2F38)
    val accent = Color(0xFF7849)
    val green = Color(0x69D8A0)
    val red = Color(0xFF6B72)
    private val face: Font by lazy {
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val family = listOf("Segoe UI Variable Text", "SF Pro Text", "Segoe UI", "Helvetica Neue", "Dialog")
            .firstOrNull(installed::contains) ?: "Dialog"
        Font(family, Font.PLAIN, 14)
    }
    fun font(size: Int, bold: Boolean = false): Font = face.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size.toFloat())
}

internal fun configureDesktopTheme() {
    UIManager.put("Component.arc", 14)
    UIManager.put("Button.arc", 14)
    UIManager.put("TextComponent.arc", 14)
    UIManager.put("ScrollBar.width", 10)
    UIManager.put("TitlePane.unifiedBackground", true)
    UIManager.put("TitlePane.buttonSize", Dimension(46, 34))
    UIManager.put("RootPane.background", Ink.background)
    UIManager.put("OptionPane.background", Ink.surface)
    UIManager.put("Panel.background", Ink.surface)
    UIManager.put("OptionPane.messageForeground", Ink.text)
    UIManager.put("Label.foreground", Ink.text)
    UIManager.put("Button.background", Ink.elevated)
    UIManager.put("Button.foreground", Ink.text)
    UIManager.put("Button.font", Ink.font(14))
    UIManager.put("TextField.selectionBackground", Color(0x724332))
    UIManager.put("PasswordField.selectionBackground", Color(0x724332))
    ToolTipManager.sharedInstance().initialDelay = 450
}

internal enum class Page { RECORD, LIBRARY, SETTINGS }
internal enum class RecordingState { READY, RECORDING, PROCESSING, SUCCESS, ERROR }
internal data class LectureItem(val file: File, val title: String, val course: String, val date: String)

internal class LectureVaultWindow : JFrame("LectureVault") {
    private val settings = DesktopSettings()
    internal val view = DesktopView(settings.subject)
    private var recorder: SegmentedRecorder? = null
    private var busy = false
    private var recordedAt = 0L
    private var pendingFiles = emptyList<File>()
    private var lastNote: File? = null
    private var historyTask: SwingWorker<List<LectureItem>, Unit>? = null
    private val timer = Timer(250) {
        view.setElapsed((System.currentTimeMillis() - recordedAt) / 1000)
    }

    init {
        minimumSize = Dimension(900, 680)
        size = Dimension(1260, 850)
        iconImage = runCatching { ImageIO.read(javaClass.getResource("/icons/LectureVault.png")) }.getOrNull() ?: appIcon()
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        contentPane = view
        setLocationRelativeTo(null)
        view.onRecord = ::toggleRecording
        view.onImport = ::chooseAudio
        view.onDrop = ::importFiles
        view.onNavigate = { page -> if (page == Page.LIBRARY) refreshHistory() }
        view.onSaveSettings = ::saveSettings
        view.onDeleteKeys = ::deleteKeys
        view.onChooseVault = ::chooseVault
        view.onOpenNote = ::openNote
        view.onDeleteNote = ::deleteNote
        view.onRetry = { process(pendingFiles) }
        view.onOpenLast = { lastNote?.let(::openNote) }
        loadSettingsView()
        refreshHistory()
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                if (busy) {
                    view.showNotice("Дождитесь завершения обработки перед закрытием.", true)
                    view.navigate(Page.RECORD)
                    return
                }
                if (recorder != null) {
                    val answer = JOptionPane.showConfirmDialog(this@LectureVaultWindow,
                        "Остановить запись и закрыть? Аудио останется на компьютере, конспект ещё не будет создан.",
                        "Запись продолжается", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                    if (answer != JOptionPane.YES_OPTION) return
                    recorder?.stop()
                    timer.stop()
                }
                historyTask?.cancel(true)
                dispose()
            }
        })
    }

    private fun loadSettingsView() {
        view.vaultField.text = settings.vault?.absolutePath.orEmpty()
        view.notesField.text = settings.notesFolder
        view.consentBox.isSelected = settings.cloudConsent
        view.setConfiguration(settings.vault?.name, settings.cloudConsent)
    }

    private fun ready(): Boolean {
        if (view.subject.text.trim().isEmpty()) {
            view.showNotice("Введите предмет, чтобы сохранить лекцию в нужную папку.", true)
            view.subject.requestFocusInWindow()
            return false
        }
        if (!settings.cloudConsent || settings.vault == null) {
            view.navigate(Page.SETTINGS)
            view.settingsNotice("Подключите папку Obsidian и подтвердите облачную обработку перед первой лекцией.", true)
            return false
        }
        return true
    }

    private fun toggleRecording() {
        if (busy) return
        val active = recorder
        if (active != null) {
            timer.stop()
            recorder = null
            runCatching { active.stop() }.onSuccess { process(it) }.onFailure {
                view.setState(RecordingState.ERROR, "Не удалось завершить запись: ${it.message}")
            }
            return
        }
        if (!ready()) return
        settings.subject = view.subject.text.trim()
        runCatching { SegmentedRecorder(settings.workingDirectory()).also { it.start() } }
            .onSuccess {
                recorder = it
                recordedAt = System.currentTimeMillis()
                view.setElapsed(0)
                view.setState(RecordingState.RECORDING, "Микрофон включён")
                timer.start()
            }.onFailure { view.setState(RecordingState.ERROR, "Микрофон недоступен. Проверьте разрешение и устройство ввода.") }
    }

    private fun chooseAudio() {
        if (busy || recorder != null || !ready()) return
        val picker = SystemFileChooser().apply {
            dialogTitle = "Выбрать аудиозапись"
            isMultiSelectionEnabled = true
            addChoosableFileFilter(SystemFileChooser.FileNameExtensionFilter("Аудио", "wav", "mp3", "m4a", "mp4", "flac", "ogg", "webm"))
        }
        if (picker.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) importFiles(picker.selectedFiles.toList())
    }

    private fun importFiles(files: List<File>) {
        if (busy || recorder != null || !ready()) return
        val supported = setOf("wav", "mp3", "m4a", "mp4", "flac", "ogg", "webm")
        if (files.isEmpty() || files.any { !it.isFile || it.extension.lowercase() !in supported || it.length() !in 1..(24L * 1024 * 1024) }) {
            view.showNotice("Выберите аудио до 24 МБ на файл: MP3, M4A, WAV, FLAC, OGG, MP4 или WebM.", true)
            return
        }
        process(files)
    }

    private fun process(files: List<File>) {
        if (busy) return
        if (files.isEmpty()) {
            view.setState(RecordingState.ERROR, "В записи не оказалось аудио. Попробуйте ещё раз.")
            return
        }
        if (!ready()) return
        val vault = settings.vault ?: return
        val course = view.subject.text.trim()
        val folder = settings.notesFolder
        settings.subject = course
        pendingFiles = files
        busy = true
        view.navigate(Page.RECORD)
        view.setState(RecordingState.PROCESSING, "Подготавливаем аудио")
        object : SwingWorker<File, String>() {
            override fun doInBackground(): File {
                val transcripts = files.mapIndexed { index, file ->
                    publish("Расшифровка · часть ${index + 1} из ${files.size}")
                    CloudApi.transcribe(file)
                }
                val plain = transcripts.joinToString("\n\n") { it.first }
                val timed = transcripts.mapIndexed { index, item -> "### Часть ${index + 1}\n${item.second}" }.joinToString("\n\n")
                publish("Составляем конспект")
                val note = CloudApi.summarize(plain, course)
                publish("Сохраняем в Obsidian")
                return NoteWriter.save(vault, folder, course, note, timed)
            }
            override fun process(chunks: MutableList<String>) { view.setProgress(chunks.last()) }
            override fun done() {
                busy = false
                runCatching { get() }.onSuccess {
                    lastNote = it
                    pendingFiles = emptyList()
                    view.setState(RecordingState.SUCCESS, "Конспект сохранён в Obsidian")
                    view.setElapsed(0)
                    refreshHistory()
                }.onFailure {
                    val detail = (it.cause?.message ?: it.message).orEmpty().take(220)
                    view.setState(RecordingState.ERROR, "Не удалось обработать аудио. $detail", canRetry = true)
                }
            }
        }.execute()
    }

    private fun chooseVault() {
        val picker = SystemFileChooser().apply {
            dialogTitle = "Корневая папка хранилища Obsidian"
            fileSelectionMode = SystemFileChooser.DIRECTORIES_ONLY
        }
        if (picker.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) view.vaultField.text = picker.selectedFile.absolutePath
    }

    private fun saveSettings() {
        if (busy || recorder != null) return
        runCatching {
            val folder = File(view.vaultField.text.trim()).canonicalFile
            require(view.vaultField.text.isNotBlank() && folder.isDirectory) { "Выберите существующую папку Obsidian." }
            settings.save(folder, view.notesField.text.trim(), view.consentBox.isSelected)
        }.onSuccess {
            loadSettingsView()
            view.settingsNotice("Настройки сохранены. Можно начинать лекцию.")
            refreshHistory()
        }.onFailure { view.settingsNotice(it.message ?: "Не удалось сохранить настройки.", true) }
    }

    private fun deleteKeys() {
        if (busy || recorder != null) return
        runCatching { settings.disableCloudProcessing() }
            .onSuccess {
                view.consentBox.isSelected = false
                loadSettingsView()
                view.settingsNotice("Облачная обработка отключена на этом компьютере.")
            }
            .onFailure { view.settingsNotice("Не удалось отключить облачную обработку.", true) }
    }

    private fun refreshHistory() {
        historyTask?.cancel(true)
        val vault = settings.vault
        val folder = settings.notesFolder
        view.showHistoryLoading()
        historyTask = object : SwingWorker<List<LectureItem>, Unit>() {
            override fun doInBackground(): List<LectureItem> {
                if (vault == null) return emptyList()
                val root = folder.replace('\\', '/').split('/').filter(String::isNotBlank).fold(vault, ::File).canonicalFile
                require(root.toPath().startsWith(vault.canonicalFile.toPath())) { "Папка лекций должна находиться внутри Obsidian." }
                return root.walkTopDown().maxDepth(5).filter { !isCancelled && it.isFile && it.extension.equals("md", true) }
                    .sortedByDescending(File::lastModified).take(300).map {
                        LectureItem(it, it.nameWithoutExtension.replace(Regex("^\\d{4}-\\d{2}-\\d{2} — "), ""),
                            it.parentFile?.name.orEmpty(), SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("ru")).format(Date(it.lastModified())))
                    }.toList()
            }
            override fun done() {
                if (isCancelled) return
                runCatching { get() }.onSuccess(view::setLectures).onFailure { view.showHistoryError("Не удалось прочитать папку лекций.") }
            }
        }.also { it.execute() }
    }

    private fun openNote(file: File) {
        runCatching {
            require(file.isFile) { "Файл перемещён или удалён. Обновите библиотеку." }
            val text = file.readText(Charsets.UTF_8).replaceFirst(Regex("(?s)^---.*?---\\s*"), "")
            val area = JTextArea(text).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true
                font = Ink.font(14); foreground = Ink.text; background = Ink.surface
                border = BorderFactory.createEmptyBorder(14, 14, 14, 14); caretPosition = 0
            }
            JOptionPane.showMessageDialog(this, JScrollPane(area).apply { preferredSize = Dimension(760, 620) }, file.nameWithoutExtension, JOptionPane.PLAIN_MESSAGE)
        }.onFailure { view.showNotice(it.message ?: "Не удалось открыть конспект.", true) }
    }

    private fun deleteNote(file: File) {
        if (busy) return
        val answer = JOptionPane.showConfirmDialog(this, "Удалить этот конспект из Obsidian? Это нельзя отменить.", "Удалить лекцию", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        if (answer != JOptionPane.YES_OPTION) return
        runCatching {
            val vault = settings.vault?.canonicalFile ?: error("Vault не подключён")
            val target = file.canonicalFile
            require(target.toPath().startsWith(vault.toPath()) && target.extension.equals("md", true)) { "Некорректный путь конспекта" }
            require(target.delete()) { "Не удалось удалить файл. Возможно, он открыт в Obsidian." }
        }.onSuccess { view.showNotice("Конспект удалён из Obsidian"); refreshHistory() }
            .onFailure { view.showNotice(it.message ?: "Не удалось удалить конспект.", true) }
    }
}

internal class DesktopView(initialSubject: String = "") : JPanel(BorderLayout()) {
    var onRecord: () -> Unit = {}
    var onImport: () -> Unit = {}
    var onDrop: (List<File>) -> Unit = {}
    var onNavigate: (Page) -> Unit = {}
    var onChooseVault: () -> Unit = {}
    var onSaveSettings: () -> Unit = {}
    var onDeleteKeys: () -> Unit = {}
    var onOpenNote: (File) -> Unit = {}
    var onDeleteNote: (File) -> Unit = {}
    var onRetry: () -> Unit = {}
    var onOpenLast: () -> Unit = {}
    val subject = input(initialSubject, "Предмет")
    val consentBox = JCheckBox("Разрешаю отправлять аудио и текст на защищённый сервер ИИ").apply {
        isOpaque = false; foreground = Ink.text; font = Ink.font(12)
    }
    val vaultField = input("", "Папка хранилища Obsidian")
    val notesField = input("Лекции", "Папка конспектов")
    val recordButton = RecordButton()
    private val importButton = ActionButton("Выбрать аудио", "upload")
    private val cards = CardLayout()
    private val pages = JPanel(cards).apply { background = Ink.background }
    private val statusBadge = JLabel("ГОТОВО", StatusDot(Ink.green), SwingConstants.LEFT).apply { font = Ink.font(11, true); foreground = Ink.green; iconTextGap = 8 }
    private val timer = label("00:00:00", 54).apply { font = Font("Monospaced", Font.PLAIN, 54) }
    private val recordCaption = label("Начать запись", 14, true)
    private val detail = body("Микрофон выключен", 13)
    private val notice = body("", 13).apply { isVisible = false }
    private val saveNotice = body("", 13).apply { isVisible = false }
    private val vaultStatus = label("Obsidian не подключён", 11, color = Ink.muted)
    private val vaultName = label("Выбрать папку", 13, true)
    private val setup = Surface(Ink.elevated, 16)
    private val recent = column()
    private val library = column()
    private val count = label("", 12, color = Ink.muted)
    private val search = input("", "Поиск по названию или предмету")
    private val recovery = row(10)
    private val retry = ActionButton("Повторить", "refresh").apply { addActionListener { onRetry() } }
    private val openLast = ActionButton("Открыть конспект", "arrow").apply { addActionListener { onOpenLast() } }
    private val saveButton = ActionButton("Сохранить настройки", "check", true).apply { addActionListener { onSaveSettings() } }
    private val deleteKeysButton = ActionButton("Отключить облачный ИИ", "delete").apply { addActionListener { onDeleteKeys() } }
    private val chooseVaultButton = ActionButton("Выбрать папку", "folder").apply { addActionListener { onChooseVault() } }
    private val navButtons = linkedMapOf<Page, ActionButton>()
    private var lectures = emptyList<LectureItem>()
    private var currentState = RecordingState.READY
    private val sidebar = column().apply {
        background = Ink.surface
        isOpaque = true
        preferredSize = Dimension(216, 600)
        border = BorderFactory.createEmptyBorder(32, 20, 24, 20)
    }

    init {
        background = Ink.background
        add(sidebar, BorderLayout.WEST)
        add(pages, BorderLayout.CENTER)
        val brand = row(10)
        brand.add(JLabel(LineIcon("brand", Ink.accent, 29)))
        brand.add(label("LectureVault", 18, true))
        sidebar.add(brand)
        sidebar.add(Box.createVerticalStrut(7))
        sidebar.add(label("СОБИРАЙ ЗНАНИЯ", 9, true, Ink.subtle).apply { border = BorderFactory.createEmptyBorder(0, 40, 0, 0) })
        sidebar.add(Box.createVerticalStrut(50))
        listOf(Triple(Page.RECORD, "Запись", "mic"), Triple(Page.LIBRARY, "Библиотека", "library"), Triple(Page.SETTINGS, "Настройки", "settings")).forEach { (page, text, icon) ->
            val button = ActionButton(text, icon).apply { horizontalAlignment = SwingConstants.LEFT; addActionListener { navigate(page) } }
            navButtons[page] = button
            sidebar.add(button)
            sidebar.add(Box.createVerticalStrut(8))
        }
        sidebar.add(Box.createVerticalGlue())
        sidebar.add(JSeparator().apply { foreground = Ink.line; background = Ink.line; maximumSize = Dimension(Int.MAX_VALUE, 1) })
        sidebar.add(Box.createVerticalStrut(22))
        sidebar.add(JLabel("OBSIDIAN", LineIcon("folder", Ink.muted, 15), SwingConstants.LEFT).apply { font = Ink.font(10, true); foreground = Ink.muted; iconTextGap = 8 })
        sidebar.add(Box.createVerticalStrut(10)); sidebar.add(vaultName)
        sidebar.add(Box.createVerticalStrut(5)); sidebar.add(vaultStatus)
        sidebar.add(Box.createVerticalStrut(22))
        sidebar.add(label("Твои лекции. Твоя система.", 10, color = Ink.subtle))
        pages.add(scroll(recordPage()), Page.RECORD.name)
        pages.add(scroll(libraryPage()), Page.LIBRARY.name)
        pages.add(scroll(settingsPage()), Page.SETTINGS.name)
        recordButton.addActionListener { onRecord() }
        importButton.addActionListener { onImport() }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderLibrary()
            override fun removeUpdate(e: DocumentEvent) = renderLibrary()
            override fun changedUpdate(e: DocumentEvent) = renderLibrary()
        })
        setLectures(emptyList())
        navigate(Page.RECORD)
    }

    private fun page(title: String, subtitle: String): JPanel = column().apply {
        border = BorderFactory.createEmptyBorder(36, 36, 32, 36)
        add(label(subtitle, 10, true, Ink.muted))
        add(Box.createVerticalStrut(10))
        add(label(title, 30, true))
        add(Box.createVerticalStrut(25))
    }

    private fun recordPage(): JPanel = page("Сохрани главное.", SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag("ru")).format(Date()).uppercase(Locale.forLanguageTag("ru"))).apply {
        add(notice)
        val hero = Surface(Ink.surface, 24).apply {
            layout = BorderLayout(26, 0)
            border = BorderFactory.createEmptyBorder(28, 30, 30, 30)
            maximumSize = Dimension(Int.MAX_VALUE, 316)
            preferredSize = Dimension(670, 316)
        }
        val information = column()
        information.add(statusBadge)
        information.add(Box.createVerticalStrut(25))
        information.add(label("ПРЕДМЕТ", 10, true, Ink.muted))
        information.add(Box.createVerticalStrut(9))
        subject.font = Ink.font(18, true)
        subject.maximumSize = Dimension(Int.MAX_VALUE, 46)
        information.add(subject)
        information.add(Box.createVerticalStrut(21))
        information.add(timer)
        information.add(Box.createVerticalStrut(7))
        information.add(detail)
        hero.add(information, BorderLayout.CENTER)
        val control = column().apply { preferredSize = Dimension(174, 200) }
        control.add(Box.createVerticalGlue())
        recordButton.alignmentX = CENTER_ALIGNMENT
        control.add(recordButton)
        control.add(Box.createVerticalStrut(13))
        recordCaption.alignmentX = CENTER_ALIGNMENT
        control.add(recordCaption)
        control.add(Box.createVerticalStrut(8))
        control.add(label("Аудио → конспект", 11, color = Ink.subtle).apply { alignmentX = CENTER_ALIGNMENT })
        control.add(Box.createVerticalGlue())
        hero.add(control, BorderLayout.EAST)
        add(hero)
        recovery.add(retry); recovery.add(openLast)
        recovery.isVisible = false
        add(recovery)
        add(Box.createVerticalStrut(16))
        val drop = Surface(Ink.background, 18, dashed = true).apply {
            layout = BorderLayout(18, 0)
            border = BorderFactory.createEmptyBorder(18, 22, 18, 22)
            maximumSize = Dimension(Int.MAX_VALUE, 94)
        }
        val importText = column().apply {
            add(label("Уже есть запись?", 14, true))
            add(Box.createVerticalStrut(5))
            add(label("Перетащите аудио сюда · до 24 МБ", 11, color = Ink.muted))
        }
        drop.add(importText, BorderLayout.CENTER)
        drop.add(importButton, BorderLayout.EAST)
        drop.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                currentState !in setOf(RecordingState.RECORDING, RecordingState.PROCESSING) && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val files = runCatching { (support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>() }.getOrNull() ?: return false
                onDrop(files)
                return true
            }
        }
        add(drop)
        add(Box.createVerticalStrut(16))
        setup.apply {
            layout = BorderLayout(16, 0)
            border = BorderFactory.createEmptyBorder(15, 20, 15, 18)
            maximumSize = Dimension(Int.MAX_VALUE, 78)
            add(label("Подключите ИИ и Obsidian", 13, true), BorderLayout.CENTER)
            add(ActionButton("Настроить", "arrow").apply { addActionListener { navigate(Page.SETTINGS) } }, BorderLayout.EAST)
        }
        add(setup)
        add(Box.createVerticalStrut(29))
        val heading = JPanel(BorderLayout()).apply {
            isOpaque = false; maximumSize = Dimension(Int.MAX_VALUE, 42)
            add(label("Последние лекции", 20, true), BorderLayout.WEST)
            add(ActionButton("Все лекции", "arrow").apply { addActionListener { navigate(Page.LIBRARY) }; preferredSize = Dimension(143, 36) }, BorderLayout.EAST)
        }
        add(heading)
        add(Box.createVerticalStrut(13)); add(recent)
    }

    private fun libraryPage(): JPanel = page("Библиотека", "ВСЁ ВАЖНОЕ ОСТАЁТСЯ С ТОБОЙ").apply {
        add(count)
        add(Box.createVerticalStrut(16))
        add(search)
        add(Box.createVerticalStrut(22))
        add(library)
    }

    private fun settingsPage(): JPanel = page("Настройки", "ПОДСТРОЙ ПОД СЕБЯ").apply {
        add(saveNotice)
        fun section(title: String, description: String): JPanel = Surface(Ink.surface, 22).apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(24, 26, 24, 26)
            add(label(title, 20, true))
            add(Box.createVerticalStrut(6)); add(body(description, 12)); add(Box.createVerticalStrut(24))
        }
        val ai = section("Обработка с ИИ", "Ключи находятся на защищённом сервере и не сохраняются на компьютере.")
        ai.add(consentBox)
        ai.add(Box.createVerticalStrut(10)); ai.add(deleteKeysButton)
        add(ai); add(Box.createVerticalStrut(18))
        val storage = section("Хранилище Obsidian", "Конспекты сохраняются в выбранную папку на компьютере.")
        storage.add(label("Корневая папка хранилища", 13, true)); storage.add(Box.createVerticalStrut(8))
        storage.add(vaultField); storage.add(Box.createVerticalStrut(10)); storage.add(chooseVaultButton)
        storage.add(Box.createVerticalStrut(18)); storage.add(label("Папка конспектов", 13, true)); storage.add(Box.createVerticalStrut(8)); storage.add(notesField)
        add(storage); add(Box.createVerticalStrut(20)); add(saveButton)
    }

    fun navigate(page: Page) {
        cards.show(pages, page.name)
        navButtons.forEach { (key, button) -> button.navSelected = key == page }
        onNavigate(page)
    }

    fun setConfiguration(vault: String?, hasKeys: Boolean) {
        setup.isVisible = vault == null || !hasKeys
        vaultName.text = vault ?: "Выбрать папку"
        vaultName.toolTipText = vault
        vaultStatus.text = if (vault == null) "Не подключено" else "Папка подключена"
        vaultStatus.foreground = if (vault == null) Ink.muted else Ink.green
        revalidate(); repaint()
    }

    fun showNotice(text: String, error: Boolean = false) {
        notice.text = text
        notice.foreground = if (error) Ink.red else Ink.green
        notice.isVisible = text.isNotBlank()
        revalidate(); repaint()
    }

    fun settingsNotice(text: String, error: Boolean = false) {
        saveNotice.text = text
        saveNotice.foreground = if (error) Ink.red else Ink.green
        saveNotice.isVisible = true
        revalidate(); repaint()
    }

    fun setState(state: RecordingState, message: String, canRetry: Boolean = false) {
        currentState = state
        val locked = state == RecordingState.RECORDING || state == RecordingState.PROCESSING
        subject.isEnabled = !locked
        importButton.isEnabled = !locked
        saveButton.isEnabled = !locked
        chooseVaultButton.isEnabled = !locked
        listOf(consentBox, vaultField, notesField).forEach { it.isEnabled = !locked }
        recordButton.isEnabled = state != RecordingState.PROCESSING
        recordButton.recording = state == RecordingState.RECORDING
        recordButton.toolTipText = if (recordButton.recording) "Остановить и создать конспект" else "Начать запись"
        recordButton.accessibleContext?.accessibleName = recordButton.toolTipText
        recordCaption.text = when (state) { RecordingState.RECORDING -> "Остановить"; RecordingState.PROCESSING -> "Обрабатываем…"; else -> "Начать запись" }
        val color = when (state) { RecordingState.RECORDING, RecordingState.ERROR -> Ink.red; RecordingState.PROCESSING -> Ink.accent; else -> Ink.green }
        statusBadge.text = when (state) { RecordingState.READY -> "ГОТОВО"; RecordingState.RECORDING -> "ИДЁТ ЗАПИСЬ"; RecordingState.PROCESSING -> "ОБРАБОТКА"; RecordingState.SUCCESS -> "СОХРАНЕНО"; RecordingState.ERROR -> "НУЖНО ВНИМАНИЕ" }
        statusBadge.icon = StatusDot(color); statusBadge.foreground = color
        detail.text = if (state == RecordingState.ERROR) "Аудио не удалено" else message
        showNotice(if (state == RecordingState.ERROR) message else "", state == RecordingState.ERROR)
        retry.isVisible = canRetry
        openLast.isVisible = state == RecordingState.SUCCESS
        recovery.isVisible = canRetry || state == RecordingState.SUCCESS
        revalidate(); repaint()
    }

    fun setProgress(message: String) { detail.text = message }
    fun setElapsed(seconds: Long) {
        val duration = seconds.coerceAtLeast(0)
        timer.text = "%02d:%02d:%02d".format(duration / 3600, duration % 3600 / 60, duration % 60)
    }

    fun showHistoryLoading() {
        if (lectures.isEmpty()) { recent.removeAll(); recent.add(emptyCard("Загружаем лекции…", "Читаем папку Obsidian", "refresh")); recent.revalidate() }
    }

    fun showHistoryError(text: String) {
        recent.removeAll(); recent.add(emptyCard(text, "Проверьте папку в настройках", "folder"))
        library.removeAll(); library.add(emptyCard(text, "Проверьте папку в настройках", "folder"))
        revalidate(); repaint()
    }

    fun setLectures(items: List<LectureItem>) {
        lectures = items
        count.text = if (items.size >= 300) "Последние 300 конспектов" else "Конспектов: ${items.size}"
        recent.removeAll()
        if (items.isEmpty()) recent.add(emptyCard("Здесь появится первая лекция", "Запишите её или выберите готовое аудио", "library"))
        else items.take(3).forEach { recent.add(lectureCard(it)); recent.add(Box.createVerticalStrut(8)) }
        renderLibrary()
        revalidate(); repaint()
    }

    private fun renderLibrary() {
        library.removeAll()
        val query = search.text.trim()
        val filtered = lectures.filter { it.title.contains(query, true) || it.course.contains(query, true) }
        if (filtered.isEmpty()) library.add(emptyCard(if (query.isBlank()) "Пока нет конспектов" else "Ничего не найдено", if (query.isBlank()) "Начните с новой лекции" else "Попробуйте другой предмет или название", "library"))
        else filtered.forEach { library.add(lectureCard(it)); library.add(Box.createVerticalStrut(10)) }
        library.revalidate(); library.repaint()
    }

    private fun lectureCard(item: LectureItem): JPanel = Surface(Ink.surface, 16).apply {
        layout = BorderLayout(17, 0)
        border = BorderFactory.createEmptyBorder(16, 18, 16, 16)
        maximumSize = Dimension(Int.MAX_VALUE, 83)
        add(JLabel(LineIcon("document", Ink.accent, 27)), BorderLayout.WEST)
        add(column().apply {
            add(label(item.title, 14, true).apply { toolTipText = item.title })
            add(Box.createVerticalStrut(6))
            add(label("${item.course}  ·  ${item.date}", 11, color = Ink.muted))
        }, BorderLayout.CENTER)
        add(row(6).apply {
            add(ActionButton("", "delete").apply {
                preferredSize = Dimension(42, 42)
                toolTipText = "Удалить конспект"; accessibleContext?.accessibleName = "Удалить ${item.title}"
                addActionListener { onDeleteNote(item.file) }
            })
            add(ActionButton("", "arrow").apply {
                preferredSize = Dimension(42, 42)
            toolTipText = "Открыть конспект"; accessibleContext?.accessibleName = "Открыть ${item.title}"
            addActionListener { onOpenNote(item.file) }
            })
        }, BorderLayout.EAST)
    }

    private fun emptyCard(title: String, subtitle: String, icon: String): JPanel = Surface(Ink.surface, 16).apply {
        layout = BorderLayout(18, 0)
        border = BorderFactory.createEmptyBorder(24, 22, 24, 22)
        maximumSize = Dimension(Int.MAX_VALUE, 100)
        add(JLabel(LineIcon(icon, Ink.subtle, 28)), BorderLayout.WEST)
        add(column().apply { add(label(title, 14, true)); add(Box.createVerticalStrut(7)); add(label(subtitle, 12, color = Ink.muted)) }, BorderLayout.CENTER)
    }
}

private fun label(text: String, size: Int, bold: Boolean = false, color: Color = Ink.text) = JLabel(text).apply {
    putClientProperty("html.disable", true)
    foreground = color; font = Ink.font(size, bold); alignmentX = Component.LEFT_ALIGNMENT
}

private fun body(text: String, size: Int): JTextArea = JTextArea(text).apply {
    font = Ink.font(size); foreground = Ink.muted; isOpaque = false; isEditable = false
    lineWrap = true; wrapStyleWord = true; isFocusable = false; alignmentX = Component.LEFT_ALIGNMENT
    border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
}

private fun input(text: String, accessibleName: String) = PromptTextField(text, accessibleName).apply { styleField(this, accessibleName) }
private fun password(accessibleName: String) = JPasswordField().apply { styleField(this, accessibleName); echoChar = '•' }
private fun styleField(field: JTextField, name: String) {
    field.font = Ink.font(15)
    field.background = Ink.elevated; field.foreground = Ink.text; field.caretColor = Ink.accent
    field.disabledTextColor = Ink.muted; field.selectionColor = Color(0x724332); field.selectedTextColor = Ink.text
    field.border = FieldBorder(); field.maximumSize = Dimension(Int.MAX_VALUE, 46); field.preferredSize = Dimension(250, 46)
    field.alignmentX = Component.LEFT_ALIGNMENT
    field.accessibleContext?.accessibleName = name
    field.toolTipText = name
    field.addFocusListener(object : FocusAdapter() { override fun focusGained(e: FocusEvent) = field.repaint(); override fun focusLost(e: FocusEvent) = field.repaint() })
}

internal class PromptTextField(text: String, private val prompt: String) : JTextField(text) {
    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        if (text.isNotEmpty() || hasFocus()) return
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font
        g.color = Ink.subtle
        val metrics = g.fontMetrics
        g.drawString(prompt, insets.left, (height - metrics.height) / 2 + metrics.ascent)
        g.dispose()
    }
}

private fun appIcon(): Image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).also { image ->
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Ink.background; g.fillRoundRect(0, 0, 64, 64, 15, 15)
    g.color = Ink.accent
    val heights = intArrayOf(22, 38, 28, 44, 20)
    heights.forEachIndexed { index, height ->
        val x = 12 + index * 10
        g.fillRoundRect(x, (64 - height) / 2, 5, height, 5, 5)
    }
    g.dispose()
}

private class FieldBorder : AbstractBorder() {
    override fun getBorderInsets(c: Component) = Insets(10, 13, 10, 13)
    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
        val copy = g.create() as Graphics2D
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = if (c.hasFocus()) Ink.accent else Ink.line
        copy.drawRoundRect(x, y, w - 1, h - 1, 12, 12); copy.dispose()
    }
}

private fun column() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
private fun row(gap: Int) = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 50) }

private fun scroll(content: JPanel): JScrollPane {
    val holder = object : JPanel(BorderLayout()), Scrollable {
        init { background = Ink.background; add(content, BorderLayout.NORTH) }
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 24
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height - 40
    }
    return JScrollPane(holder).apply {
        border = BorderFactory.createEmptyBorder(); horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = Ink.background
        verticalScrollBar.apply {
            preferredSize = Dimension(8, 0)
            setUI(object : BasicScrollBarUI() {
                override fun configureScrollBarColors() { thumbColor = Ink.line; trackColor = Ink.background }
                override fun createDecreaseButton(o: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
                override fun createIncreaseButton(o: Int) = JButton().apply { preferredSize = Dimension(0, 0) }
            })
        }
    }
}

private class Surface(private val fill: Color, private val radius: Int, private val dashed: Boolean = false) : JPanel() {
    init { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = fill; g.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
        g.color = Ink.line
        if (dashed) g.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(4f, 5f), 0f)
        g.drawRoundRect(0, 0, width - 1, height - 1, radius, radius); g.dispose()
        super.paintComponent(graphics)
    }
}

internal class ActionButton(text: String, private val symbol: String, private val primary: Boolean = false) : JButton(text) {
    var navSelected = false
        set(value) { field = value; repaint() }
    init {
        font = Ink.font(13, true); foreground = if (primary) Ink.background else Ink.text
        isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        margin = Insets(10, 14, 10, 14); iconTextGap = 10
        preferredSize = Dimension(if (text.isBlank()) 44 else 172, 46)
        maximumSize = Dimension(Int.MAX_VALUE, 46); alignmentX = Component.LEFT_ALIGNMENT
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        putClientProperty("html.disable", true)
        addMouseListener(object : MouseAdapter() { override fun mouseEntered(e: MouseEvent) = repaint(); override fun mouseExited(e: MouseEvent) = repaint() })
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val active = navSelected || primary
        g.color = when { !isEnabled -> Ink.elevated; primary && model.isRollover -> Color(0xFF936E); primary -> Ink.accent; navSelected -> Color(0x33251F); model.isRollover -> Color(0x2B3039); else -> Ink.elevated }
        g.fillRoundRect(0, 0, width - 1, height - 1, 13, 13)
        if (hasFocus()) { g.color = Ink.accent; g.stroke = BasicStroke(2f); g.drawRoundRect(2, 2, width - 5, height - 5, 12, 12) }
        g.dispose()
        foreground = when { !isEnabled -> Ink.subtle; primary -> Ink.background; navSelected -> Ink.accent; else -> Ink.text }
        icon = LineIcon(symbol, foreground, 17)
        super.paintComponent(graphics)
    }
}

internal class RecordButton : JButton() {
    var recording = false
        set(value) { field = value; repaint() }
    init {
        preferredSize = Dimension(132, 132); minimumSize = preferredSize; maximumSize = preferredSize
        isOpaque = false; isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        accessibleContext?.accessibleName = "Начать запись"
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val tone = if (recording) Ink.red else Ink.accent
        g.color = if (isEnabled) Color(tone.red, tone.green, tone.blue, 18) else Ink.elevated
        g.fillOval(0, 0, width - 1, height - 1)
        g.color = if (isEnabled) Color(tone.red, tone.green, tone.blue, 45) else Ink.line
        g.stroke = BasicStroke(1f); g.drawOval(1, 1, width - 3, height - 3)
        g.color = if (isEnabled) (if (model.isRollover) tone.brighter() else tone) else Ink.elevated
        g.fillOval(15, 15, width - 30, height - 30)
        if (hasFocus()) { g.color = Ink.text; g.stroke = BasicStroke(2f); g.drawOval(7, 7, width - 15, height - 15) }
        if (recording) { g.color = Ink.background; g.fillRoundRect(width / 2 - 13, height / 2 - 13, 26, 26, 7, 7) }
        else LineIcon("mic", if (isEnabled) Ink.background else Ink.subtle, 34).paintIcon(this, g, width / 2 - 17, height / 2 - 17)
        g.dispose()
    }
    override fun contains(x: Int, y: Int): Boolean = java.awt.geom.Ellipse2D.Double(0.0, 0.0, width.toDouble(), height.toDouble()).contains(x.toDouble(), y.toDouble())
}

private class StatusDot(private val color: Color) : Icon {
    override fun getIconWidth() = 7
    override fun getIconHeight() = 7
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) { g.color = color; g.fillOval(x, y, 7, 7) }
}

internal class LineIcon(private val kind: String, private val color: Color, private val size: Int) : Icon {
    override fun getIconWidth() = size
    override fun getIconHeight() = size
    override fun paintIcon(c: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.translate(x, y); g.scale(size / 24.0, size / 24.0)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color; g.stroke = BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) = g.drawLine(x1, y1, x2, y2)
        when (kind) {
            "mic" -> { g.drawRoundRect(8, 2, 8, 13, 8, 8); g.drawArc(5, 7, 14, 12, 180, 180); line(12, 19, 12, 22); line(9, 22, 15, 22) }
            "library" -> { g.drawRoundRect(3, 4, 6, 17, 2, 2); line(6, 7, 6, 10); g.drawRoundRect(12, 4, 7, 17, 2, 2); line(15, 7, 15, 10); line(21, 6, 23, 20) }
            "settings" -> { line(3, 6, 21, 6); line(3, 12, 21, 12); line(3, 18, 21, 18); g.color = Ink.surface; g.fillOval(7, 3, 6, 6); g.fillOval(13, 9, 6, 6); g.fillOval(5, 15, 6, 6); g.color = color; g.drawOval(7, 3, 6, 6); g.drawOval(13, 9, 6, 6); g.drawOval(5, 15, 6, 6) }
            "upload" -> { line(12, 3, 12, 15); line(7, 8, 12, 3); line(17, 8, 12, 3); line(4, 15, 4, 21); line(4, 21, 20, 21); line(20, 15, 20, 21) }
            "folder" -> { val p = Path2D.Double(); p.moveTo(3.0, 5.0); p.lineTo(9.0, 5.0); p.lineTo(11.0, 8.0); p.lineTo(21.0, 8.0); p.lineTo(21.0, 20.0); p.lineTo(3.0, 20.0); p.closePath(); g.draw(p) }
            "document" -> { g.drawRoundRect(5, 2, 14, 20, 3, 3); line(9, 8, 15, 8); line(9, 12, 15, 12); line(9, 16, 13, 16) }
            "arrow" -> { line(5, 12, 19, 12); line(14, 7, 19, 12); line(14, 17, 19, 12) }
            "check" -> { line(5, 12, 10, 17); line(10, 17, 20, 7) }
            "delete" -> { g.drawRoundRect(6, 7, 12, 14, 2, 2); line(4, 7, 20, 7); line(9, 3, 15, 3); line(10, 11, 10, 17); line(14, 11, 14, 17) }
            "refresh" -> { g.drawArc(4, 4, 16, 16, 35, 300); line(20, 3, 20, 9); line(14, 9, 20, 9) }
            "brand" -> { g.stroke = BasicStroke(2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); line(3, 7, 3, 17); line(8, 3, 8, 21); line(13, 7, 13, 17); line(18, 5, 18, 19); line(23, 9, 23, 15) }
        }
        g.dispose()
    }
}

internal fun renderDesktopPreviews(directory: File) {
    require(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    SwingUtilities.invokeAndWait {
        val view = DesktopView("Линейная алгебра")
        view.setConfiguration("Учеба", true)
        view.setLectures(listOf(
            LectureItem(File("preview-matrices.md"), "Матрицы и линейные преобразования", "Линейная алгебра", "10 сент. 2026"),
            LectureItem(File("preview-philosophy.md"), "Познание и границы научного знания", "Философия", "9 сент. 2026"),
            LectureItem(File("preview-physics.md"), "Законы сохранения энергии", "Физика", "8 сент. 2026")))
        fun render(name: String, width: Int, height: Int) {
            view.setSize(width, height)
            fun layout(component: Component) {
                if (component is Container) { component.doLayout(); component.components.forEach(::layout) }
            }
            repeat(3) { layout(view) }
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics(); view.printAll(g); g.dispose()
            ImageIO.write(image, "png", File(directory, name))
        }
        render("desktop-record-wide.png", 1260, 880)
        render("desktop-record-compact.png", 900, 720)
        view.navigate(Page.SETTINGS)
        view.vaultField.text = "C:\\Users\\student\\Documents\\Учеба"
        render("desktop-settings.png", 1260, 960)
        view.navigate(Page.LIBRARY)
        render("desktop-library.png", 1260, 880)
        view.navigate(Page.RECORD)
        view.setState(RecordingState.RECORDING, "Микрофон включён"); view.setElapsed(765)
        render("desktop-recording.png", 1260, 880)
        view.setState(RecordingState.PROCESSING, "Составляем конспект")
        render("desktop-processing.png", 1260, 880)
        view.setState(RecordingState.ERROR, "Не удалось подключиться. Проверьте интернет и повторите обработку.", true)
        render("desktop-error.png", 1260, 960)
        view.setState(RecordingState.READY, "Микрофон выключен")
        view.setElapsed(0); view.setLectures(emptyList()); view.setConfiguration(null, false)
        render("desktop-empty.png", 1260, 880)
    }
}
