# Shared Kotlin core, native UI per platform

All logic (Passphrase generation, word lists, secure randomness) lives in a shared Kotlin Multiplatform core, while each platform gets its own native UI: Jetpack Compose (Material 3) on Android and SwiftUI on iOS. We chose this over Compose Multiplatform everywhere because following each platform's UI paradigms is a core requirement, and a shared Compose UI does not look or behave natively on iOS. Web is deferred; when it returns it gets its own UI layer over the same core.

## Considered Options

- **Compose Multiplatform on all platforms**: maximum code reuse, rejected because iOS would not feel native.
- **Compose Multiplatform shared by Android and web, SwiftUI on iOS**: rejected along with deferring web.
