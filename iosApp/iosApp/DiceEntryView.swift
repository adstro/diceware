import Shared
import SwiftUI

/// Renders the core's Dice entry state and forwards each die to the core.
struct DiceEntryView: View {
    let generator: Generator
    @State private var entry: DiceEntryState?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                if let entry { content(entry) }
            }
            .navigationTitle("Enter dice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .task {
            for await newEntry in generator.diceEntry { entry = newEntry }
        }
    }

    private func content(_ entry: DiceEntryState) -> some View {
        VStack(spacing: 16) {
            Text(Self.prompt(for: entry)).font(.headline)
            if entry.rerollDigit {
                Text("That Roll doesn't pick a digit. Roll the 2 dice again.").foregroundStyle(.red)
            }
            if entry.step != .done {
                Text((0..<Int(entry.diceInRoll)).map { $0 < entry.roll.count ? "\(entry.roll[$0])" : "–" }.joined(separator: " "))
                    .font(.system(.title, design: .monospaced))
                    .accessibilityLabel("Dice entered: \(entry.roll.map { "\($0)" }.joined(separator: ", "))")
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(72)), count: 3), spacing: 8) {
                    ForEach(1...6, id: \.self) { face in
                        Button { generator.enterDie(face: Int32(face)) } label: {
                            Text("\(face)").font(.title).frame(minWidth: 64, minHeight: 64)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityLabel(Self.label(face: face))
                    }
                }
            }
            Button("Remove last die") { generator.removeLastDie() }
                .disabled(entry.words.isEmpty && entry.roll.isEmpty)
            // Below the keypad, so the dice stay in reach as Words pile up.
            ForEach(Array(entry.words.enumerated()), id: \.offset) { _, word in
                Text(word).font(.system(.title3, design: .monospaced))
            }
        }
        .padding(24)
        .frame(maxWidth: 600)
    }

    static func label(face: Int) -> String { String(localized: "Die face \(face)") }

    static func prompt(for entry: DiceEntryState) -> String {
        switch entry.step {
        case .word: String(localized: "Word \(entry.words.count + 1) of \(entry.wordCount): roll \(entry.diceInRoll) dice")
        case .digit: String(localized: "Digit: roll 2 dice")
        case .symbol: String(localized: "Symbol: roll 2 dice")
        case .done: String(localized: "Every Roll is entered.")
        }
    }
}
