import kotlinx.benchmark.gradle.JvmBenchmarkTarget

// Not published; does not apply conventions.preset.base because benchmarks only need the targets
// they actually run on, not the library's full target matrix.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
    id("conventions.ktlint")
    id("conventions.detekt")
}

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(17)
        vendor = JvmVendorSpec.ADOPTIUM
    }

    compilerOptions {
        allWarningsAsErrors = true
    }

    jvm()
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.ahoCorasick)
                implementation(libs.regexp.trie)
                implementation(libs.kotlinx.benchmark.runtime)
            }
        }
    }
}

// JMH requires benchmark classes to be open on the JVM.
allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

benchmark {
    targets {
        register("jvm") {
            this as JvmBenchmarkTarget
            jmhVersion = "1.37"
        }
        register("js")
        register("wasmJs")
        register("macosArm64")
        register("linuxX64")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ms"
        }
        // Only the dense AhoCorasick scans/builds — a regression check for prefilter changes.
        register("dense") {
            include("benchmark\\.AhoCorasickBenchmark\\.ahoCorasick")
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ms"
        }
        // Only the sparse-match benchmarks — a faster loop for prefilter A/B runs.
        register("sparse") {
            include("SparseMatchBenchmark")
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            mode = "avgt"
            outputTimeUnit = "ms"
        }
    }
}
