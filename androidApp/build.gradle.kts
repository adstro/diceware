plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 saves its back stack of @Serializable keys.
    alias(libs.plugins.kotlin.serialization)
    // Type-resolved detekt (detektProdDebug): the Compose and coroutine rules that need types.
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

detekt {
    config.setFrom(rootProject.file("config/detekt.yml"))
    buildUponDefaultConfig = true
}

android {
    namespace = "dev.adstro.diceware"
    // Declares the unqualified values/ strings as en-US (res/resources.properties) and lists it as the app's language.
    androidResources { generateLocaleConfig = true }
    lint {
        warningsAsErrors = true
    }
    // Compose UI 1.13 needs compileSdk 37.1.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "dev.adstro.diceware"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    flavorDimensions += "channel"
    productFlavors {
        create("prod") { dimension = "channel" }
        // A separate app ID so beta installs beside prod and its crash reports stay apart.
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
        }
    }
    // The bundled EFF Word Lists live once at the repo root and are shared with iOS.
    sourceSets.getByName("main").assets.directories.add("../wordlists")
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

// Line coverage of the app from its unit tests may not drop below today's level.
kover { reports { variant("prodDebug") { verify { rule { minBound(89) } } } } }

kotlin {
    jvmToolchain(21)
    compilerOptions { allWarningsAsErrors = true }
}

dependencies {
    detektPlugins(libs.compose.rules.detekt)
    detektPlugins(libs.detekt.ktlint)
    implementation(projects.shared)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Window size classes for the adaptive Generator layout.
    implementation(libs.androidx.window.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
}
