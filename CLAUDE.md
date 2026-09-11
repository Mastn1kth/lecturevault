# Project notes

## Desktop smoke-test process

- Symptom: `:desktop:clean` cannot delete `desktop/build/libs/desktop.jar`.
- Cause: a manually launched LectureVault Swing smoke-test is still using the fat JAR.
- Prevention: after a smoke test, stop only processes whose executable or command line resolves inside
  `desktop/build` before running `:desktop:clean`; never terminate unrelated Java processes.

## Cross-platform release lessons

- In Swing subclasses, do not declare a Kotlin Boolean property named `selected` on `JButton`; it collides with the inherited JVM `setSelected(boolean)`. Use a distinct name such as `navSelected`.
- Gradle `:desktop:run` uses the desktop module as its working directory. Preview output arguments must be module-relative or absolute to avoid accidental `desktop/desktop/...` paths.
- Cloud readiness and offline readiness are independent. A configured cloud path must not be blocked by a missing local model; attempt cloud first and require Vosk only for fallback.
- Sending lecture data to Groq/Gemini requires persisted, explicit consent on every supported platform. A saved key alone must never enable uploads.
- A custom Swing content pane does not modernize the native frame or `JFileChooser`. Use FlatLaf window decorations and its `SystemFileChooser`; otherwise Windows falls back to a white Java title bar and obsolete Metal-style dialogs.
- Variable fonts loaded with Java 17 can render cramped at Windows scaling. Prefer the platform UI font for desktop body text and verify at the user's actual display scale.
- A lecture deletion spans two stores: delete the Obsidian note first, then local session metadata/audio. If vault deletion fails, keep the local record so the user can retry instead of orphaning an invisible note.
- Mobile history entries must retain a safe vault-relative path, not only a display title; duplicate titles cannot otherwise be opened or deleted reliably.
- Groq timestamps reset for each uploaded recording segment. Preserve the `### Часть N` marker when rendering the transcript so timestamp taps can seek within the right local audio file.
