import Foundation
import Testing
@testable import iosApp

@MainActor
struct AboutTests {
    private let text = AboutView.sections.map { String(localized: $0.body) }.joined(separator: "\n")

    @Test func explainsThatDicewareChoosesWordsByRollsOfDice() {
        #expect(text.contains("words chosen by rolls of dice"))
    }

    @Test func creditsTheEffWordListsUnderCcBy3() {
        #expect(text.contains("EFF Word Lists by the Electronic Frontier Foundation"))
        #expect(text.contains("CC BY 3.0"))
    }

    @Test func statesNoHistoryNoLoggingNoAnalyticsAndTheOnlyNetworkUse() {
        #expect(text.contains("no history"))
        #expect(text.contains("no logs"))
        #expect(text.contains("no analytics"))
        #expect(text.contains("The only time it uses the network is when you ask it to check eff.org"))
    }
}
