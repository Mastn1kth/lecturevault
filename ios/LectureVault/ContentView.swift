import SwiftUI
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
            if case let .success(urls) = $0, let url = urls.first { model.selectVault(url) }
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
                TextField("Предмет", text: $model.course).textInputAutocapitalization(.sentences)
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
            if shownNotes.isEmpty {
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
            }
        }.frame(maxWidth: .infinity)
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

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(renderedContents)
                    .font(.system(size: 16))
                    .lineSpacing(6)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            .background(LV.background.ignoresSafeArea())
            .foregroundStyle(LV.text)
            .navigationTitle(note.title)
            .navigationBarTitleDisplayMode(.inline)
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
        }.preferredColorScheme(.dark)
    }

    private var renderedContents: AttributedString {
        let visible = stripFrontMatter(contents)
        return (try? AttributedString(markdown: visible)) ?? AttributedString(visible)
    }

    private func stripFrontMatter(_ markdown: String) -> String {
        guard markdown.hasPrefix("---"), let end = markdown.range(of: "\n---", range: markdown.index(markdown.startIndex, offsetBy: 3)..<markdown.endIndex) else { return markdown }
        return String(markdown[end.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var groq = ""
    @State private var gemini = ""
    @State private var consent = false
    @State private var showingPrivacy = false
    let showVaultPicker: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    settingsCard("Облачный режим", subtitle: model.hasCloudKeys ? "Ключи сохранены" : "Ключи не добавлены") {
                        secureInput("Groq API key", text: $groq)
                        secureInput("Gemini API key", text: $gemini)
                        Toggle("Разрешаю отправку аудио в Groq и текста в Gemini", isOn: $consent).tint(LV.accent)
                        HStack {
                            Button("Удалить ключи") { model.deleteCloudKeys(); consent = false }.foregroundStyle(LV.danger)
                            Spacer()
                            Button("Как используются данные") { showingPrivacy = true }.foregroundStyle(LV.muted)
                        }.font(.caption.weight(.semibold))
                    }
                    settingsCard("Obsidian", subtitle: model.vaultName == "Не выбран" ? "Vault не выбран" : "Подключено: \(model.vaultName)") {
                        Button("Выбрать папку vault", action: showVaultPicker)
                            .frame(maxWidth: .infinity).frame(height: 50).background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
                        HStack {
                            Image(systemName: "folder.fill").foregroundStyle(LV.accent)
                            TextField("Папка для лекций", text: $model.notesFolder)
                        }.padding(.horizontal, 14).frame(height: 52).background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
                    }
                    Button("Сохранить") {
                        model.saveSettings(groq: groq, gemini: gemini, consent: consent)
                        if model.isConfigured { dismiss() }
                    }
                    .font(.headline).frame(maxWidth: .infinity).frame(height: 54)
                    .background(LV.accent, in: RoundedRectangle(cornerRadius: 17)).foregroundStyle(LV.background)
                }.padding(20)
            }
            .background(LV.background.ignoresSafeArea()).foregroundStyle(LV.text)
            .navigationTitle("Настройки").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() }.foregroundStyle(LV.accent) } }
            .onAppear { consent = model.cloudConsent }
            .alert("Как используются данные", isPresented: $showingPrivacy) {
                Button("Понятно", role: .cancel) {}
            } message: {
                Text("Аудио отправляется в Groq для расшифровки, а текст — в Gemini для создания конспекта. Ключи хранятся в Keychain этого устройства.")
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

    private func secureInput(_ title: String, text: Binding<String>) -> some View {
        SecureField(title, text: text).padding(.horizontal, 14).frame(height: 52)
            .background(LV.elevated, in: RoundedRectangle(cornerRadius: 15))
    }
}
