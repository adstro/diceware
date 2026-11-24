# Diceware

## Hard rule: Words come only from Rolls

A Word is ALWAYS chosen by a Roll of dice (each die 1–6) resolved against the Word List, and a Passphrase is built by repeating that once per Word. In random mode the secure random generator rolls each die individually; in Dice Mode the user enters the dice. NEVER pick a Word from a single random number or index into the Word List directly, even though it would be mathematically equivalent. Digits and symbols are also chosen by Rolls (two dice each). See `docs/adr/0002-words-come-only-from-rolls.md`.

## American English (en-US)

Write everything in American English: code identifiers, UI strings, comments, tests, docs, commit messages and issues ("capitalize", "canceled", "favor", "color"). en-US is the declared default language on both platforms. CI runs `codespell` with the British-to-American dictionary (config in `.codespellrc`), so run `pipx run codespell==2.4.3` from the repo root before committing. The EFF Word Lists in `wordlists/` are third-party data and are never respelled.

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
