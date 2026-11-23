import Shared
import SwiftUI

/// Renders the core's Word Lists state: every Word List with its Word count and Strength per Word.
struct WordListsView: View {
    let generator: Generator
    @State private var wordLists: [WordListInfo] = []

    var body: some View {
        List(wordLists, id: \.wordList) { info in
            VStack(alignment: .leading) {
                Text(Self.name(of: info.wordList))
                Text(Self.details(of: info))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
        }
        .navigationTitle("Word Lists")
        .task {
            for await newWordLists in generator.wordLists { wordLists = newWordLists }
        }
    }

    static func name(of wordList: EffWordList) -> String {
        switch wordList {
        case .large: String(localized: "EFF Large")
        case .short1: String(localized: "EFF Short #1")
        case .short2: String(localized: "EFF Short #2")
        }
    }

    static func details(of info: WordListInfo) -> String {
        String(localized: "\(info.wordCount.formatted()) Words · \(info.strengthPerWordBits, specifier: "%.1f") bits per Word")
    }
}
