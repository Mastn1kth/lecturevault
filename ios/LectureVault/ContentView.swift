import SwiftUI
import AVFoundation
import UniformTypeIdentifiers

private enum LV {
    static let background = Color(red: 13 / 255, green: 15 / 255, blue: 18 / 255)
    static let surface = Color(red: 23 / 255, green: 26 / 255, blue: 32 / 255)
    static let elevated = Color(red: 32 / 255, green: 36 / 255, blue: 43 / 255)
    static let text = Color(red: 243 / 255, green: 241 / 255, blue: 236 / 255)
    static let muted = Color(red: 170 / 255, green: 176 / 255, blue: 188 / 255)
    static let line = Color(red: 42 / 255, green: 47 / 255, blue: 56 / 255)
    static let accent = Color(red: 255 / 255, green: 120 / 255, blue: 73 / 255)
    static let success = Color(red: 105 / 255, green: 216 / 255, blue: 160 / 255)
    static let danger = Color(red: 255 / 255, green: 107 / 255, blue: 114 / 255)
}

private enum MainPage { case record, library }

struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @State private var page: MainPage = .record
    @State private var search = ""
    @State private var showingAudioPicker = false
    @State private var showingVaultPicker = false
    @State private var showingSettings = false
    @State private var selectedNote: LectureNote?
    @State private var pendingDelete: LectureNote?

    private var shownNotes: [LectureNote] {
        let filtered = model.recentNotes.filter {
            search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.course.localizedCaseInsensitiveContains(search)
        }
        return page == .record ? Array(filtered.prefix(4)) : filtered
    }

    private var shownFailedAudios: [FailedLectureAudio] {
        let filtered = model.failedAudios.filter {
            search.isEmpty || $0.course.localizedCaseInsensitiveContains(search)
        }
        return page == .record ? Array(filtered.prefix(2)) : filtered
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            LV.background.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 0) {
                    brandBar
                    if page == .record { recorderPage } else { libraryPage }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 104)
            }
            VStack(spacing: 8) {
                Spacer()
                if !model.isRecording && model.status != "Введите предмет и начните запись" {
                    Text(model.status)
                        .font(.caption.weight(.medium)).lineLimit(3).multilineTextAlignment(.center)
                        .padding(.horizontal, 15).padding(.vertical, 10)
                        .background(LV.elevated, in: Capsule())
                        .overlay(Capsule().stroke(LV.line))
                        .padding(.horizontal, 20)
                }
                bottomBar
            }
        }
        .foregroundStyle(LV.text)
        .preferredColorScheme(.dark)
        .fileImporter(isPresented: $showingAudioPicker, allowedContentTypes: [.audio], allowsMultipleSelection: true) {
            if case let .success(urls) = $0 { model.importAudio(urls) }
        }
        .fileImporter(isPresented: $showingVaultPicker, allowedContentTypes: [.folder]) {
            if case let .success(url) = $0 { model.selectVault(url) }
        }
        .sheet(isPresented: $showingSettings) {
            SettingsView(showVaultPicker: {
                showingSettings = false
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { showingVaultPicker = true }
            }).environmentObject(model)
        }
        .sheet(item: $selectedNote) { note in
            LectureReaderView(note: note).environmentObject(model)
        }
        .alert(item: $pendingDelete) { note in
            Alert(
                title: Text("Удалить лекцию?"),
                message: Text("Файл будет удалён из Obsidian. Это действие нельзя отменить."),
                primaryButton: .destructive(Text("Удалить")) { try? model.deleteNote(note) },
                secondaryButton: .cancel(Text("Отмена"))
            )
        }
    }

    private var brandBar: some View {
        HStack(spacing: 11) {
            ZStack {
                Circle().fill(LV.accent).frame(width: 34, height: 34)
                Image(systemName: "doc.text.fill").font(.system(size: 15, weight: .bold)).foregroundStyle(LV.background)
            }
            Text("LectureVault").font(.system(size: 20, weight: .bold, design: .rounded))
            Spacer()
            Button { showingSettings = true } label: {
                Image(systemName: "gearshape.fill").font(.system(size: 17)).frame(width: 42, height: 42)
                    .background(LV.surface, in: Circle()).foregroundStyle(LV.muted)
            }.accessibilityLabel("Настройки")
        }.padding(.top, 10).padding(.bottom, 22)
    }

    private var recorderPage: some View {
        VStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(Date.now.formatted(.dateTime.weekday(.wide).day().month(.wide).locale(Locale(identifier: "ru_RU"))))
                    .font(.caption.weight(.semibold)).textCase(.uppercase).foregroundStyle(LV.muted)
            }.frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 12) {
                Image(systemName: "book.closed.fill").foregroundStyle(LV.accent)
                TextField("Предмет", text: $model.course)
                    .disabled(model.isRecording)
            }
            .padding(.horizontal, 16).frame(height: 56)
            .background(LV.surface, in: RoundedRectangle(cornerRadius: 17))
            .overlay(RoundedRectangle(cornerRadius: 17).stroke(LV.line))

            recorderCard

            Button { showingAudioPicker = true } label: {
                Label("Загрузить готовое аудио", systemImage: "waveform.badge.plus")
                    .font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity).frame(height: 54)
                    .background(LV.elevated, in: RoundedRectangle(cornerRadius: 17))
            }.foregroundStyle(LV.text).disabled(model.isRecording || model.isBusy)

            if !model.isConfigured {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Подключить Obsidian").font(.headline)
                        Text("Остался один шаг").font(.caption).foregroundStyle(LV.muted)
                    }
                    Spacer()
                    Button("Настроить") { showingSettings = true }
                        .font(.subheadline.bold()).padding(.horizontal, 14).frame(height: 42)
                        .background(LV.accent, in: RoundedRectangle(cornerRadius: 13)).foregroundStyle(LV.background)
                }
                .padding(16).background(LV.surface, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(LV.line))
            }

            lectureList(title: "Последние лекции")
        }
    }

    private var recorderCard: some View {
        ZStack {
            LinearGradient(colors: [Color(red: 46/255, green: 34/255, blue: 31/255), LV.surface], startPoint: .topLeading, endPoint: .bottomTrailing)
            Canvas { context, size in
                let center = CGPoint(x: size.width / 2, y: size.height * 0.61)
                for radius in stride(from: 66.0, through: 126.0, by: 30.0) {
                    context.stroke(Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)), with: .color(LV.accent.opacity(0.09)), lineWidth: 1)
                }
            }.accessibilityHidden(true)
            VStack(spacing: 17) {
                Text(model.isRecording ? "ИДЁТ ЗАПИСЬ" : model.isBusy ? "ОБРАБОТКА" : "ГОТОВО")
                    .font(.caption2.bold()).tracking(0.8).foregroundStyle(model.isRecording ? LV.danger : LV.accent)
                    .padding(.horizontal, 12).padding(.vertical, 7).background(LV.background.opacity(0.5), in: Capsule())
                Text(model.elapsed).font(.system(size: 40, weight: .bold, design: .monospaced))
                Button(action: model.toggleRecording) {
                    Image(systemName: model.isRecording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 32, weight: .bold)).frame(width: 88, height: 88)
                        .background(model.isRecording ? LV.danger : LV.accent, in: Circle()).foregroundStyle(LV.background)
                }.disabled(model.isBusy).accessibilityLabel(model.isRecording ? "Остановить запись" : "Начать запись")
                Text(model.isRecording ? "Нажмите, чтобы завершить" : "Нажмите, чтобы начать")
                    .font(.caption).foregroundStyle(LV.muted)
            }
        }
        .frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 24))
    }

    private var libraryPage: some View {
        VStack(spacing: 14) {
            Text("Все лекции").font(.system(size: 32, weight: .bold, design: .rounded))
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(LV.muted)
                TextField("Найти лекцию", text: $search)
            }.padding(.horizontal, 16).frame(height: 54).background(LV.surface, in: RoundedRectangle(cornerRadius: 17))
            lectureList(title: "")
        }
    }

    @ViewBuilder private func lectureList(title: String) -> some View {
        VStack(spacing: 10) {
            if !title.isEmpty {
                HStack { Text(title).font(.title2.bold()); Spacer(); Text("\(model.recentNotes.count)").foregroundStyle(LV.muted) }
                    .padding(.top, 10)
            }
            if shownNotes.isEmpty && shownFailedAudios.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "books.vertical.fill").font(.title).foregroundStyle(LV.muted)
                    Text(search.isEmpty ? "Здесь появятся лекции" : "Ничего не найдено").font(.headline)
                    if search.isEmpty { Text("Запишите или загрузите аудио").font(.caption).foregroundStyle(LV.muted) }
                }.frame(maxWidth: .infinity).padding(.vertical, 34)
            } else {
                ForEach(shownNotes) { note in
                    HStack(spacing: 13) {
                        Image(systemName: "doc.text.fill").foregroundStyle(LV.accent).frame(width: 46, height: 46)
                            .background(LV.elevated, in: RoundedRectangle(cornerRadius: 14))
                        VStack(alignment: .leading, spacing: 5) {
                            Text(note.title).font(.headline).lineLimit(2)
                            Text(note.course).font(.caption.weight(.semibold)).foregroundStyle(LV.success)
                        }
                        Spacer()
                        Button { pendingDelete = note } label: {
                            Image(systemName: "trash.fill").foregroundStyle(LV.danger).frame(width: 42, height: 42)
                        }.accessibilityLabel("Удалить лекцию")
                    }
                    .padding(15).background(LV.surface, in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(LV.line))
                    .contentShape(Rectangle())
                    .onTapGesture { selectedNote = note }
                }
                ForEach(shownFailedAudios) { audio in
                    failedAudioCard(audio)
                }
            }
        }.frame(maxWidth: .infinity)
    }

    private func failedAudioCard(_ audio: FailedLectureAudio) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "waveform.badge.exclamationmark").foregroundStyle(LV.danger).frame(width: 46, height: 46)
                    .background(LV.elevated, in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 4) {
                    Text("Лекция не обработана").font(.headline)
                    Text(audio.course).font(.caption.weight(.semibold)).foregroundStyle(LV.accent)
                    Text("Исходное аудио сохранено").font(.caption).foregroundStyle(LV.success)
                }
                Spacer()
                Button { model.deleteFailedAudio(audio) } label: {
                    Image(systemName: "trash.fill").foregroundStyle(LV.danger).frame(width: 42, height: 42)
                }.accessibilityLabel("Удалить исходную запись")
            }
            if !audio.errorMessage.isEmpty {
                Text(audio.errorMessage).font(.caption).foregroundStyle(LV.muted).lineLimit(2)
            }
            HStack(spacing: 9) {
                Button { model.retryProcessing(audio) } label: {
                    Label("Повторить", systemImage: "arrow.clockwise")
                }.buttonStyle(.bordered).tint(LV.accent)
                exportMenu(audio)
            }
        }
        .padding(15).background(LV.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(LV.line))
    }

    @ViewBuilder private func exportMenu(_ audio: FailedLectureAudio) -> some View {
        let files = audio.urls
        if files.count == 1, let file = files.first {
            ShareLink(item: file) { Label("Сохранить аудио", systemImage: "square.and.arrow.up") }
                .buttonStyle(.borderedProminent).tint(LV.accent)
        } else if !files.isEmpty {
            Menu {
                ForEach(Array(files.enumerated()), id: \.offset) { index, file in
                    ShareLink(item: file) { Text("Сохранить часть \(index + 1)") }
                }
            } label: {
                Label("Сохранить аудио", systemImage: "square.and.arrow.up")
            }
            .buttonStyle(.borderedProminent).tint(LV.accent)
        }
    }

    private var bottomBar: some View {
        HStack {
            tabButton("Запись", icon: "mic.fill", selected: page == .record) { page = .record }
            tabButton("Лекции", icon: "books.vertical.fill", selected: page == .library) { page = .library }
            tabButton("Настройки", icon: "gearshape.fill", selected: false) { showingSettings = true }
        }
        .padding(.horizontal, 10).padding(.top, 8).padding(.bottom, 4)
        .background(.ultraThinMaterial)
    }

    private func tabButton(_ title: String, icon: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 17, weight: .semibold))
                Text(title).font(.caption2.weight(.semibold))
            }.frame(maxWidth: .infinity).foregroundStyle(selected ? LV.accent : LV.muted)
        }
    }
}

