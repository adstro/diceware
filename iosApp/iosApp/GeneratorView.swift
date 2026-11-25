import Shared
import SwiftUI

/// Renders the core's Generator state; all logic lives in the shared core.
struct GeneratorView: View {
    let generator: Generator
    @State private var state: GeneratorState?
    @State private var options: PassphraseOptions?
    @State private var wordLists: [WordListInfo] = []
    @Binding var showingWordLists: Bool
    @State private var showingAbout = false
    @State private var showingDiceEntry = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    var body: some View {
        NavigationStack {
            // Scroll only when Dynamic Type makes the content taller than the screen.
            ViewThatFits(in: .vertical) {
                content
                ScrollView { content }
            }
            .navigationTitle("Diceware")
            .toolbar {
                Menu {
                    Button("Word Lists") { showingWordLists = true }
                    Button("About") { showingAbout = true }
                } label: {
                    Label("More options", systemImage: "ellipsis.circle")
                }
            }
            .navigationDestination(isPresented: $showingWordLists) { WordListsView(generator: generator) }
            .navigationDestination(isPresented: $showingAbout) { AboutView() }
            .sheet(isPresented: $showingDiceEntry) { Self.diceEntrySheet(generator: generator) }
        }
        .task {
            for await newState in generator.state { state = newState }
        }
        .task {
            for await newOptions in generator.options { options = newOptions }
        }
        .task {
            for await newWordLists in generator.library.wordLists { wordLists = newWordLists }
        }
    }

    @ViewBuilder private var content: some View {
        if let state {
            if Self.sideBySide(horizontal: horizontalSizeClass, vertical: verticalSizeClass) {
                HStack(spacing: 32) {
                    VStack(spacing: 24) { passphrasePane(state) }
                        .frame(maxWidth: .infinity)
                    optionsPane(state)
                        .frame(maxWidth: .infinity)
                }
                .padding(24)
                .frame(maxWidth: 1000)
            } else {
                VStack(spacing: 24) {
                    passphrasePane(state)
                    optionsPane(state)
                }
                .padding(24)
                .frame(maxWidth: 600)
            }
        }
    }

    /// Passphrase and options side by side on a wide window (iPad, or any regular width) and in a short one
    /// (iPhone landscape); one above the other otherwise.
    static func sideBySide(horizontal: UserInterfaceSizeClass?, vertical: UserInterfaceSizeClass?) -> Bool {
        horizontal == .regular || vertical == .compact
    }

