plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover) apply false
}

detekt {
    source.setFrom(fileTree(".") { include("shared/src/**/*.kt", "androidApp/src/**/*.kt") })
    config.setFrom("config/detekt.yml")
    buildUponDefaultConfig = true
    parallel = true
}

dependencies {
    detektPlugins(libs.compose.rules.detekt)
    detektPlugins(libs.detekt.ktlint)
}
