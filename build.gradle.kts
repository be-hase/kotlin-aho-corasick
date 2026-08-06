plugins {
    // Load the Kotlin plugin once so all subprojects share the same plugin classloader.
    alias(libs.plugins.kotlin.multiplatform) apply false
    id("conventions.kover")
    id("conventions.dokka")
}

dependencies {
    kover(projects.ahoCorasick)

    dokka(projects.ahoCorasick)
}

dokka {
    moduleName = "Kotlin Aho-Corasick"
}
