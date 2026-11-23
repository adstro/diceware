import Foundation
import Shared
import SwiftUI
import Testing
@testable import iosApp

@MainActor
struct DiceEntryTests {
    /// Each test gets its own settings file, so saved settings never leak between tests or into the app.
    private let generator = DicewareApp.makeGenerator(
        settingsPath: FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).preferences_pb").path
    )

    @Test func diceModeBuildsThePassphraseFromEnteredDiceOneWordPerRoll() async throws {
        for await state in generator.state where !(state is GeneratorStateLoading) { break }

        generator.setMode(mode: .dice)
        #expect(generator.state.value is GeneratorStateAwaitingDice)
        // EFF Large: 5 dice per Roll. 11111 is "abacus", 66666 is "zoom".
        #expect(DiceEntryView.prompt(for: generator.diceEntry.value) == "Word 1 of 6: roll 5 dice")
        for _ in 0..<5 { generator.enterDie(face: 1) }
        for _ in 0..<5 { generator.enterDie(face: 6) }
        #expect(generator.diceEntry.value.words == ["abacus", "zoom"])
        for _ in 0..<20 { generator.enterDie(face: 1) }

        let ready = try #require(generator.state.value as? GeneratorStateReady)
        #expect(ready.passphrase == "abacus zoom abacus abacus abacus abacus")
    }

    @Test func dieButtonsAreLabelledForVoiceOver() {
        #expect((1...6).map(DiceEntryView.label(face:)) == (1...6).map { "Die face \($0)" })
    }

    @Test func theDiceEntrySheetIsCoveredWhenTheSceneIsNotActive() {
        // The root view's cover does not reach a sheet, so the sheet carries its own.
        let sheet = GeneratorView.diceEntrySheet(generator: generator)

        #expect(type(of: sheet.modifier) == PrivacyCover.self)
        #expect(PrivacyCover.isShown(in: .inactive))
        #expect(PrivacyCover.isShown(in: .background))
    }
}
