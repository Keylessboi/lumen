import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(project(":transport-xmpp"))
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                // X25519 keypair generation for LinuxKeychain (M4 E2EE).
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

compose.desktop {
    application {
        mainClass = "dev.lumen.app.MainKt"
        nativeDistributions {
            // Compose Desktop validates target formats against the HOST os at
            // configuration time, so an unguarded Linux-only list fails the whole
            // build on a non-Linux machine — including :core and :app-android.
            // An empty targetFormats() list is rejected, so skip the call entirely.
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                )
            }
            packageName = "lumen"
            packageVersion = "0.1.0"
            // JvmLumenStore uses JDBC (java.sql.DriverManager) for SQLite.
            // The jlink runtime image omits it by default, so the app crashes
            // at startup with NoClassDefFoundError.
            modules("java.sql")
        }
    }
}

// Live-test helper: prints the desktop runtime classpath so collector
// tests (LiveTest.kt) can run directly without the Compose window.
tasks.register("printClasspath") {
    doLast {
        val cp = configurations.getByName("desktopRuntimeClasspath").asPath
        println("CLASSPATH_START")
        println(cp)
        println("CLASSPATH_END")
    }
}
