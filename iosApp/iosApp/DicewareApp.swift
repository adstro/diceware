import Foundation
// Kotlin classes are not Sendable; the core does its own threading.
@preconcurrency import Shared
import SwiftUI

@main
struct DicewareApp: App {
    @State private var generator = DicewareApp.makeGenerator()
    @State private var showingWordLists = false

    var body: some Scene {
        WindowGroup {
            GeneratorView(generator: generator, showingWordLists: $showingWordLists)
                .modifier(PrivacyCover())
                // "Open in…" from another app: the system hands over a copy in the app's Inbox.
                .onOpenURL { url in
                    showingWordLists = true
                    Task { await Self.importFile(at: url, into: generator.library) }
                }
        }
    }

    /// Imports a file the user picked or opened in the app; the file is untrusted, so the core validates
    /// all of it. Inbox copies from "Open in…" are deleted afterwards.
    static func importFile(at url: URL, into library: WordListLibrary) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer {
            if scoped { url.stopAccessingSecurityScopedResource() }
            if url.deletingLastPathComponent().lastPathComponent == "Inbox" { try? FileManager.default.removeItem(at: url) }
        }
        try? await library.importFile(path: url.path, fileName: url.lastPathComponent)
    }

    /// Only ever called from the user's tap on "Check for updates"; the core reports the outcome as state.
    static func checkForEffUpdates(in library: WordListLibrary) async {
        try? await library.checkForEffUpdates()
    }

    /// The core Generator over the bundled EFF Word Lists and the Custom Word Lists in `wordListsPath`,
    /// rolled by SecRandomCopyBytes, with settings saved at `settingsPath`.
    static func makeGenerator(settingsPath: String = settingsPath, wordListsPath: String = wordListsPath) -> Generator {
        GeneratorKt.createGenerator(
            // @Sendable: the core calls this off the main thread, so it must not inherit main-actor isolation.
            readBundled: { @Sendable wordList in
                // The lists ship inside the app bundle, so a missing or unreadable file is a build error.
                let url = Bundle.main.url(forResource: wordList.fileName, withExtension: nil)!
                return try! String(contentsOf: url, encoding: .utf8)
            },
            settingsPath: settingsPath,
            wordListsPath: wordListsPath
        )
    }

    /// Application Support, not Documents: settings are app-private and never shown in Files.
    static var settingsPath: String {
        applicationSupport.appendingPathComponent("diceware.preferences_pb").path
    }

    /// App-private Custom Word Lists, kept on this device: excluded from iCloud and computer backups.
    static var wordListsPath: String { excludedFromBackup(applicationSupport.appendingPathComponent("wordlists")).path }

    /// Creates `directory` if needed and marks it, and so everything in it, as excluded from backup.
    static func excludedFromBackup(_ directory: URL) -> URL {
        var directory = directory
        // The app's own container is always writable; failing here means a broken install.
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try! directory.setResourceValues(values)
        return directory
    }

    private static var applicationSupport: URL {
        // The app's own container always has Application Support; failing to create it means a broken install.
        try! FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }
}
