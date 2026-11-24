import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Renders the core's Word Lists state: every Word List with its Word count and Strength per Word, the
/// running import, rename and delete for Custom Word Lists, and the user-triggered eff.org update check.
/// Every action is forwarded to the core.
struct WordListsView: View {
    let generator: Generator
    @State private var wordLists: [WordListInfo] = []
    @State private var importState: ImportState = ImportStateIdle.shared
    @State private var picking = false
    @State private var renaming: WordListInfo?
    @State private var newName = ""
    @State private var deleting: WordListInfo?
    @State private var effUpdateState: EffUpdateState = EffUpdateStateIdle.shared

    private var library: WordListLibrary { generator.library }

    var body: some View {
        List {
            if case .importing(let importing) = onEnum(of: importState) {
                HStack {
                    ProgressView("Importing Word List…", value: importing.progress)
                    Button("Cancel") { library.cancelImport() }
                }
            }
            ForEach(wordLists.indices, id: \.self) { index in
                row(wordLists[index])
            }
            Section {
                let checking = effUpdateState is EffUpdateStateChecking
                if checking { ProgressView("Checking eff.org…") }
                Button("Check for updates") { Task { await DicewareApp.checkForEffUpdates(in: library) } }
                    .disabled(checking)
                Button("Restore bundled lists") { library.restoreBundledEffWordLists() }
                    .disabled(checking || !wordLists.contains { $0.updated })
            }
        }
        .navigationTitle("Word Lists")
        .toolbar {
            Button("Import") { picking = true }
                .disabled(importState is ImportStateImporting)
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.plainText]) { result in
            guard case .success(let url) = result else { return }
            Task { await DicewareApp.importFile(at: url, into: library) }
        }
        .alert(resultMessage ?? "", isPresented: Binding(get: { resultMessage != nil }, set: { _ in library.dismissImportResult() })) {
            Button("OK") { library.dismissImportResult() }
        }
        .alert(effMessage ?? "", isPresented: Binding(get: { effMessage != nil }, set: { _ in library.dismissEffUpdate() })) {
            Button("OK") { library.dismissEffUpdate() }
        }
        .alert("Update EFF Word Lists?", isPresented: Binding(get: { effChanges != nil }, set: { _ in })) {
            Button("Update") { library.applyEffUpdate() }
            Button("Cancel", role: .cancel) { library.dismissEffUpdate() }
        } message: {
            Text((effChanges ?? []).flatMap(Self.summary(of:)).joined(separator: "\n"))
        }
        .alert("Rename Word List", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Rename") {
                if let id = renaming?.id as? CustomWordListId { library.rename(id: id, name: newName) }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(
            deleting.map { String(localized: "Delete \(Self.name(of: $0))?") } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let id = deleting?.id as? CustomWordListId { library.delete(id: id) }
            }
        }
        .task {
            for await newWordLists in library.wordLists { wordLists = newWordLists }
        }
        .task {
            for await newState in library.importState { importState = newState }
        }
        .task {
            for await newState in library.effUpdateState { effUpdateState = newState }
        }
    }

    private func row(_ info: WordListInfo) -> some View {
        VStack(alignment: .leading) {
            Text(Self.name(of: info))
            Text(Self.details(of: info))
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .contextMenu {
            if info.id is CustomWordListId {
                Button("Rename") {
                    newName = Self.name(of: info)
                    renaming = info
                }
                Button("Delete", role: .destructive) { deleting = info }
            }
        }
        .swipeActions {
            if info.id is CustomWordListId {
                Button("Delete", role: .destructive) { deleting = info }
                Button("Rename") {
                    newName = Self.name(of: info)
                    renaming = info
                }
            }
        }
    }

    private var resultMessage: String? {
        switch onEnum(of: importState) {
        case .imported(let imported): String(localized: "Imported \(imported.name).")
        case .failed(let failed): Self.message(for: failed.error)
        default: nil
        }
    }

    private var effChanges: [EffWordListChange]? {
        (effUpdateState as? EffUpdateStateUpdateAvailable)?.changes
    }

    private var effMessage: String? {
        switch onEnum(of: effUpdateState) {
        case .upToDate: String(localized: "The EFF Word Lists are up to date.")
        case .updated: String(localized: "The EFF Word Lists were updated.")
        case .failed(let failed): Self.message(for: failed.error)
        default: nil
        }
    }

    /// What an update changes in one EFF Word List: Words added and removed, with a few of each.
    static func summary(of change: EffWordListChange) -> [String] {
        let name = name(of: change.wordList)
        if change.wordsAdded == 0 && change.wordsRemoved == 0 {
            return [String(localized: "\(name): the same Words, matched to different Rolls")]
        }
        func sample(_ words: [String], _ total: Int64) -> String {
            (total > words.count ? words + ["…"] : words).joined(separator: ", ")
        }
        var lines = [String(localized: "\(name): \(change.wordsAdded.formatted()) Words added, \(change.wordsRemoved.formatted()) removed")]
        if !change.sampleAdded.isEmpty { lines.append(String(localized: "Added: \(sample(change.sampleAdded, change.wordsAdded))")) }
        if !change.sampleRemoved.isEmpty { lines.append(String(localized: "Removed: \(sample(change.sampleRemoved, change.wordsRemoved))")) }
        return lines
    }

    /// Why an eff.org check failed; nothing was changed.
    static func message(for error: EffUpdateError) -> String {
        switch onEnum(of: error) {
        case .offline:
            String(localized: "Couldn’t reach eff.org. Check your connection and try again. Nothing was changed.")
        case .httpStatus(let detail):
            String(localized: "eff.org answered with error \(detail.code). Nothing was changed.")
        case .tooLarge:
            String(localized: "The download from eff.org was over 1 MB, so it was refused. Nothing was changed.")
        case .redirected:
            String(localized: "eff.org redirected the download to another site, so it was refused. Nothing was changed.")
        case .invalid(let detail):
            String(localized: "\(name(of: detail.wordList)) from eff.org is not a valid Word List. \(message(for: detail.error)) Nothing was changed.")
        case .storageFailed:
            String(localized: "There is not enough storage space for the update. Nothing was changed.")
        }
    }

    /// A Custom Word List's own name, or the EFF Word List's display name.
    static func name(of info: WordListInfo) -> String {
        if let name = info.name { return name }
        return name(of: info.id as? EffWordList ?? .large)
    }

    static func name(of eff: EffWordList) -> String {
        switch eff {
        case .short1: String(localized: "EFF Short #1")
        case .short2: String(localized: "EFF Short #2")
        default: String(localized: "EFF Large")
        }
    }

    static func details(of info: WordListInfo) -> String {
        let details = String(localized: "\(info.wordCount.formatted()) Words · \(info.strengthPerWordBits, specifier: "%.1f") bits per Word")
        return info.updated ? String(localized: "\(details) · Updated from eff.org") : details
    }

    /// Why an import was rejected, naming the offending line.
    static func message(for error: ImportError) -> String {
        switch onEnum(of: error) {
        case .wrongWordCount(let detail):
            String(localized: "Not imported: the file has \(detail.wordCount.formatted()) Words, but a Word List needs exactly 6ⁿ Words (1,296, 7,776, 46,656, …).")
        case .duplicateWord(let detail):
            String(localized: "Not imported: line \(detail.line.formatted()), “\(detail.word)”, repeats the Word on line \(detail.firstLine.formatted()) (ignoring case).")
        case .wordHasWhitespace(let detail):
            String(localized: "Not imported, line \(detail.line.formatted()): the Word contains a space.")
        case .invalidRoll(let detail):
            String(localized: "Not imported, line \(detail.line.formatted()): the Roll must be digits 1 to 6.")
        case .mixedRollLength(let detail):
            String(localized: "Not imported, line \(detail.line.formatted()): the Roll has a different number of dice from line 1.")
        case .missingRoll(let detail):
            String(localized: "Not imported, line \(detail.line.formatted()): there is no Roll.")
        case .rollNotFound(let detail):
            String(localized: "Not imported: no line has the Roll \(detail.roll).")
        case .repeatedRoll(let detail):
            String(localized: "Not imported, line \(detail.line.formatted()): the Roll repeats the Roll on line \(detail.firstLine.formatted()).")
        case .lineTooLong(let detail):
            String(localized: "Not imported: line \(detail.line.formatted()) is too long to be a Word.")
        case .notText(let detail):
            String(localized: "Not imported: line \(detail.line.formatted()) is not UTF-8 text.")
        case .alreadyImported(let detail):
            String(localized: "Not imported: already imported as \(detail.name).")
        case .alreadyImportedAsEff(let detail):
            String(localized: "Not imported: already imported as \(name(of: detail.wordList)).")
        case .unreadable:
            String(localized: "Not imported: the file could not be read.")
        case .storageFailed:
            String(localized: "Not imported: there is not enough storage space. Nothing was saved.")
        }
    }
}
