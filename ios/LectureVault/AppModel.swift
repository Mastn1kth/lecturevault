import AVFoundation
import Foundation
import UserNotifications

struct LectureNote: Identifiable, Hashable {
    let relativePath: String
    let title: String
    let course: String
    let modifiedAt: Date
    var id: String { relativePath }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var course = UserDefaults.standard.string(forKey: "course") ?? ""
    @Published var notesFolder = UserDefaults.standard.string(forKey: "notesFolder") ?? "Лекции"
    @Published var cloudConsent = UserDefaults.standard.bool(forKey: "cloudConsent")
    @Published private(set) var status = "Введите предмет и начните запись"
    @Published private(set) var elapsed = "00:00:00"
    @Published private(set) var isRecording = false
    @Published private(set) var isBusy = false
    @Published private(set) var recentNotes: [LectureNote] = []
    private var recorder: AVAudioRecorder?
    private var recordedParts: [URL] = []
    private var timer: Timer?
    private var startedAt: Date?
    private var vaultBookmark: Data? { UserDefaults.standard.data(forKey: "vaultBookmark") }
    private var audioIndex: [String: [String]] { get { UserDefaults.standard.dictionary(forKey: "lectureAudioIndex") as? [String: [String]] ?? [:] } set { UserDefaults.standard.set(newValue, forKey: "lectureAudioIndex") } }

    init() {
        // Old releases kept provider credentials in Keychain. The gateway release never uses them.
        Keychain.delete(account: "groq")
        Keychain.delete(account: "gemini")
        refreshHistory()
    }

    var vaultName: String {
        guard let bookmark = vaultBookmark,
              let url = try? URL(resolvingBookmarkData: bookmark, options: [.withoutUI, .withSecurityScope], relativeTo: nil, bookmarkDataIsStale: nil)
        else { return "Не выбран" }
        return url.lastPathComponent
    }
    var isConfigured: Bool { vaultBookmark != nil && cloudConsent }

