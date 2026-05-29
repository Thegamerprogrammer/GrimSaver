pluginManagement {
    repositories {
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        maven { name = "CCBlueX Releases"; url = uri("https://maven.ccbluex.net/releases") }
        maven { name = "CCBlueX Snapshots"; url = uri("https://maven.ccbluex.net/snapshots") }
        maven { name = "Fabric"; url = uri("https://maven.fabricmc.net/") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "GrimSaver"
