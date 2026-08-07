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

# Focused benchmark loops for prefilter work: only the sparse-match scenario, or only the dense
# AhoCorasick scan/build (regression check). Same pattern for the other targets.
./gradlew :benchmark:jvmSparseBenchmark :benchmark:jsSparseBenchmark
./gradlew :benchmark:jvmDenseBenchmark :benchmark:jsDenseBenchmark
```

## Module Structure

| Module | Description |
|---|---|
| `aho-corasick` | The library. `commonMain`: `AhoCorasick.kt` (public API) / `CaseFolding.kt` / `Match.kt`, plus the internal double-array engine `CompactAutomaton.kt` / `DoubleArrayBuilder.kt` / `Nfa.kt` / `CodeMapper.kt` / `CodePoints.kt` and the scan accelerator `Prefilter.kt`; tests in `commonTest` run on every target. |
| `benchmark` | kotlinx-benchmark comparisons (naive regex alternation vs regexp-trie regex vs Aho-Corasick, plus the frozen v1 HashMap-trie copy `LegacyAhoCorasick`). `AhoCorasickBenchmark` is the dense-match scenario (~50% keyword tokens), `SparseMatchBenchmark` the sparse one (~2% keyword tokens whose rare character is absent from the filler) — the regime the prefilter targets. Not published; declares only the targets it runs on (jvm/js/wasmJs/macosArm64/linuxX64). Results + methodology: `benchmark/RESULTS.md`, raw JSON in `benchmark/results/`. |
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

- The engine is a **compact double-array automaton** in the charwise daachorse layout ("Engineering faster double-array Aho-Corasick automata", Kanda et al. 2023): one interleaved `IntArray` with four ints per state (base / check / fail / output position), transitions are `child = base xor mappedCode` verified by `check[child] == parent`. All sentinels are -1 (`base = -1` no children — 0 is a legal base; `check = -1` root and never-occupied slots; `outputPos = -1` no output). The alphabet is **case-folded code points** mapped through `CodeMapper` to dense frequency-ranked ids; code points absent from the dictionary reset the scan to the root without touching the array.
- Safety invariant: the array length is always a multiple of the block length (alphabet size rounded up to a power of two) and mapped codes are smaller than the block length, so `base xor c` stays inside base's block — the scan loop needs no bounds guard. Don't break the multiple-of-blockLen property (allocation and trimming both preserve it).
- Construction pipeline (`CompactAutomaton.build`): frequency count (+ empty-word `require`) → `CodeMapper` → `Nfa` (flat linked-list trie, BFS failure links + output forest on original ids) → `DoubleArrayBuilder` placement (BFS, free-slot list windowed to the newest 16 blocks per the paper's Chain + SkipForward) → fails rewritten through the state→slot mapping. `check` is written only when a child is placed, which makes false transitions structurally impossible. Everything stays iterative (deep recursion crashes Kotlin/Native) and deterministic (`CodeMapper` ranks are tie-broken by code point, never HashMap iteration order).
- Word matches are emitted through the **output forest** (`outputWordIndexes` / `outputParentPositions`): `outputPos >= 0` iff the state or a failure ancestor ends a word (this is also `containsAny`'s short-circuit), and the parent chain emits own word first, then failure-chain words. Forest entries reference forest positions, not state ids, so relocation leaves them untouched.
- Folding is strictly 1 `Char` → 1 `Char`, which is what makes `Match.range` exact original-text indices (start = end − word length) with no index remapping. Never introduce a length-changing fold.
- The automaton is fully built in the constructor; instances are deeply immutable afterwards and safe for concurrent reads. There is no dynamic `add` — this is a deliberate design difference from RegexpTrie's mutable builder.
- `findAll` (leftmost-longest) is post-processing over the standard automaton output: sort by (start asc, end desc), then one greedy pass. A streaming approach is incorrect with the standard automaton because matches arrive ordered by end position (`{bc, abcd}` on `"abcd"` is the counterexample, pinned by a test).
- `findOverlapping` sorts by (start asc, end asc); both orderings are documented API guarantees.
- Scans are accelerated by an internal **rare-character prefilter** (`Prefilter.kt`, in the spirit of the `RareBytes` prefilters of BurntSushi's aho-corasick crate): one folded rarest character per word (greedy union, capped at 8; words already containing a chosen character add nothing), expanded to every case-folding preimage the unfolded text may contain (e.g. `k` ← `k`/`K`/Kelvin sign U+212A, via a full BMP sweep under `UNICODE_SIMPLE`; capped at 16 search chars). Candidates are located with `String.indexOf(Char)`, which compiles to the platform's native (SIMD) memchr on the JVM and V8 — the whole point, so never replace it with a manual loop. The scan consults the prefilter **only at the root state** (non-root means a partial match is in progress) and jumps to `candidate - (maxWordLength - 1)`, stepped back one `Char` if that would split a surrogate pair. A per-scan `Cursor` (the `Prefilter` itself is immutable → concurrent reads stay safe) caches per-char positions plus the minimum candidate, and disables the prefilter mid-scan when less than half the scanned text was skipped (dense candidates), falling back to the plain loop seamlessly at the root. The prefilter must never change observable results — `PrefilterTest` pins oracle equivalence, the surrogate-pair jump edge, fold preimages, both disable paths, and that the prefilter actually engages.
