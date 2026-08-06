package conventions

import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    kotlin("multiplatform")
}

kotlin {
    explicitApi()

    // Calling abiValidation enables ABI validation and hooks checkKotlinAbi into the `check`
    // task. Klib dumps (non-JVM targets) are always generated alongside the JVM dump.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }
}
