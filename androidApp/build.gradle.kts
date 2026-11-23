plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Navigation 3 saves its back stack of @Serializable keys.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.adstro.diceware"
    // Compose UI 1.13 needs compileSdk 37.1.
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        applicationId = "com.adstro.diceware"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    // The bundled EFF Word Lists live once at the repo root and are shared with iOS.
    sourceSets.getByName("main").assets.directories.add("../wordlists")
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.shared)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
}
