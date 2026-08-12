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
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(libs.kotlinx.coroutines.core)
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
            // configuration time, and Gradle configures every project regardless
            // of the requested task. An unguarded Linux-only list therefore fails
            // :core and :app-android too on any non-Linux machine, not just this
            // module. Non-Linux hosts get no packaging tasks at all: app-linux is
            // a Linux deliverable, and a macOS dev only needs it to configure and
            // compile so the rest of the build is reachable.
            //
            // The call must be skipped entirely rather than emptied — an empty
            // targetFormats() fails with "Collection is empty", and substituting
            // TargetFormat.Dmg fails because Dmg requires MAJOR > 0 while
            // packageVersion is 0.1.0.
            if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
                targetFormats(
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                    org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                )
            }
            packageName = "lumen"
            packageVersion = "0.1.0"
        }
    }
}
