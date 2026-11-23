import Shared
import SwiftUI

/// Renders the core's Generator state; all logic lives in the shared core.
struct GeneratorView: View {
    let generator: Generator
    @State private var state: GeneratorState?
    @State private var options: PassphraseOptions?
    @State private var showingWordLists = false
    @State private var showingAbout = false
    @State private var showingDiceEntry = false

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
    }

    private var content: some View {
        VStack(spacing: 24) {
            if let state {
                switch onEnum(of: state) {
                // Saved settings arrive within milliseconds; showing controls before then would let them be overwritten.
                case .loading:
                    EmptyView()
                case .ready(let ready):
                    Text(ready.passphrase)
                        // Monospaced keeps l, 1 and I distinguishable.
                        .font(.system(.title, design: .monospaced))
                        .multilineTextAlignment(.center)
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
                if let options, !(state is GeneratorStateLoading) {
                    OptionsControls(generator: generator, options: options)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: 600)
    }

    /// The root view's privacy cover does not reach a sheet, so the Dice entry sheet carries its own.
    static func diceEntrySheet(generator: Generator) -> ModifiedContent<DiceEntryView, PrivacyCover> {
        DiceEntryView(generator: generator).modifier(PrivacyCover())
    }
}

/// Renders the core's PassphraseOptions and forwards each change to the core.
struct OptionsControls: View {
    let generator: Generator
    let options: PassphraseOptions

    var body: some View {
        let range = PassphraseOptions.companion.WORD_COUNTS
        VStack(spacing: 12) {
            Picker("Mode", selection: Binding(get: { options.mode }, set: { generator.setMode(mode: $0) })) {
                Text("Random").tag(Mode.random)
                Text("Dice").tag(Mode.dice)
            }
            .pickerStyle(.segmented)
            LabeledContent("Word List") {
                Picker(
                    "Word List",
                    selection: Binding(get: { options.wordList }, set: { generator.setWordList(wordList: $0) })
                ) {
                    ForEach(EffWordList.allCases, id: \.self) { Text(WordListsView.name(of: $0)).tag($0) }
                }
            }
            Stepper(
                "Words: \(options.wordCount)",
                value: Binding(get: { Int(options.wordCount) }, set: { generator.setWordCount(count: Int32($0)) }),
                in: Int(range.first)...Int(range.last)
            )
            LabeledContent("Separator") {
                Picker(
                    "Separator",
                    selection: Binding(get: { options.separator }, set: { generator.setSeparator(separator: $0) })
                ) {
                    ForEach(Separator.allCases, id: \.self) { Text(Self.label(of: $0)).tag($0) }
                }
            }
            Toggle(
                "Capitalise each Word",
                isOn: Binding(get: { options.capitalise }, set: { generator.setCapitalise(on: $0) })
            )
            Toggle(
                "Add a digit",
                isOn: Binding(get: { options.appendDigit }, set: { generator.setAppendDigit(on: $0) })
            )
            Toggle(
                "Add a symbol",
                isOn: Binding(get: { options.appendSymbol }, set: { generator.setAppendSymbol(on: $0) })
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
