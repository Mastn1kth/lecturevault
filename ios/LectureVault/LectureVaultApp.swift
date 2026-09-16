import SwiftUI

#if os(macOS)
import AppKit

private final class MacApplicationDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}
#endif

@main
struct LectureVaultApp: App {
    @StateObject private var model = AppModel()
    #if os(macOS)
    @NSApplicationDelegateAdaptor(MacApplicationDelegate.self) private var macApplicationDelegate
    #endif
    var body: some Scene { WindowGroup { ContentView().environmentObject(model) } }
}
