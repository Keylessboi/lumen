import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
                implementation(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        // Smack is pure Java — works on both desktop JVM and Android
        // (API 26+, matching our minSdk). The XMPP client lives in this
        // shared intermediate source set so both targets use the same
        // implementation without code duplication. The sync seam it
        // implements is in core commonMain.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.smack.core)
                implementation(libs.smack.tcp)
                implementation(libs.smack.extensions)
                implementation(libs.smack.xmlparser.stax)
                implementation(libs.smack.resolver.minidns)
            }
        }
        val desktopMain by getting {
            dependsOn(jvmMain)
        }
        val androidMain by getting {
            dependsOn(jvmMain)
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "dev.lumen.transport"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Live-test helper: prints the desktop runtime classpath so the XMPP
// round-trip harness can run directly against a real provider.
tasks.register("printClasspath") {
    doLast {
        val cp = configurations.getByName("desktopRuntimeClasspath").asPath
        println("CLASSPATH_START")
        println(cp)
        println("CLASSPATH_END")
    }
}