    func toggleRecording() {
        if isRecording { stopAndProcess(); return }
        guard !course.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { status = "Введите название предмета"; return }
        Task {
            let microphoneAllowed = await requestMicrophonePermission()
            guard microphoneAllowed else { status = "Нет доступа к микрофону"; return }
            do {
                #if os(iOS)
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.record, mode: .spokenAudio, options: [.allowBluetooth])
                try session.setActive(true)
                #endif
                recordedParts = []
                try startAudioPart()
                UserDefaults.standard.set(course, forKey: "course")
                startedAt = Date(); isRecording = true; status = "Идёт запись…"
                timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in Task { @MainActor in self?.updateTimer() } }
            } catch { status = error.localizedDescription }
        }
    }

    func importAudio(_ urls: [URL]) { process(urls) }

    func selectVault(_ url: URL) {
        do {
            guard FileManager.default.fileExists(atPath: url.appendingPathComponent(".obsidian", isDirectory: true).path) else {
                throw AppError.message("Выберите папку самого vault Obsidian")
            }
            let bookmark = try url.bookmarkData(options: [.withSecurityScope], includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(bookmark, forKey: "vaultBookmark")
            objectWillChange.send(); status = "Vault выбран: \(url.lastPathComponent)"; refreshHistory()
        } catch { status = "Не удалось сохранить доступ к папке" }
    }

    func saveSettings(consent: Bool) {
        guard consent else { status = "Подтвердите отправку аудио и текста на сервер ИИ"; return }
        cloudConsent = true
        UserDefaults.standard.set(true, forKey: "cloudConsent")
        UserDefaults.standard.set(notesFolder, forKey: "notesFolder")
        objectWillChange.send(); status = "Настройки сохранены"
    }

    func disableCloudProcessing() {
        cloudConsent = false
        UserDefaults.standard.set(false, forKey: "cloudConsent")
        objectWillChange.send()
        status = "Облачная обработка отключена"
    }

    private func stopAndProcess() {
        recorder?.stop(); recorder = nil; timer?.invalidate(); timer = nil; isRecording = false
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false)
        #endif
        process(recordedParts)
    }

    private func process(_ urls: [URL]) {
        guard !isBusy else { return }
        guard cloudConsent else { status = "Разрешите облачную обработку в настройках"; return }
        guard let bookmark = vaultBookmark else { status = "Выберите папку vault Obsidian"; return }
        let selectedCourse = course.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !selectedCourse.isEmpty else { status = "Введите название предмета"; return }
        isBusy = true
        Task {
            do {
                var stale = false
                let vault = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI, .withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale)
                guard vault.startAccessingSecurityScopedResource() else { throw AppError.message("Нет доступа к vault") }
                defer { vault.stopAccessingSecurityScopedResource() }
                var plain: [String] = []; var timed: [String] = []
                for (index, source) in urls.enumerated() {
                    status = "Расшифровка: часть \(index + 1) из \(urls.count)"
                    let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
                    let result = try await APIClient.transcribe(source)
                    plain.append(result.text); timed.append("### Часть \(index + 1)\n\(result.timestamped)")
                }
                status = "Создаём конспект через облачный ИИ…"
                let summary = try await APIClient.summarize(plain.joined(separator: "\n\n"), course: selectedCourse)
                let saved = try NoteStore.save(vault: vault, folder: notesFolder, course: selectedCourse, summary: summary, transcript: timed.joined(separator: "\n\n"))
                let copiedAudio = try archiveAudio(urls)
                let relative = String(saved.standardizedFileURL.path.dropFirst(vault.standardizedFileURL.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                var index = audioIndex; index[relative] = copiedAudio.map(\.path); audioIndex = index
                status = "Готово ✓\n\(saved.lastPathComponent)"; refreshHistory(vault: vault)
                await notifyLectureReady(title: saved.deletingPathExtension().lastPathComponent)
            } catch { status = "Ошибка: \(error.localizedDescription)" }
            isBusy = false
        }
    }

    private func updateTimer() {
        let seconds = Int(Date().timeIntervalSince(startedAt ?? Date()))
        elapsed = String(format: "%02d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
        if seconds > 0, seconds % 1_500 == 0 {
            recorder?.stop()
            do { try startAudioPart() } catch { status = "Не удалось продолжить запись: \(error.localizedDescription)"; stopAndProcess() }
        }
    }

    private func startAudioPart() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("lecture-\(UUID().uuidString).m4a")
        let next = try AVAudioRecorder(url: url, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000,
        ])
        guard next.record() else { throw AppError.message("Не удалось начать запись") }
        recorder = next
        recordedParts.append(url)
    }

    private func requestMicrophonePermission() async -> Bool {
        #if os(iOS)
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { continuation.resume(returning: $0) }
        }
        #else
        return await withCheckedContinuation { continuation in
            AVCaptureDevice.requestAccess(for: .audio) { continuation.resume(returning: $0) }
        }
        #endif
    }

    func readNote(_ note: LectureNote) throws -> String {
        try withVault { vault in
            let url = try safeNoteURL(vault: vault, relativePath: note.relativePath)
            return try String(contentsOf: url, encoding: .utf8)
        }
    }

    func deleteNote(_ note: LectureNote) throws {
        try withVault { vault in
            let url = try safeNoteURL(vault: vault, relativePath: note.relativePath)
            try FileManager.default.removeItem(at: url)
        }
        let audio = audioIndex[note.relativePath] ?? []
        audio.forEach { try? FileManager.default.removeItem(atPath: $0) }
        var index = audioIndex; index.removeValue(forKey: note.relativePath); audioIndex = index
        status = "Лекция удалена"
        refreshHistory()
    }

    func audioURLs(for note: LectureNote) -> [URL] { (audioIndex[note.relativePath] ?? []).map(URL.init(fileURLWithPath:)).filter { FileManager.default.fileExists(atPath: $0.path) } }

    private func archiveAudio(_ sources: [URL]) throws -> [URL] {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("LectureVaultAudio", isDirectory: true).appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return try sources.enumerated().map { index, source in
            let extensionName = source.pathExtension.isEmpty ? "m4a" : source.pathExtension
            let destination = root.appendingPathComponent("часть-\(index + 1).\(extensionName)")
            let access = source.startAccessingSecurityScopedResource(); defer { if access { source.stopAccessingSecurityScopedResource() } }
            try FileManager.default.copyItem(at: source, to: destination)
            return destination
        }
    }

    private func notifyLectureReady(title: String) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        if settings.authorizationStatus == .notDetermined { _ = try? await center.requestAuthorization(options: [.alert, .sound, .badge]) }
        let content = UNMutableNotificationContent(); content.title = "Лекция готова"; content.body = title; content.sound = .default
        try? await center.add(UNNotificationRequest(identifier: "lecture-\(UUID().uuidString)", content: content, trigger: nil))
    }

    private func withVault<T>(_ action: (URL) throws -> T) throws -> T {
        guard let bookmark = vaultBookmark else { throw AppError.message("Выберите папку vault Obsidian") }
        var stale = false
        let vault = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI, .withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale)
        guard vault.startAccessingSecurityScopedResource() else { throw AppError.message("Нет доступа к vault") }
        defer { vault.stopAccessingSecurityScopedResource() }
        return try action(vault)
    }

    private func safeNoteURL(vault: URL, relativePath: String) throws -> URL {
        let parts = relativePath.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map(String.init)
        guard !parts.isEmpty, !parts.contains(where: { $0.isEmpty || $0 == "." || $0 == ".." || $0.lowercased() == ".obsidian" }) else {
            throw AppError.message("Некорректный путь конспекта")
        }
        let target = parts.reduce(vault) { $0.appendingPathComponent($1) }.standardizedFileURL
        let rootPath = vault.standardizedFileURL.path.hasSuffix("/") ? vault.standardizedFileURL.path : vault.standardizedFileURL.path + "/"
        guard target.path.hasPrefix(rootPath), target.pathExtension.lowercased() == "md" else {
            throw AppError.message("Некорректный путь конспекта")
        }
        return target
    }

    private func refreshHistory(vault suppliedVault: URL? = nil) {
        do {
            var stale = false
            let vault: URL
            if let suppliedVault {
                vault = suppliedVault
            } else {
                guard let bookmark = vaultBookmark else { recentNotes = []; return }
                vault = try URL(resolvingBookmarkData: bookmark, options: [.withoutUI, .withSecurityScope], relativeTo: nil, bookmarkDataIsStale: &stale)
            }
            let started = suppliedVault == nil ? vault.startAccessingSecurityScopedResource() : false
            defer { if started { vault.stopAccessingSecurityScopedResource() } }
            let root = notesFolder.replacingOccurrences(of: "\\", with: "/").split(separator: "/").reduce(vault) { $0.appendingPathComponent(String($1), isDirectory: true) }
            let keys: [URLResourceKey] = [.contentModificationDateKey, .isRegularFileKey]
            let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: keys)?.allObjects as? [URL] ?? []
            recentNotes = files.filter { $0.pathExtension.lowercased() == "md" }
                .sorted {
                    ((try? $0.resourceValues(forKeys: Set(keys)).contentModificationDate) ?? .distantPast) >
                    ((try? $1.resourceValues(forKeys: Set(keys)).contentModificationDate) ?? .distantPast)
                }
                .prefix(100).map { file in
                    let modified = (try? file.resourceValues(forKeys: Set(keys)).contentModificationDate) ?? .distantPast
                    let relative = String(file.standardizedFileURL.path.dropFirst(vault.standardizedFileURL.path.count)).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                    return LectureNote(
                        relativePath: relative,
                        title: file.deletingPathExtension().lastPathComponent,
                        course: file.deletingLastPathComponent().lastPathComponent,
                        modifiedAt: modified
                    )
                }
        } catch { recentNotes = [] }
    }
}

