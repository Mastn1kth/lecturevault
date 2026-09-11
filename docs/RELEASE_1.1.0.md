# LectureVault 1.1.0 — результаты сборки

## Готовые артефакты

- `LectureVault-Android-1.1.0.apk` — устанавливаемая debug-сборка Android для личной проверки.
- `LectureVault-Android-1.1.0-unsigned.aab` — release App Bundle без upload-подписи; напрямую публиковать его нельзя.
- `LectureVault-Windows-1.1.0.zip` — автономная Windows app-image со встроенной Java runtime.
- `LectureVault-macOS-portable-1.1.0.zip` — переносимый JAR и `.command`; требует Java 17. Нативный `.app`/`.dmg` собирается и подписывается только на macOS.
- `LectureVault-iPhone-source-1.1.0.zip` — SwiftUI/XcodeGen-исходники. Для `.ipa` нужен Mac, Xcode, Apple Developer Team и подпись.

## Выполненные проверки

- Android: `assembleDebug`, `bundleRelease`, unit tests для debug/release и `lintRelease` — успешно.
- Desktop: compile, tests, distribution ZIP — успешно.
- Windows app-image: запуск с генерацией восьми PNG-состояний — успешно.
- Секрет, присланный пользователем в чат, в исходники и артефакты не добавлен.

## Что ещё нельзя честно считать готовым

- Android не прогнан на реальном телефоне после редизайна: в момент проверки ADB-устройств не было.
- iOS не скомпилирован: Apple SDK и подпись недоступны на Windows.
- macOS native package не создан: `jpackage` создаёт `.app` только на macOS.
- Живой запрос к пользовательским Groq/Gemini не выполнялся: ключ Groq на машине отсутствует, а ключи приложения намеренно недоступны извне. В приложении есть кнопка «Проверить ключи».
- Для Google Play ещё нужны upload key, Play App Signing, публичный URL политики конфиденциальности, Data safety, описание, скриншоты и доступ к Play Console.
