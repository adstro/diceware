import Foundation
import Shared
import Testing
@testable import iosApp

@MainActor
struct GeneratorTests {
    /// Each test gets its own settings file, so saved settings never leak between tests or into the app.
    private let generator = DicewareApp.makeGenerator(
        settingsPath: FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).preferences_pb").path
    )

    @Test func opensWithASixWordPassphraseFromTheEffLargeWordList() async throws {
        let words = try #require(await ready()).passphrase.split(separator: " ").map(String.init)

        #expect(words.count == 6)
        #expect(words.allSatisfy(try effLargeWords().contains))
    }

    @Test func regenerateRollsAFreshPassphrase() async throws {
        let first = try #require(await ready()).passphrase

        generator.regenerate()

        // Same 6 Words twice in a row: 1 in 7776⁶ (~2⁻⁷⁷).
        #expect(try #require(await ready()).passphrase != first)
    }

    @Test func fewerWordsLowerTheStrengthBelowTheWarning() async throws {
        #expect(try #require(await ready()).isWeak == false)

        generator.setWordCount(count: 4)

        let ready = try #require(await ready())
        #expect(ready.passphrase.split(separator: " ").count == 4)
        // 4 Words × 5 dice × log₂6 ≈ 51.7 bits.
        #expect(abs(ready.strengthBits - 51.70) < 0.01)
        #expect(ready.isWeak)
    }

    @Test func separatorAndTogglesShapeThePassphrase() async throws {
        _ = try #require(await ready())

        generator.setSeparator(separator: .hyphen)
        generator.setCapitalise(on: true)
        generator.setAppendDigit(on: true)
        generator.setAppendSymbol(on: true)

        let passphrase = try #require(await ready()).passphrase
        #expect(passphrase.wholeMatch(of: /([A-Z][a-z-]*-){5}[A-Z][a-z-]*[0-9][!@#$%&*\-_+=?]/) != nil)
    }

    /// Waits out the first read of saved settings, then returns the Ready state (nil if randomness failed).
    private func ready() async -> GeneratorStateReady? {
        for await state in generator.state {
            switch onEnum(of: state) {
            case .loading: continue
            case .ready(let ready): return ready
            case .randomnessUnavailable: return nil
            }
        }
        return nil
    }

    private func effLargeWords() throws -> Set<String> {
        let url = try #require(Bundle.main.url(forResource: "eff_large_wordlist", withExtension: "txt"))
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        return Set(lines.compactMap { $0.split(separator: "\t").last.map(String.init) })
    }
}
