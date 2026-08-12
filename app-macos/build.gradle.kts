import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// app-macos — OWNER: Agent B (docs/plan.md, machine split at M0).
//
// Post-MVP #2 module. Deliberately minimal for now: the collector and the
// seam it implements, no Compose UI. The UI is a straight reuse of app-linux's
// Compose screens when macOS is promoted out of post-MVP, and pulling the
// Compose plugins in before then would only add build weight and a second
// place for CMP desktop version drift to bite.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopTest by getting {
            dependencies {
                // Deliberately no kotlinx-coroutines-test: adding it means a
                // version-catalog edit, and the catalog is shared infra. These
                // tests drive the flow with runBlocking + take(), which needs
                // nothing beyond coroutines-core.
                implementation(libs.kotlin.test)
            }
        }
    }
}
