import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// app-macos — OWNER: Agent B (docs/plan.md, machine split at M0).
//
// Local-only macOS app: collector -> session tracker -> local store -> UI.
// No sync, no transport, no keychain yet. docs/plan.md locks local-only as the
// default posture ("sync additive, never a dependency"; "unconfigured
// transport valid"), so this is a complete vertical slice rather than a stub.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // Read-only JDBC access to the macOS Knowledge store for the
                // opt-in history import. App data does not go through this.
                implementation(libs.sqlite.jdbc)
            }
        }
        val desktopTest by getting {
            dependencies {
                // Deliberately no kotlinx-coroutines-test: adding it means a
                // version-catalog edit, and the catalog is shared infra. These
                // tests drive flows with runBlocking + take(), which needs
                // nothing beyond coroutines-core.
                implementation(libs.kotlin.test)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.lumen.macos.MainKt"
        nativeDistributions {
            // Mirrors the host guard in app-linux: Compose Desktop validates
            // target formats against the HOST os at configuration time, and
            // Gradle configures every project regardless of the requested task.
            if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
                targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            }
            packageName = "Lumen"
            packageVersion = "0.1.0"
            macOS {
                // Dmg rejects a MAJOR of 0 — the app version stays 0.1.0 while
                // the package version satisfies the macOS packaging rule.
                packageVersion = "1.0.0"
                // TCC lists apps by bundle identity. Packaged, Lumen appears as
                // "Lumen" in Privacy & Security; run from Gradle the process is
                // a bare JVM and shows up as "java". Granting Full Disk Access
                // therefore requires the packaged app, not `:app-macos:run`.
                bundleID = "dev.lumen.macos"
                infoPlist {
                    // Menu-bar app: no Dock icon, no app switcher entry. The
                    // window is opened on demand from the menu, which is the
                    // shape the tray-first design needs.
                    extraKeysRawXml = """
                        <key>LSUIElement</key>
                        <true/>
                    """.trimIndent()
                }
            }
        }
    }
}
