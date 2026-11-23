import Foundation
import Shared
import SwiftUI

@main
struct DicewareApp: App {
    @State private var generator = DicewareApp.makeGenerator()

    var body: some Scene {
        WindowGroup { GeneratorView(generator: generator) }
    }

    /// The core Generator over the bundled EFF Large Word List, rolled by SecRandomCopyBytes,
    /// with settings saved at `settingsPath`.
    static func makeGenerator(settingsPath: String = settingsPath) -> Generator {
        // The list ships inside the app bundle, so a missing or unreadable file is a build error.
        let url = Bundle.main.url(forResource: "eff_large_wordlist", withExtension: "txt")!
        return GeneratorKt.createGenerator(
            wordListText: try! String(contentsOf: url, encoding: .utf8),
            settingsPath: settingsPath
        )
    }

    /// Application Support, not Documents: settings are app-private and never shown in Files.
    static var settingsPath: String {
        // The app's own container always has Application Support; failing to create it means a broken install.
        let dir = try! FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        return dir.appendingPathComponent("diceware.preferences_pb").path
    }
}
