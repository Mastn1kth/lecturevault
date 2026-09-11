# LectureVault for iPhone and Mac

The iOS source is ready for generation with XcodeGen.

On a Mac:

1. Install Xcode and XcodeGen.
2. Run `cd ios && xcodegen generate`.
3. Open `LectureVault.xcodeproj`.
4. Select your Apple Team under Signing & Capabilities for both targets: `LectureVault` and `LectureVaultMac`.
5. Build `LectureVault` to an iPhone or archive it for TestFlight. Select the `LectureVaultMac` scheme to build the macOS app.

The app records AAC/M4A audio, imports audio from Files, stores API keys in Keychain,
runs Groq then Gemini, and writes Markdown into a user-selected Obsidian vault folder.
Saved lectures can be searched, read, deleted, played back and shared as audio from inside the app.
The reader can create a Gemini mini-test with exactly 10 questions and three answer choices each.
The Mac target shares the same SwiftUI interface and vault format.
