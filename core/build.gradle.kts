import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Single desktop JVM target. KMP forbids two jvm() targets in one module.
    // Platform keychains live in the app modules (LinuxKeychain in app-linux,
    // MacosKeychain in app-macos) as classes implementing core's Keychain
    // interface — expect/actual does not survive two JVM targets.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.sqldelight.runtime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sqldelight.jvm.driver)
                // Argon2id for the M5 export (docs/e2ee.md §7).
                implementation(libs.bouncycastle)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.sqldelight.android.driver)
                // Same Argon2id implementation as desktop, so an export
                // written on one opens on the other. A platform-specific KDF
                // would be a compatibility bug waiting for its first user.
                implementation(libs.bouncycastle)
            }
        }
    }
}

sqldelight {
    databases {
        create("LumenDatabase") {
            packageName.set("dev.lumen.core.db")
            // Schema is FROZEN at M1. Migrations are additive-only, owned by Agent A.
        }
    }
}

android {
    namespace = "dev.lumen.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
