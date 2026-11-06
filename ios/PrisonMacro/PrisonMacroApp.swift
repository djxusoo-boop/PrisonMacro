import SwiftUI

@main
struct PrisonMacroApp: App {
    @StateObject private var cloud = CloudStore()

    var body: some Scene {
        WindowGroup {
            ContentView(cloud: cloud)
        }
    }
}