enum AppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(value) = self { return value }; return nil }
}

enum NoteStore {
    static func save(vault: URL, folder: String, course: String, summary: String, transcript: String) throws -> URL {
        let manager = FileManager.default
        let safeCourse = safe(course)
        let folderParts = folder.replacingOccurrences(of: "\\", with: "/").split(separator: "/").map { String($0).trimmingCharacters(in: .whitespaces) }
        guard !folderParts.contains(where: { $0 == "." || $0 == ".." || $0.lowercased() == ".obsidian" }) else { throw AppError.message("Недопустимый путь конспектов") }
        let destination = (folderParts + [safeCourse]).filter { !$0.isEmpty }.reduce(vault) { $0.appendingPathComponent(safe($1), isDirectory: true) }
        try manager.createDirectory(at: destination, withIntermediateDirectories: true)
        let safeSummary = sanitizeMarkdown(summary)
        let title = safeSummary.split(separator: "\n").first(where: { $0.hasPrefix("# ") }).map { safe(String($0.dropFirst(2))) } ?? "Лекция"
        let formatter = DateFormatter(); formatter.dateFormat = "yyyy-MM-dd"
        var target = destination.appendingPathComponent("\(formatter.string(from: Date())) — \(title).md")
        var index = 2
        while manager.fileExists(atPath: target.path) { target = destination.appendingPathComponent("\(formatter.string(from: Date())) — \(title) (\(index)).md"); index += 1 }
        let body = """
---
type: lecture
lecturevault_id: "\(UUID().uuidString)"
title: "\(title)"
course: "\(safeCourse)"
created: "\(ISO8601DateFormatter().string(from: Date()))"
tags:
  - lecturevault
---

\(safeSummary)

---

## Полная расшифровка

\(transcript)
"""
        try body.write(to: target, atomically: true, encoding: .utf8)
        return target
    }
    private static func safe(_ value: String) -> String {
        String(value.map { "/\\:*?\"<>|".contains($0) || $0.isNewline ? " " : $0 }).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private static func sanitizeMarkdown(_ value: String) -> String {
        var result = value
        let patterns = ["<\\s*/?\\s*(script|iframe|object|embed|style|link|meta)\\b[^>]*>", "!\\[([^]]*)]\\(\\s*https?://[^)]+\\)"]
        for pattern in patterns { result = result.replacingOccurrences(of: pattern, with: "", options: [.regularExpression, .caseInsensitive]) }
        result = result.replacingOccurrences(of: "(?i)(javascript|file|obsidian)\\s*:", with: "blocked-link:", options: .regularExpression)
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
