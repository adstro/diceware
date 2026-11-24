# Diceware

## Hard rule: Words come only from Rolls

A Word is ALWAYS chosen by a Roll of dice (each die 1–6) resolved against the Word List, and a Passphrase is built by repeating that once per Word. In random mode the secure random generator rolls each die individually; in Dice Mode the user enters the dice. NEVER pick a Word from a single random number or index into the Word List directly, even though it would be mathematically equivalent. Digits and symbols are also chosen by Rolls (two dice each). See `docs/adr/0002-words-come-only-from-rolls.md`.

## American English (en-US)

Write everything in American English: code identifiers, UI strings, comments, tests, docs, commit messages and issues ("capitalize", "canceled", "favor", "color"). en-US is the declared default language on both platforms. CI runs `codespell` with the British-to-American dictionary (config in `.codespellrc`), so run `pipx run codespell==2.4.3` from the repo root before committing. The EFF Word Lists in `wordlists/` are third-party data and are never respelled.

## Static analysis

CI fails on any finding, so run these before committing:

- Kotlin and Compose (shared core and Android app): `./gradlew detekt :shared:detektMainAndroid :shared:detektHostTestAndroid :androidApp:detektDebug :androidApp:detektDebugUnitTest`, configured in `config/detekt.yml`. The plain `detekt` task covers every source file; the others add type resolution, which the coroutine, null-safety and many Compose rules need.
- Kotlin compiler: every warning is an error (`allWarningsAsErrors`), in the core and the Android app.
- Android: `./gradlew :androidApp:lintDebug :androidApp:lintRelease`, which treat warnings as errors. Some checks run only on release. They cover the Android app only: AGP 9.3's KMP library plugin has no lint for the core's main code, which detekt covers instead.
- Swift and SwiftUI: `swiftlint lint` from `iosApp/` (0.65.1, configured in `iosApp/.swiftlint.yml`).
- Swift compiler: the iOS project builds in Swift 6 language mode (data races are errors) with `SWIFT_TREAT_WARNINGS_AS_ERRORS`, so every compiler warning fails the build. Set both in `iosApp/project.yml` and regenerate with XcodeGen.

Fix a finding rather than silencing it. A bypass is anything that stops an analyzer reporting: `@Suppress`, `tools:ignore`, a `swiftlint:disable` or `codespell:ignore` comment, a rule turned off or narrowed in an analyzer's config, a skipped path, a baseline, or a warnings-as-errors setting turned off. Every bypass needs a line in `config/analysis-exceptions.txt`, and CI (`python3 scripts/analysis-exceptions.py`) fails on any that's missing. Before adding one, run `/interrogate` on it and get the project owner's approval. Approval is only for a genuine false positive or a deliberate design choice, scoped to that one spot, with a one-line reason. Remove a line when its bypass goes.

## Android work

Always use the installed Android skills for Android work:

- Interact with emulators and devices (create/boot AVDs, install, launch, screenshot, inspect UI) through the `android-cli` skill.
- Check Android code against the matching best-practice skill before calling it done: `edge-to-edge`, `adaptive`, `navigation-3`, `testing-setup`, `android-intent-security` (share-sheet import), `android-permissions-security`, `jetpack-compose-performance`, `anr-audit-and-fix`, `r8-analyzer`.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for adstro/diceware (via `gh`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
