pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumen"

include(":core")
include(":ui")
include(":transport-xmpp")
include(":app-linux")
include(":app-android")
include(":scrobble")
include(":app-common")
include(":app-macos")
