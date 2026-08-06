# AGENTS.md

This file provides guidance to coding agents (Claude Code, Codex, etc.) when working with code in this repository. It is the canonical source; tool-specific entry points such as `CLAUDE.md` point here.

## Overview

kotlin-aho-corasick is a Kotlin Multiplatform implementation of the Aho-Corasick multi-pattern string matching algorithm (trie + failure/output links). It matches a fixed dictionary of words against texts in a single pass, in time proportional to the text length plus the number of matches. The implementation is pure common code with zero dependencies and supports every Kotlin target (JVM, JS, Wasm, all Native tiers). Sister library: [kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie) (same word list, but produces a `Regex`).

## Commands

```bash
# Build everything (all targets on this host) + lint + ABI check
./gradlew check

# Fast feedback loop: JVM tests only
./gradlew :aho-corasick:jvmTest

# Tests on other platforms (macOS host can run all of these)
./gradlew :aho-corasick:jsNodeTest :aho-corasick:wasmJsNodeTest :aho-corasick:wasmWasiNodeTest
./gradlew :aho-corasick:macosArm64Test :aho-corasick:iosSimulatorArm64Test

# Run a single test class
./gradlew :aho-corasick:jvmTest --tests "dev.hsbrysk.ahocorasick.MatchingTest"

# Lint (ktlint + detekt; detekt covers all KMP source sets via the plain `detekt` task)
./gradlew ktlintCheck
./gradlew detekt

# Auto-format
./gradlew ktlintFormat

# Update ABI reference dumps (run after changing public API; commit aho-corasick/api/*)
./gradlew updateKotlinAbi

# Verify publish artifacts locally. Keep the default latest-SNAPSHOT version: signing is skipped
# for SNAPSHOT versions, while a release version (-PpublishVersion=X.Y.Z) requires the PGP key
# (only available in CI).
./gradlew publishToMavenLocal

# Benchmarks (kotlinx-benchmark; results are recorded in benchmark/RESULTS.md)
./gradlew :benchmark:jvmBenchmark :benchmark:jsBenchmark :benchmark:wasmJsBenchmark :benchmark:macosArm64Benchmark
```

## Module Structure

| Module | Description |
|---|---|
| `aho-corasick` | The library. `AhoCorasick.kt` / `CaseFolding.kt` / `Match.kt` in `commonMain`; tests in `commonTest` run on every target. |
| `benchmark` | kotlinx-benchmark comparisons (naive regex alternation vs regexp-trie regex vs Aho-Corasick). Not published; declares only the targets it runs on (jvm/js/wasmJs/macosArm64/linuxX64). Results + methodology: `benchmark/RESULTS.md`, raw JSON in `benchmark/results/`. |
| `build-logic` | Convention Gradle plugins shared across modules. |

## Build Conventions

- The library module applies `conventions.preset.base` (= `conventions.kotlin` + `conventions.ktlint` + `conventions.detekt`). `conventions.kotlin` declares every KMP target; the Kotlin toolchain is Java 17 (Adoptium). `allWarningsAsErrors = true` is enforced. `benchmark` intentionally skips `conventions.kotlin` (it declares only the targets it runs on) and applies the ktlint/detekt conventions directly.
- `conventions.public-api` enables `explicitApi()` and Kotlin's built-in ABI validation (`checkKotlinAbi` runs as part of `check`; dumps live in `aho-corasick/api/`).
- Publishing uses the vanniktech maven-publish plugin (`conventions.maven-publish`). The version is resolved in `conventions.preset.base` from `-PpublishVersion` / `PUBLISH_VERSION`, defaulting to `latest-SNAPSHOT`. Releases are tag-driven (`vX.Y.Z` → `.github/workflows/publish.yml`, macOS runner because Apple targets can only be built there).
- Versions are centralized in `gradle/libs.versions.toml`; Gradle plugins used by `build-logic` are declared there as libraries under `# gradle plugins for build-logic`.

## Testing Conventions

- kotlin-test only (no assertk/MockK — they are not available on every KMP target).
- All tests live in `commonTest` so they run on every platform; this is what pins down that matching and case folding (`Char.lowercaseChar` tables) behave identically across platforms. The one exception is `jvmTest`'s `ConcurrencyTest`, which needs real threads (`java.util.concurrent`) to exercise the concurrent-read guarantee.
- Lone surrogates must be built at runtime (`Char(0xD83D).toString()`) — the Kotlin/JS compiler can mangle lone-surrogate string literals. The ZWJ and combining characters are written as `\uXXXX` escapes so the tested code points are unambiguous in the source.
- `StressTest` cross-checks against a brute-force oracle and exercises a 10,000-node-deep trie (everything must stay iterative; deep recursion crashes Kotlin/Native).

## Implementation Notes

- The trie is keyed per **code point** (`MutableMap<Int, Node>`), folded through the instance's `CaseFolding` at insertion and scan time. Folding is strictly 1 `Char` → 1 `Char`, which is what makes `Match.range` exact original-text indices (start = end − word length) with no index remapping. Never introduce a length-changing fold.
- The automaton (failure links, output links, precomputed `hasOutput`) is fully built in the constructor via BFS; instances are deeply immutable afterwards and safe for concurrent reads. There is no dynamic `add` — this is a deliberate design difference from RegexpTrie's mutable builder.
- `findAll` (leftmost-longest) is post-processing over the standard automaton output: sort by (start asc, end desc), then one greedy pass. A streaming approach is incorrect with the standard automaton because matches arrive ordered by end position (`{bc, abcd}` on `"abcd"` is the counterexample, pinned by a test).
- `findOverlapping` sorts by (start asc, end asc); both orderings are documented API guarantees and independent of `HashMap` iteration order.
