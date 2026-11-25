import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Copies a Passphrase so it never syncs over Universal Clipboard and is gone a minute later.
enum PassphraseClipboard {
    static func options(now: Date = .now) -> [UIPasteboard.OptionsKey: Any] {
        [.localOnly: true, .expirationDate: now.addingTimeInterval(60)]
    }

    static func copy(_ passphrase: String, to pasteboard: UIPasteboard = .general, now: Date = .now) {
        pasteboard.setItems([[UTType.utf8PlainText.identifier: passphrase]], options: options(now: now))
    }
}

/// Hides the app behind a plain cover whenever the scene is not active, so the app switcher snapshot never shows a
/// Passphrase.
struct PrivacyCover: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    // Inactive too: the app switcher shows the inactive scene before the background snapshot is taken.
    static func isShown(in phase: ScenePhase) -> Bool { phase != .active }

    func body(content: Content) -> some View {
        content.overlay {
            if Self.isShown(in: scenePhase) {
                ZStack {
                    Color(uiColor: .systemBackground)
                    Text("Diceware").font(.largeTitle)
                }
                .ignoresSafeArea()
            }
        }
    }
}
