pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        // mavenLocal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        // mavenLocal()
    }
}

rootProject.name = "kotlin-aho-corasick"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("aho-corasick")
include("benchmark")
