package conventions

plugins {
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    // The plain `detekt` task only looks at src/main and src/test by default, which does not
    // match KMP source set layout (src/commonMain, src/commonTest, ...). Point it at `src`
    // so every source set is analyzed.
    source.setFrom(files("src"))
    config.setFrom("${rootProject.rootDir}/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt> {
    exclude {
        // invariantSeparatorsPath so the exclusion also works on Windows.
        it.file.invariantSeparatorsPath.contains("/build/generated/")
    }
    reports {
        sarif.required.set(true)
    }
}
