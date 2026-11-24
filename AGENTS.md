# Diceware

## Hard rule: Words come only from Rolls

A Word is ALWAYS chosen by a Roll of dice (each die 1–6) resolved against the Word List, and a Passphrase is built by repeating that once per Word. In random mode the secure random generator rolls each die individually; in Dice Mode the user enters the dice. NEVER pick a Word from a single random number or index into the Word List directly, even though it would be mathematically equivalent. Digits and symbols are also chosen by Rolls (two dice each). See `docs/adr/0002-words-come-only-from-rolls.md`.

## American English (en-US)

Write everything in American English: code identifiers, UI strings, comments, tests, docs, commit messages and issues ("capitalize", "canceled", "favor", "color"). en-US is the declared default language on both platforms. CI runs `codespell` with the British-to-American dictionary (config in `.codespellrc`), so run `pipx run codespell==2.4.3` from the repo root before committing. The EFF Word Lists in `wordlists/` are third-party data and are never respelled.

## Static analysis

CI fails on any finding, so run these before committing:

- Kotlin and Compose (shared core and Android app): `./gradlew detekt :shared:detektMainAndroid`, configured in `config/detekt.yml`. The second task adds type resolution for the core's common and Android code, which the coroutine and null-safety rules need.
- Android: `./gradlew :androidApp:lintDebug`, which treats warnings as errors. It covers the Android app only: AGP 9.3's KMP library plugin has no lint for the core's main code, which detekt covers instead.
- Swift and SwiftUI: `swiftlint lint` from `iosApp/` (0.65.1, configured in `iosApp/.swiftlint.yml`).
- Swift compiler: the iOS project builds in Swift 6 language mode (data races are errors) with `SWIFT_TREAT_WARNINGS_AS_ERRORS`, so every compiler warning fails the build. Set both in `iosApp/project.yml` and regenerate with XcodeGen.

Fix a finding rather than silencing it. Suppress only a genuine false positive or a deliberate design choice, at that one spot, with a one-line reason.

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
