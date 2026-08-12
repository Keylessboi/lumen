import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :ui — shared Compose Multiplatform UI.
//
// OWNERSHIP: proposed, not settled — see discussion #21. A shared UI module
// has no clean single owner, which is exactly the case the two-agent contract
// was built to avoid. Until that row lands in docs/plan.md, treat this module
// the way tools/ownership-check.sh treats build files: either agent may edit,
// the other reviews in the PR body.
//
// WHY IT EXISTS: adjudicated decision A picked Compose Multiplatform because
// "Kotlin collapses collector + core + UI into one language/build". That
// rationale is about sharing the UI. With per-app ui/ packages the Today
// screen gets written three times and docs/design-spec.md gets hand-copied
// three times — binding but unenforced, and the drift is invisible until
// someone compares screenshots.
//
// WHAT BELONGS HERE: anything the design spec describes — tokens, screens,
// charts, formatting. What does NOT: collectors, stores, keychains, packaging,
// tray/notification wiring, window and Activity hosts. Those stay in the app
// modules, which is where the platforms genuinely differ.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Single desktop JVM target, mirroring :core — KMP forbids two jvm()
    // targets in one module, so app-linux and app-macos share this one.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                // Weekday and month names for the trend chart's axis. Derived
                // in :ui rather than by each platform so the labels cannot
                // drift between Linux, Android and macOS.
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "dev.lumen.ui"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
