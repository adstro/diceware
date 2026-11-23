import Foundation
import Shared
import SwiftUI

@main
struct DicewareApp: App {
    @State private var generator = DicewareApp.makeGenerator()

    var body: some Scene {
        WindowGroup { GeneratorView(generator: generator) }
    }

    /// The core Generator over the bundled EFF Large Word List, rolled by SecRandomCopyBytes.
    static func makeGenerator() -> Generator {
        // The list ships inside the app bundle, so a missing or unreadable file is a build error.
        let url = Bundle.main.url(forResource: "eff_large_wordlist", withExtension: "txt")!
        return GeneratorKt.createGenerator(wordListText: try! String(contentsOf: url, encoding: .utf8))
    }
}
