# LectureVault platforms

## Android

The production Android module remains in `app`. Build with `gradlew :app:assembleDebug`.

## Windows and macOS desktop

The `desktop` module is a JVM desktop application. It records 16 kHz mono WAV and splits long
recordings below the Groq upload limit, imports common audio formats, calls Groq then Gemini, and
writes Markdown directly into a selected Obsidian vault.

Build the portable package with `gradlew :desktop:portableZip`. Build a native installer with
`jpackage` on the target operating system. Native macOS `.app`/`.dmg` packaging must run on macOS.

## iPhone

The `ios` directory contains a SwiftUI application definition and XcodeGen project spec. It uses
AVAudioRecorder, rotates recordings every 25 minutes, imports audio through Files, stores API keys
in Keychain, and persists security-scoped access to a selected vault directory.

Generate and sign it on macOS with Xcode. Apple does not provide the iOS SDK or code signing tools
for Windows.

## Processing order

1. Groq speech-to-text.
2. Gemini cleanup, topic detection, and structured Markdown.
3. Direct write to the selected Obsidian vault.

The Android version retains its installed Vosk offline fallback. The first desktop/iOS packages are
cloud-first; shared offline Whisper support is a separate native integration and is not represented
as complete here.
