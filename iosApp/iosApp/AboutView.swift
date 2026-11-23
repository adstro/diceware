import SwiftUI

/// Explains Diceware, credits the EFF Word Lists and states the privacy guarantees.
struct AboutView: View {
    static let sections: [(title: LocalizedStringResource, body: LocalizedStringResource)] = [
        (
            "What is Diceware?",
            "Diceware makes a passphrase from words chosen by rolls of dice. Each roll of five dice (four for a short word list), such as 4 3 5 6 1, picks one word from the word list. Repeating this once per word gives a passphrase that is easy to remember and hard to guess. This app rolls each die with your device's secure random generator."
        ),
        (
            "Word lists",
            "The built-in word lists are the EFF Word Lists by the Electronic Frontier Foundation (eff.org/dice), used under the Creative Commons Attribution 3.0 License (CC BY 3.0, creativecommons.org/licenses/by/3.0)."
        ),
        (
            "Privacy",
            "Diceware keeps no history of your passphrases, writes no logs and has no analytics. The only time it uses the network is when you ask it to check eff.org for updated EFF Word Lists."
        ),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Self.sections.indices, id: \.self) { index in
                    Text(Self.sections[index].title)
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                        .padding(.top, 16)
                    Text(Self.sections[index].body)
                }
            }
            .padding(24)
            .frame(maxWidth: 600, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("About")
    }
}
