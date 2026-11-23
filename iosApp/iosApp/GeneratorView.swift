import Shared
import SwiftUI

/// Renders the core's Generator state; all logic lives in the shared core.
struct GeneratorView: View {
    let generator: Generator
    @State private var state: GeneratorState?

    var body: some View {
        NavigationStack {
            // Scroll only when Dynamic Type makes the content taller than the screen.
            ViewThatFits(in: .vertical) {
                content
                ScrollView { content }
            }
            .navigationTitle("Diceware")
        }
        .task {
            for await newState in generator.state { state = newState }
        }
    }

    private var content: some View {
        VStack(spacing: 24) {
            if let state {
                switch onEnum(of: state) {
                case .ready(let ready):
                    Text(ready.passphrase)
                        // Monospaced keeps l, 1 and I distinguishable.
                        .font(.system(.title, design: .monospaced))
                        .multilineTextAlignment(.center)
                case .randomnessUnavailable:
                    Text("Secure randomness is unavailable on this device, so no passphrase was generated.")
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                }
            }
            Button("Roll new passphrase") { generator.regenerate() }
                .buttonStyle(.borderedProminent)
        }
        .padding(24)
        .frame(maxWidth: 600)
    }
}