private struct LectureReaderView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let note: LectureNote
    @State private var contents = ""
    @State private var errorMessage: String?
    @State private var confirmingDelete = false
    @State private var quiz: [QuizQuestion] = []
    @State private var selectedAnswers: [Int: Int] = [:]
    @State private var quizError: String?
    @State private var isGeneratingQuiz = false
    @State private var quizResult: String?
    @StateObject private var player = LectureAudioPlayer()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if !model.audioURLs(for: note).isEmpty {
                        HStack {
                            Button { player.toggle(model.audioURLs(for: note)) } label: { Label(player.isPlaying ? "Пауза" : "Слушать аудио", systemImage: player.isPlaying ? "pause.fill" : "play.fill") }
                            .buttonStyle(.borderedProminent).tint(LV.accent)
                            ShareLink(item: model.audioURLs(for: note).first!) { Label("Сохранить аудио", systemImage: "square.and.arrow.up") }
                        }
                    }
                    TimestampedNoteView(markdown: visibleContents) { seconds, part in
                        player.seek(seconds: seconds, partNumber: part, files: model.audioURLs(for: note))
                    }
                    Divider().overlay(LV.line)
                    Button { generateQuiz() } label: { Label(isGeneratingQuiz ? "Создаём тест…" : "Создать мини‑тест · 10 вопросов", systemImage: "checklist") }
                        .disabled(isGeneratingQuiz).buttonStyle(.borderedProminent).tint(LV.accent)
                    ForEach(Array(quiz.enumerated()), id: \.element.id) { index, item in
                        VStack(alignment: .leading, spacing: 9) {
                            Text("\(index + 1). \(item.question)").font(.headline)
                            ForEach(item.options.indices, id: \.self) { option in
                                Button { selectedAnswers[index] = option } label: {
                                    HStack { Image(systemName: selectedAnswers[index] == option ? "largecircle.fill.circle" : "circle"); Text(item.options[option]); Spacer() }
                                }.foregroundStyle(LV.text).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }.padding(15).background(LV.surface, in: RoundedRectangle(cornerRadius: 18))
                    }
                    if !quiz.isEmpty {
                        Button("Проверить ответы") {
                            let correct = quiz.enumerated().filter { selectedAnswers[$0.offset] == $0.element.correctIndex }.count
                            quizResult = "Правильных ответов: \(correct) из \(quiz.count)"
                        }.buttonStyle(.borderedProminent).tint(LV.success)
                        if let quizResult { Text(quizResult).font(.headline).foregroundStyle(LV.success) }
                    }
                }.padding(20)
            }
            .background(LV.background.ignoresSafeArea())
            .foregroundStyle(LV.text)
            .navigationTitle(note.title)
            .lvInlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() }.foregroundStyle(LV.accent) }
                ToolbarItem(placement: .primaryAction) {
                    Button { confirmingDelete = true } label: { Image(systemName: "trash.fill") }.tint(LV.danger)
                }
            }
            .onAppear {
                do { contents = try model.readNote(note) }
                catch { errorMessage = error.localizedDescription }
            }
            .onDisappear { player.stop() }
            .alert("Удалить лекцию?", isPresented: $confirmingDelete) {
                Button("Удалить", role: .destructive) {
                    do { try model.deleteNote(note); dismiss() }
                    catch { errorMessage = error.localizedDescription }
                }
                Button("Отмена", role: .cancel) {}
            } message: { Text("Файл будет удалён из Obsidian. Это действие нельзя отменить.") }
            .alert("Не удалось открыть конспект", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) { Button("Понятно", role: .cancel) {} } message: { Text(errorMessage ?? "Неизвестная ошибка") }
            .alert("Не удалось создать тест", isPresented: Binding(
                get: { quizError != nil },
                set: { if !$0 { quizError = nil } }
            )) { Button("Понятно", role: .cancel) {} } message: { Text(quizError ?? "Неизвестная ошибка") }
        }.preferredColorScheme(.dark)
    }

    private var visibleContents: String { stripFrontMatter(contents) }

    private func stripFrontMatter(_ markdown: String) -> String {
        guard markdown.hasPrefix("---"), let end = markdown.range(of: "\n---", range: markdown.index(markdown.startIndex, offsetBy: 3)..<markdown.endIndex) else { return markdown }
        return String(markdown[end.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func generateQuiz() {
        isGeneratingQuiz = true; quizError = nil
        Task {
            do { quiz = try await APIClient.generateMiniTest(markdown: contents); selectedAnswers = [:]; quizResult = nil }
            catch { quizError = error.localizedDescription }
            isGeneratingQuiz = false
        }
    }
}

private struct TimestampedNoteView: View {
    let markdown: String
    let onTimestampTap: (Int, Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(TimestampedNoteLine.parse(markdown)) { line in
                if let seconds = line.seconds {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Button {
                            onTimestampTap(seconds, line.partNumber)
                        } label: {
                            Text(line.label).font(.system(.caption, design: .monospaced).weight(.bold))
                        }
                        .buttonStyle(.bordered)
                        .tint(LV.accent)
                        .accessibilityLabel("Перейти к \(line.label) в аудио")
                        Text(line.text).font(.system(size: 16)).lineSpacing(6).textSelection(.enabled)
                    }
                } else {
                    Text((try? AttributedString(markdown: line.text)) ?? AttributedString(line.text))
                        .font(.system(size: 16)).lineSpacing(6).textSelection(.enabled)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct TimestampedNoteLine: Identifiable {
    let id: Int
    let text: String
    let label: String
    let seconds: Int?
    let partNumber: Int?

    static func parse(_ markdown: String) -> [TimestampedNoteLine] {
        var currentPart: Int?
        return markdown.split(separator: "\n", omittingEmptySubsequences: false).enumerated().map { index, value in
            let line = String(value)
            if line.hasPrefix("### Часть "), let number = Int(line.dropFirst("### Часть ".count).trimmingCharacters(in: .whitespaces)) {
                currentPart = number
            }
            guard line.first == "[", let close = line.firstIndex(of: "]") else {
                return TimestampedNoteLine(id: index, text: line, label: "", seconds: nil, partNumber: currentPart)
            }
            let label = String(line[...close])
            let clock = label.dropFirst().dropLast().split(whereSeparator: { $0 == "–" || $0 == "-" }).first.map(String.init) ?? ""
            guard let seconds = seconds(from: clock) else {
                return TimestampedNoteLine(id: index, text: line, label: "", seconds: nil, partNumber: currentPart)
            }
            let after = line.index(after: close)
            return TimestampedNoteLine(
                id: index,
                text: String(line[after...]).trimmingCharacters(in: .whitespaces),
                label: label,
                seconds: seconds,
                partNumber: currentPart
            )
        }
    }

    private static func seconds(from value: String) -> Int? {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 || parts.count == 3 else { return nil }
        let (hours, minutes, seconds) = parts.count == 3 ? (parts[0], parts[1], parts[2]) : (0, parts[0], parts[1])
        guard minutes < 60, seconds < 60 else { return nil }
        return hours * 3_600 + minutes * 60 + seconds
    }
}

@MainActor
private final class LectureAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var isPlaying = false
    private var player: AVAudioPlayer?
    private var files: [URL] = []
    private var durations: [TimeInterval] = []
    private var currentPart = 0

    func toggle(_ sourceFiles: [URL]) {
        configure(sourceFiles)
        guard !files.isEmpty else { return }
        if let player {
            if player.isPlaying { player.pause(); isPlaying = false }
            else { player.play(); isPlaying = true }
        } else {
            open(part: currentPart, at: 0, shouldPlay: true)
        }
    }

    func seek(seconds: Int, partNumber: Int?, files sourceFiles: [URL]) {
        configure(sourceFiles)
        guard !files.isEmpty else { return }
        if let partNumber, files.indices.contains(partNumber - 1) {
            open(part: partNumber - 1, at: TimeInterval(seconds), shouldPlay: true)
            return
        }
        var remaining = TimeInterval(seconds)
        for index in files.indices {
            let duration = durations[index]
            if index == files.indices.last || duration <= 0 || remaining <= duration {
                open(part: index, at: remaining, shouldPlay: true)
                return
            }
            remaining -= duration
        }
    }

    func stop() { player?.stop(); player = nil; isPlaying = false }

    func audioPlayerDidFinishPlaying(_ finishedPlayer: AVAudioPlayer, successfully flag: Bool) {
        guard finishedPlayer === player else { return }
        if currentPart + 1 < files.count { open(part: currentPart + 1, at: 0, shouldPlay: true) }
        else { isPlaying = false }
    }

    private func configure(_ sourceFiles: [URL]) {
        guard sourceFiles != files else { return }
        stop()
        files = sourceFiles
        durations = sourceFiles.map { (try? AVAudioPlayer(contentsOf: $0).duration) ?? 0 }
        currentPart = 0
    }

    private func open(part: Int, at offset: TimeInterval, shouldPlay: Bool) {
        guard files.indices.contains(part), let next = try? AVAudioPlayer(contentsOf: files[part]) else { stop(); return }
        player?.stop()
        currentPart = part
        next.delegate = self
        next.currentTime = min(max(0, offset), next.duration)
        player = next
        if shouldPlay { next.play(); isPlaying = true } else { isPlaying = false }
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var consent = false
    @State private var showingPrivacy = false
    @State private var gatewayStatus: String?
    @State private var isCheckingGateway = false
    let showVaultPicker: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    settingsCard("Облачный режим", subtitle: model.cloudConsent ? "Сервер ИИ подключён" : "Требуется подтверждение") {
                        Toggle("Разрешаю отправку аудио и текста на защищённый сервер ИИ", isOn: $consent).tint(LV.accent)
                        Button {
                            isCheckingGateway = true
                            gatewayStatus = "Проверяем сервер ИИ…"
                            Task {
                                do { gatewayStatus = try await APIClient.checkHealth() }
                                catch { gatewayStatus = "Сервер недоступен: \(error.localizedDescription)" }
                                isCheckingGateway = false
                            }
                        } label: {
                            Label(isCheckingGateway ? "Проверяем…" : "Проверить подключение к ИИ", systemImage: "arrow.clockwise")
                                .frame(maxWidth: .infinity)
                        }
                        .disabled(isCheckingGateway)
                        .frame(height: 46).background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
                        if let gatewayStatus { Text(gatewayStatus).font(.caption).foregroundStyle(gatewayStatus.hasPrefix("Сервер ИИ готов") ? LV.success : LV.muted) }
                        HStack {
                            Button("Отключить облачный ИИ") { model.disableCloudProcessing(); consent = false }.foregroundStyle(LV.danger)
                            Spacer()
                            Button("Как используются данные") { showingPrivacy = true }.foregroundStyle(LV.muted)
                        }.font(.caption.weight(.semibold))
                    }
                    settingsCard("Obsidian", subtitle: model.vaultName == "Не выбран" ? "Vault не выбран" : "Подключено: \(model.vaultName)") {
                        Button("Создать / подключить vault", action: showVaultPicker)
                            .frame(maxWidth: .infinity).frame(height: 50).background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
                        Text("Выберите пустую папку — LectureVault подготовит её для Obsidian и создаст папку лекций.")
                            .font(.caption).foregroundStyle(LV.muted)
                        HStack {
                            Image(systemName: "folder.fill").foregroundStyle(LV.accent)
                            TextField("Папка для лекций", text: $model.notesFolder)
                        }.padding(.horizontal, 14).frame(height: 52).background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
                    }
                    Button("Сохранить") {
                        model.saveSettings(consent: consent)
                        if model.isConfigured { dismiss() }
                    }
                    .font(.headline).frame(maxWidth: .infinity).frame(height: 54)
                    .background(LV.accent, in: RoundedRectangle(cornerRadius: 17)).foregroundStyle(LV.background)
                }.padding(20)
            }
            .background(LV.background.ignoresSafeArea()).foregroundStyle(LV.text)
            .navigationTitle("Настройки").lvInlineNavigationTitle()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() }.foregroundStyle(LV.accent) } }
            .onAppear { consent = model.cloudConsent }
            .alert("Как используются данные", isPresented: $showingPrivacy) {
                Button("Открыть политику") {
                    if let url = APIClient.privacyURL {
                        openURL(url)
                    }
                }
                Button("Понятно", role: .cancel) {}
            } message: {
                Text("Аудио и текст отправляются на защищённый сервер приложения для расшифровки и создания конспекта. Ключи ИИ не хранятся на iPhone или Mac.")
            }
        }.preferredColorScheme(.dark)
    }

    private func settingsCard<Content: View>(_ title: String, subtitle: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 13) {
            Text(title).font(.title3.bold())
            Text(subtitle).font(.caption).foregroundStyle(LV.muted)
            content()
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(LV.surface, in: RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(LV.line))
    }

}

private extension View {
    @ViewBuilder
    func lvInlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }
}
