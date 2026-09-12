# Платформы LectureVault

## Android

Основная готовая версия находится в модуле `app`. Она записывает или импортирует аудио,
отправляет его на защищённый AI Gateway после явного согласия, сохраняет Markdown в выбранный
vault Obsidian и при недоступности облака использует заранее скачанную русскую Vosk-модель.

Сборка для теста: `gradlew :app:assembleDebug`.

## Windows

Модуль `desktop` — desktop-версия на JVM для Windows. Он записывает 16 kHz mono WAV,
автоматически делит длинную запись на фрагменты ниже лимита загрузки, импортирует аудио,
обращается к AI Gateway и пишет Markdown напрямую в выбранный Obsidian vault.

Сборка portable-архива: `gradlew :desktop:portableZip`.

## iPhone и Mac

Папка `ios` содержит общий SwiftUI-код и XcodeGen-спецификацию для iPhone и macOS.
Обе Apple-версии записывают AAC/M4A, импортируют аудио из Files, используют защищённый
LectureVault gateway и сохраняют Markdown в выбранный Obsidian vault. Личные ключи ИИ не
хранятся на устройстве.

На Mac выполните `cd ios && xcodegen generate`, откройте `LectureVault.xcodeproj`, выберите
свою Apple Team и соберите нужную схему. Для TestFlight и App Store требуется аккаунт Apple
Developer и собственная подпись. GitHub Actions дополнительно собирает исходники Apple без
подписи для защиты от регрессий, но не создаёт распространяемое приложение.

## Общая обработка

1. Клиент после согласия отправляет аудио в AI Gateway LectureVault.
2. Gateway использует Groq `whisper-large-v3-turbo` для русской расшифровки.
3. Gateway создаёт структуру Markdown и мини-тест через Gemini, затем OpenRouter как резерв.
4. Клиент сохраняет результат в папку предмета внутри vault Obsidian.

Если текстовый ИИ недоступен, Android может перейти на локальную Vosk-модель и создать
упрощённый конспект. iPhone, Mac и Windows пока требуют доступный gateway для облачной обработки.
