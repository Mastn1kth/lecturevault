# LectureVault for iPhone and Mac

The iOS source is ready for generation with XcodeGen.

On a Mac:

1. Install Xcode and XcodeGen.
2. Run `cd ios && xcodegen generate`.
3. Open `LectureVault.xcodeproj`.
4. Select your Apple Team under Signing & Capabilities for both targets: `LectureVault` and `LectureVaultMac`.
5. Build `LectureVault` to an iPhone or archive it for TestFlight. Select the `LectureVaultMac` scheme to build the macOS app.

The app records AAC/M4A audio, imports audio from Files, sends it to the protected
LectureVault gateway, and writes Markdown into a user-selected Obsidian vault folder.
Provider keys stay on the gateway and are never stored on the iPhone or Mac. The gateway
uses Gemini first and OpenRouter as a fallback when configured.
Saved lectures can be searched, read, deleted, played back and shared as audio from inside the app.
The reader can create a 10-question mini-test with three answer choices each.
The Mac target shares the same SwiftUI interface and vault format.
