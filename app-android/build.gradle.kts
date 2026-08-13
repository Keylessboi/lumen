import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(project(":transport-xmpp"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.test.core)
                // X25519 reconstruction in AndroidKeychainTest (same BC the
                // keychain itself uses to generate the pair).
                implementation(libs.bouncycastle)
            }
        }
    }
}

android {
    namespace = "dev.lumen.app"
    compileSdk = 36

    packaging {
        resources {
            // Bouncy Castle (Argon2id, M5) ships multi-release JAR metadata
            // that collides during APK packaging:
            //   2 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'
            // OSGi and signing artefacts with no meaning inside an APK.
            // Excluded rather than pickFirst: first-wins silently keeps an
            // arbitrary one of two files, which is the wrong answer if a real
            // resource ever collides here. Excluding says these paths do not
            // belong in the package at all.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
        }
    }

    defaultConfig {
        applicationId = "dev.lumen.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // LOCKED hardening — see docs/plan.md locked decision #10.
    // allowBackup=false: Android auto-backup would exfiltrate the local DB
    // (and keys) to Google Drive in plaintext. This is how E2EE dies.
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            manifestPlaceholders["enableBackup"] = "false"
        }
        getByName("debug") {
            manifestPlaceholders["enableBackup"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
