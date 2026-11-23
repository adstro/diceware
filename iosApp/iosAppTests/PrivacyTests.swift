import SwiftUI
import Testing
import UIKit
@testable import iosApp

@MainActor
struct PrivacyTests {
    @Test func copyPutsThePassphraseOnThePasteboard() {
        let pasteboard = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: pasteboard.name) }

        PassphraseClipboard.copy("abacus zoom kettle", to: pasteboard)

        #expect(pasteboard.string == "abacus zoom kettle")
    }

    @Test func copiedPassphraseIsLocalOnlyAndExpiresAfter60Seconds() {
        let now = Date(timeIntervalSince1970: 1_000)
        let options = PassphraseClipboard.options(now: now)

        #expect(options[.localOnly] as? Bool == true)
        #expect(options[.expirationDate] as? Date == Date(timeIntervalSince1970: 1_060))
    }

    @Test func copiedPassphraseIsGoneOnceExpired() {
        let pasteboard = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: pasteboard.name) }

        PassphraseClipboard.copy("abacus zoom kettle", to: pasteboard, now: Date().addingTimeInterval(-61))

        #expect(pasteboard.string == nil)
    }

    @Test func privacyCoverShowsWheneverTheSceneIsNotActive() {
        #expect(PrivacyCover.isShown(in: .background))
        #expect(PrivacyCover.isShown(in: .inactive))
        #expect(!PrivacyCover.isShown(in: .active))
    }
}
