pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "leyline"

include(":mesh-core")   // pure Kotlin: packets, routing, dedup, TTL — no Android
include(":mesh-ble")    // Android: advertise, scan, GATT client/server
include(":mesh-sim")    // JVM: virtual-node simulator and stress tests
include(":app")         // Android app: Compose UI, Room, foreground service
