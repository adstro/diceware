import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.skie)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "com.adstro.diceware.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// NoWeakRandomnessTest reads the main sources, so a comment-only change must still re-run it.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src") { include("*Main/**/*.kt") }).withPathSensitivity(PathSensitivity.RELATIVE)
}

skie {
    // No build analytics leave the machine (privacy stance).
    analytics { disableUpload.set(true) }
}
