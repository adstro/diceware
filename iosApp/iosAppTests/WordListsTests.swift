import Foundation
import Shared
import Testing
@testable import iosApp

@MainActor
struct WordListsTests {
    /// Each test gets its own settings file, so saved settings never leak between tests or into the app.
    private let generator = DicewareApp.makeGenerator(
        settingsPath: FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).preferences_pb").path
    )

    @Test func choosingEffShort1RerollsWithItsWordsAndFourDicePerWord() async throws {
        _ = try #require(await ready())

        generator.setWordList(wordList: .short1)

        let ready = try #require(await ready { $0.strengthBits < 70 })
        let words = ready.passphrase.split(separator: " ").map(String.init)
        #expect(words.count == 6)
        #expect(words.allSatisfy(try wordsIn("eff_short_wordlist_1.txt").contains))
        // 6 Words × 4 dice × log₂6 ≈ 62.04 bits.
        #expect(abs(ready.strengthBits - 62.04) < 0.01)
    }

    @Test func wordListsShowEachEffWordListWithItsWordCountAndStrengthPerWord() {
        let rows = generator.wordLists.value.map { "\(WordListsView.name(of: $0.wordList)): \(WordListsView.details(of: $0))" }

        #expect(rows == [
            "EFF Large: 7,776 Words · 12.9 bits per Word",
            "EFF Short #1: 1,296 Words · 10.3 bits per Word",
            "EFF Short #2: 1,296 Words · 10.3 bits per Word",
        ])
    }

    /// Waits out the first read of saved settings, then returns the first Ready state matching `until`.
    private func ready(until: (GeneratorStateReady) -> Bool = { _ in true }) async -> GeneratorStateReady? {
        for await state in generator.state {
            switch onEnum(of: state) {
            case .loading: continue
            case .ready(let ready): if until(ready) { return ready }
            case .randomnessUnavailable: return nil
            }
        }
        return nil
    }

    private func wordsIn(_ fileName: String) throws -> Set<String> {
        let url = try #require(Bundle.main.url(forResource: fileName, withExtension: nil))
        let lines = try String(contentsOf: url, encoding: .utf8).split(separator: "\n")
        return Set(lines.compactMap { $0.split(separator: "\t").last.map(String.init) })
    }
}
