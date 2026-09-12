# Подготовка Google Play для LectureVault 1.5.7

## Подтверждено локально

- Android `versionCode = 11`, `versionName = 1.5.7`, `targetSdk = 36`, `minSdk = 26`.
- Debug unit-тесты и release AAB успешно собираются.
- Release AAB проходит R8 и release lint, но пока не подписан upload key.
- В приложении нет личных ключей Groq, Gemini или OpenRouter. Оно отправляет данные только после явного согласия на защищённый сервер LectureVault.
- Политика обработки данных обновлена в `PRIVACY.md`.

## Перед созданием подписанного AAB

1. Создайте upload key и сохраните его в безопасном месте вне репозитория:

```powershell
keytool -genkeypair -v -keystore keys/lecturevault-upload.jks -alias lecturevault-upload -keyalg RSA -keysize 4096 -validity 10000
```

2. Скопируйте `release-signing.properties.example` в `release-signing.properties`.
3. Впишите путь к ключу, alias и оба пароля. Этот файл и `.jks` уже исключены из Git.
4. Соберите подписанный App Bundle:

```powershell
.\gradlew.bat :app:bundleRelease
```

5. Загрузите `app/build/outputs/bundle/release/app-release.aab` в закрытый тест Play Console и включите Play App Signing.

## До отправки на модерацию

- Проверьте release AAB на физическом устройстве: запись, закрытый экран, импорт аудио, Obsidian, удаление лекции, мини-тест, отсутствие сети и локальную модель.
- Укажите в Play Console публичную политику: `https://lecturevault-ai-gateway.aleksandrsimunin828.workers.dev/privacy`. Добавьте контакт издателя: его нельзя выдумывать, поэтому он остаётся за владельцем приложения.
- Заполните Data safety по фактической схеме: аудио и текст отправляются в Cloudflare Worker, затем в Groq и Gemini/OpenRouter; сервер хранит только IP-счётчик дневного лимита, а не содержание лекции.
- Подготовьте описание, иконку, скриншоты и список тестировщиков в Play Console.
