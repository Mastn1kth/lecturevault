import SwiftUI

@main
struct LectureVaultApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { ContentView().environmentObject(model) } }
}
