import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app-common — the glue app-linux and app-android share: account sign-up,
// the sync pass, scrobbling. Each app used to carry its own copy of this
// (Linux) or none at all (Android, which had no sync). No Compose code here:
// controllers expose :ui state types as StateFlows and take plain calls.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                api(project(":ui"))
                api(project(":transport-xmpp"))
                api(project(":scrobble"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.bouncycastle)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "dev.lumen.app.common"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