    @ViewBuilder private func passphrasePane(_ state: GeneratorState) -> some View {
        switch onEnum(of: state) {
        // Saved settings arrive within milliseconds; showing controls before then would let them be overwritten.
        case .loading:
            EmptyView()
        case .ready(let ready):
            PassphraseView(ready: ready)
            Text("Strength: \(ready.strengthBits, specifier: "%.1f") bits")
            if ready.isWeak {
                Text("Weak: below 64 bits. Add Words for a stronger passphrase.")
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
        case .awaitingDice:
            Text("Roll your dice and enter each Roll to build a passphrase.")
                .multilineTextAlignment(.center)
        case .randomnessUnavailable:
            Text("Secure randomness is unavailable on this device, so no passphrase was generated.")
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        if options?.mode == .dice {
            Button("Enter dice") {
                generator.startDiceEntry()
                showingDiceEntry = true
            }
            .buttonStyle(.borderedProminent)
        } else {
            Button("Roll new passphrase") { generator.regenerate() }
                .buttonStyle(.borderedProminent)
        }
        if case .ready(let ready) = onEnum(of: state) {
            Button("Copy") { PassphraseClipboard.copy(ready.passphrase) }
                .buttonStyle(.bordered)
        }
    }

    @ViewBuilder private func optionsPane(_ state: GeneratorState) -> some View {
        if let options, !(state is GeneratorStateLoading) {
            OptionsControls(generator: generator, options: options, wordLists: wordLists)
        }
    }

    /// The root view's privacy cover does not reach a sheet, so the Dice entry sheet carries its own.
    static func diceEntrySheet(generator: Generator) -> ModifiedContent<DiceEntryView, PrivacyCover> {
        DiceEntryView(generator: generator).modifier(PrivacyCover())
    }
}

/// The Passphrase as one line of text to the eye, but to VoiceOver one element per Word (then per appended
/// character), each with a "Spell out" action that reads the whole Passphrase character by character.
struct PassphraseView: View {
    let ready: GeneratorStateReady

    var body: some View {
        Text(ready.passphrase)
            // Monospaced keeps l, 1 and I distinguishable.
            .font(.system(.title, design: .monospaced))
            .multilineTextAlignment(.center)
            .accessibilityRepresentation {
                HStack {
                    ForEach(Array(ready.parts.enumerated()), id: \.offset) { _, part in
                        Text(part.spoken)
                            .accessibilityAction(named: "Spell out") {
                                UIAccessibility.post(
                                    notification: .announcement,
                                    argument: Self.spelledOut(ready.passphrase)
                                )
                            }
                    }
                }
            }
    }

    /// The Passphrase for VoiceOver to read one character at a time, naming capitals and punctuation.
    static func spelledOut(_ passphrase: String) -> NSAttributedString {
        NSAttributedString(string: passphrase, attributes: [.accessibilitySpeechSpellOut: true])
    }
}

/// Renders the core's PassphraseOptions, with every Word List to pick from, and forwards each change to the core.
struct OptionsControls: View {
    let generator: Generator
    let options: PassphraseOptions
    let wordLists: [WordListInfo]

    var body: some View {
        let range = PassphraseOptions.companion.WORD_COUNTS
        VStack(spacing: 12) {
            Picker(
                "Mode",
                selection: Binding(
                    get: { options.mode },
                    set: { generator.change(option: OptionChangeSetMode(mode: $0)) }
                )
            ) {
                Text("Random").tag(Mode.random)
                Text("Dice").tag(Mode.dice)
            }
            .pickerStyle(.segmented)
            LabeledContent("Word List") {
                Picker(
                    "Word List",
                    // Word List ids are Kotlin objects behind a protocol, so the Picker selects by position.
                    selection: Binding(
                        get: { wordLists.firstIndex { ($0.id as AnyObject).isEqual(options.wordList) } ?? 0 },
                        set: { generator.change(option: OptionChangeSetWordList(wordList: wordLists[$0].id)) }
                    )
                ) {
                    ForEach(wordLists.indices, id: \.self) { Text(WordListsView.name(of: wordLists[$0])).tag($0) }
                }
            }
            Stepper(
                "Words: \(options.wordCount)",
                value: Binding(
                    get: { Int(options.wordCount) },
                    set: { generator.change(option: OptionChangeSetWordCount(count: Int32($0))) }
                ),
                in: Int(range.first)...Int(range.last)
            )
            LabeledContent("Separator") {
                Picker(
                    "Separator",
                    selection: Binding(
                        get: { options.separator },
                        set: { generator.change(option: OptionChangeSetSeparator(separator: $0)) }
                    )
                ) {
                    ForEach(Separator.allCases, id: \.self) { Text(Self.label(of: $0)).tag($0) }
                }
            }
            Toggle(
                "Capitalize each Word",
                isOn: Binding(
                    get: { options.capitalize },
                    set: { generator.change(option: OptionChangeSetCapitalize(on: $0)) }
                )
            )
            Toggle(
                "Add a digit",
                isOn: Binding(
                    get: { options.appendDigit },
                    set: { generator.change(option: OptionChangeSetAppendDigit(on: $0)) }
                )
            )
            Toggle(
                "Add a symbol",
                isOn: Binding(
                    get: { options.appendSymbol },
                    set: { generator.change(option: OptionChangeSetAppendSymbol(on: $0)) }
                )
            )
        }
    }

    private static func label(of separator: Separator) -> LocalizedStringKey {
        switch separator {
        case .space: "Space"
        case .hyphen: "Hyphen"
        case .period: "Period"
        case .underscore: "Underscore"
        case .none: "None"
        }
    }
}
