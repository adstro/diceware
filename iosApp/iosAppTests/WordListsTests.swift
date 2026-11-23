import Foundation
import Shared
import Testing
@testable import iosApp

@MainActor
struct WordListsTests {
    private static let temporary = FileManager.default.temporaryDirectory
    private let wordListsDirectory = DicewareApp.excludedFromBackup(temporary.appendingPathComponent(UUID().uuidString))
    /// Each test gets its own settings file and Word Lists, so nothing leaks between tests or into the app.
    private let generator: Generator

    init() {
        generator = DicewareApp.makeGenerator(
            settingsPath: Self.temporary.appendingPathComponent("\(UUID().uuidString).preferences_pb").path,
            wordListsPath: wordListsDirectory.path
        )
    }

    @Test func choosingEffShort1RerollsWithItsWordsAndFourDicePerWord() async throws {
        _ = try #require(await ready())

        generator.setWordList(wordList: await loaded { !$0.isEmpty }[1].id)

        let ready = try #require(await ready { $0.strengthBits < 70 })
        let words = ready.passphrase.split(separator: " ").map(String.init)
        #expect(words.count == 6)
        #expect(words.allSatisfy(try wordsIn("eff_short_wordlist_1.txt").contains))
        // 6 Words × 4 dice × log₂6 ≈ 62.04 bits.
        #expect(abs(ready.strengthBits - 62.04) < 0.01)
    }

    @Test func wordListsShowEachEffWordListWithItsWordCountAndStrengthPerWord() async {
        let rows = await loaded { !$0.isEmpty }.map { "\(WordListsView.name(of: $0)): \(WordListsView.details(of: $0))" }

        #expect(rows == [
            "EFF Large: 7,776 Words · 12.9 bits per Word",
            "EFF Short #1: 1,296 Words · 10.3 bits per Word",
            "EFF Short #2: 1,296 Words · 10.3 bits per Word",
        ])
    }

    @Test func aPickedFileIsImportedNamedAfterItAndCanBeRenamedAndDeleted() async throws {
        let file = Self.temporary.appendingPathComponent("Animals.txt")
        try (0..<1296).map { "animal\($0)" }.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8)
        _ = await loaded { !$0.isEmpty }

        await DicewareApp.importFile(at: file, into: generator.library)

        let imported = try #require(await loaded { $0.count == 4 }.last)
        #expect("\(WordListsView.name(of: imported)): \(WordListsView.details(of: imported))" == "Animals: 1,296 Words · 10.3 bits per Word")
        let id = try #require(imported.id as? CustomWordListId)

        generator.library.rename(id: id, name: "Beasts")
        #expect(await loaded { $0.last?.name == "Beasts" }.count == 4)

        generator.library.delete(id: id)
        #expect(await loaded { $0.count == 3 }.count == 3)
    }

    @Test func anInvalidFileIsRejectedNamingTheLine() async throws {
        let file = Self.temporary.appendingPathComponent("bad.txt")
        try (0..<1296).map { $0 == 7 ? "ice cream" : "w\($0)" }.joined(separator: "\n").write(to: file, atomically: true, encoding: .utf8)

        await DicewareApp.importFile(at: file, into: generator.library)

        let failed = try #require(generator.library.importState.value as? ImportStateFailed)
        #expect(WordListsView.message(for: failed.error) == "Not imported: the Word on line 8 contains a space.")
    }

    @Test func anEffUpdateIsSummarisedAsWordsAddedAndRemovedWithASample() {
        let change = EffWordListChange(
            wordList: .short1, wordsAdded: 7, wordsRemoved: 2,
            sampleAdded: ["kiwi", "lime", "fig", "date", "plum"], sampleRemoved: ["acid", "zoom"]
        )

        #expect(WordListsView.summary(of: change) == [
            "EFF Short #1: 7 Words added, 2 removed",
            "Added: kiwi, lime, fig, date, plum, …",
            "Removed: acid, zoom",
        ])
    }

    @Test func anUpdatedEffWordListIsMarked() {
        let info = WordListInfo(id: EffWordList.large.toKotlinEnum(), name: nil, wordCount: 7776, strengthPerWordBits: 12.92, updated: true)

        #expect(WordListsView.details(of: info) == "7,776 Words · 12.9 bits per Word · Updated from eff.org")
    }

    @Test func aFailedEffCheckSaysWhyAndThatNothingChanged() {
        #expect(WordListsView.message(for: EffUpdateErrorRedirected.shared)
            == "eff.org redirected the download to another site, so it was refused. Nothing was changed.")
        #expect(WordListsView.message(for: EffUpdateErrorInvalid(wordList: .short2, error: ImportErrorWordHasWhitespace(line: 8)))
            == "EFF Short #2 from eff.org is not a valid Word List. Not imported: the Word on line 8 contains a space. Nothing was changed.")
    }

    @Test func importingAnEffWordListOrSkippingARollSaysSo() {
        #expect(WordListsView.message(for: ImportErrorAlreadyImportedAsEff(wordList: .large))
            == "Not imported: already imported as EFF Large.")
        #expect(WordListsView.message(for: ImportErrorRollNotFound(roll: "1122"))
            == "Not imported: no line has the Roll 1122.")
    }

    @Test func customWordListsAreExcludedFromBackup() throws {
        let values = try wordListsDirectory.resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
        #expect(try URL(fileURLWithPath: DicewareApp.wordListsPath).resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    /// The first Word Lists state matching `until`.
    private func loaded(until: ([WordListInfo]) -> Bool) async -> [WordListInfo] {
        for await lists in generator.library.wordLists where until(lists) { return lists }
        return []
    }

    /// Waits out the first read of saved settings, then returns the first Ready state matching `until`.
    private func ready(until: (GeneratorStateReady) -> Bool = { _ in true }) async -> GeneratorStateReady? {
        for await state in generator.state {
            switch onEnum(of: state) {
            case .loading: continue
            case .ready(let ready): if until(ready) { return ready }
            case .awaitingDice, .randomnessUnavailable: return nil
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
