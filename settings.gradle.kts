pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Versions are managed in libs.versions.toml
    // plugins {
    //     // Define the Kotlin Android plugin version here
    //     id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    // }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Snapshot repository removed
    }
}
rootProject.name = "SHCWallet" // Updated name
include(":app")
