# LectureVault for iPhone

The iOS source is ready for generation with XcodeGen.

On a Mac:

1. Install Xcode and XcodeGen.
2. Run `cd ios && xcodegen generate`.
3. Open `LectureVault.xcodeproj`.
4. Select your Apple Team under Signing & Capabilities.
5. Build to an iPhone, or archive for TestFlight.

The app records AAC/M4A audio, imports audio from Files, stores API keys in Keychain,
runs Groq then Gemini, and writes Markdown into a user-selected Obsidian vault folder.
Saved lectures can be searched, read as formatted Markdown, and deleted from inside the app.
