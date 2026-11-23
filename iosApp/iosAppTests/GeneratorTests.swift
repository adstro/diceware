import Foundation
import Shared
import Testing
@testable import iosApp

@MainActor
struct GeneratorTests {
    @Test func opensWithASixWordPassphraseFromTheEffLargeWordList() throws {
        let words = try #require(passphrase(of: DicewareApp.makeGenerator())).split(separator: " ").map(String.init)

        #expect(words.count == 6)
        #expect(words.allSatisfy(try effLargeWords().contains))
    }

    @Test func regenerateRollsAFreshPassphrase() throws {
        let generator = DicewareApp.makeGenerator()
        let first = try #require(passphrase(of: generator))

        generator.regenerate()

        // Same 6 Words twice in a row: 1 in 7776⁶ (~2⁻⁷⁷).
        #expect(passphrase(of: generator) != first)
    }

    private func passphrase(of generator: Generator) -> String? {
        if case .ready(let ready) = onEnum(of: generator.state.value) { return ready.passphrase }
        return nil
    }

    private func effLargeWords() throws -> Set<String> {
        let url = try #require(Bundle.main.url(forResource: "eff_large_wordlist", withExtension: "txt"))
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        return Set(lines.compactMap { $0.split(separator: "\t").last.map(String.init) })
    }
}
