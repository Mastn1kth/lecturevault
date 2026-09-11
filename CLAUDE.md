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
- Gemini may answer a structured test request with Markdown fences despite an explicit JSON-only prompt; the client must trim a single outer fence and validate all ten questions, three options, and the correct index before displaying it.
- Do not silently swallow a Groq/Gemini failure before local fallback. Keep a safe, actionable failure reason so a missing local model does not turn a cloud error into an unhelpful generic message.
- Exported lecture audio must be copied to a user-selected Storage Access Framework folder; do not expose internal session paths or move the source files, because deleting a lecture later must not delete the user's exported backup.
- An Apple shared iPhone/macOS target must keep iPhone-only audio-session calls behind `#if os(iOS)` and use `AVCaptureDevice.requestAccess(for: .audio)` on macOS; otherwise Xcode cannot compile the Mac target.
- On Apple platforms, keep lecture-to-audio metadata outside the Markdown vault note; archived audio belongs in Application Support and must be deleted only after the Obsidian note deletion succeeds.
