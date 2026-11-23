import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Renders the core's Word Lists state: every Word List with its Word count and Strength per Word, the
/// running import, and rename and delete for Custom Word Lists. Every action is forwarded to the core.
struct WordListsView: View {
    let generator: Generator
    @State private var wordLists: [WordListInfo] = []
    @State private var importState: ImportState = ImportStateIdle.shared
    @State private var picking = false
    @State private var renaming: WordListInfo?
    @State private var newName = ""
    @State private var deleting: WordListInfo?

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

    /// A Custom Word List's own name, or the EFF Word List's display name.
    static func name(of info: WordListInfo) -> String {
        if let name = info.name { return name }
        switch info.id as? EffWordList {
        case .short1: return String(localized: "EFF Short #1")
        case .short2: return String(localized: "EFF Short #2")
        default: return String(localized: "EFF Large")
        }
    }

    static func details(of info: WordListInfo) -> String {
        String(localized: "\(info.wordCount.formatted()) Words · \(info.strengthPerWordBits, specifier: "%.1f") bits per Word")
    }

    /// Why an import was rejected, naming the offending line.
    static func message(for error: ImportError) -> String {
        switch onEnum(of: error) {
        case .wrongWordCount(let e):
            String(localized: "Not imported: the file has \(e.wordCount.formatted()) Words, but a Word List needs exactly 6ⁿ Words (1,296, 7,776, 46,656, …).")
        case .duplicateWord(let e):
            String(localized: "Not imported: line \(e.line.formatted()), “\(e.word)”, repeats the Word on line \(e.firstLine.formatted()) (ignoring case).")
        case .wordHasWhitespace(let e):
            String(localized: "Not imported: the Word on line \(e.line.formatted()) contains a space.")
        case .invalidRoll(let e):
            String(localized: "Not imported: the Roll on line \(e.line.formatted()) must be digits 1 to 6.")
        case .mixedRollLength(let e):
            String(localized: "Not imported: the Roll on line \(e.line.formatted()) has a different number of dice from line 1.")
        case .missingRoll(let e):
            String(localized: "Not imported: line \(e.line.formatted()) has no Roll.")
        case .repeatedRoll(let e):
            String(localized: "Not imported: the Roll on line \(e.line.formatted()) repeats the Roll on line \(e.firstLine.formatted()).")
        case .lineTooLong(let e):
            String(localized: "Not imported: line \(e.line.formatted()) is too long to be a Word.")
        case .notText(let e):
            String(localized: "Not imported: line \(e.line.formatted()) is not UTF-8 text.")
        case .alreadyImported(let e):
            String(localized: "Not imported: already imported as \(e.name).")
        case .unreadable:
            String(localized: "Not imported: the file could not be read.")
        case .storageFailed:
            String(localized: "Not imported: there is not enough storage space. Nothing was saved.")
        }
    }
}
