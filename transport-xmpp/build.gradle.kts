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
        // Smack is pure Java, so the XMPP client is shared by the desktop
        // JVM and Android (API 26+). It was desktop-only, which left the
        // Android app with no way to sync at all.
        val jvmMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.smack.core)
                implementation(libs.smack.tcp)
                implementation(libs.smack.extensions)
                implementation(libs.smack.resolver.minidns)
            }
        }
        // The XML parser is the one per-platform piece. Desktop uses StAX.
        // Android has no javax.xml.stream; it ships XmlPullParser, which
        // smack-xmlparser-xpp3 drives. The xpp3 jars themselves must be excluded
        // there: it duplicates org.xmlpull classes Android already has.
        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.smack.xmlparser.stax)
            }
        }
        val androidMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(libs.smack.xmlparser.xpp3.get().toString()) {
                    // Both artifact names exist upstream (xpp3, xpp3_min); either
                    // one makes R8 fail: "Library class XmlResourceParser
                    // implements program class org.xmlpull.v1.XmlPullParser".
                    exclude(group = "xpp3")
                }
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
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
