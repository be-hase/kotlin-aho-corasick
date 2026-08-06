# kotlin-aho-corasick

[![Maven Central](https://img.shields.io/maven-central/v/dev.hsbrysk/aho-corasick)](https://central.sonatype.com/artifact/dev.hsbrysk/aho-corasick)
[![CI](https://github.com/be-hase/kotlin-aho-corasick/actions/workflows/ci.yml/badge.svg)](https://github.com/be-hase/kotlin-aho-corasick/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/be-hase/kotlin-aho-corasick/graph/badge.svg)](https://codecov.io/gh/be-hase/kotlin-aho-corasick)

[Aho-Corasick](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm) multi-pattern string
matching for Kotlin Multiplatform: match a dictionary of thousands of words against a text in a
single pass. The scan cost is proportional to the text length plus the number of occurrences —
independent of the dictionary size. Pure common code, zero dependencies, every Kotlin target.

Looking for a `Regex` instead — to combine the word list with boundaries, flags or a larger
pattern? Use the sister library [kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie).
Use this library for large dictionaries, all (possibly overlapping) occurrences, or cheap
"contains any?" screening.

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.hsbrysk:aho-corasick:<version>")
}
```

For a Kotlin Multiplatform project, add it to `commonMain`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.hsbrysk:aho-corasick:<version>")
        }
    }
}
```

## Usage

```kotlin
import dev.hsbrysk.ahocorasick.AhoCorasick
import dev.hsbrysk.ahocorasick.CaseFolding
import dev.hsbrysk.ahocorasick.buildAhoCorasick

val ac = AhoCorasick("he", "she", "his", "hers")

// Leftmost-longest, non-overlapping (what `fgrep -o` reports)
ac.findAll("ushers")          // [Match(word=she, range=1..3)]

// Every occurrence, including overlaps and words inside other words
ac.findOverlapping("ushers")  // [Match(word=she, range=1..3), Match(word=he, range=2..3),
                              //  Match(word=hers, range=2..5)]

// Early-exit boolean — ideal for NG-word screening
ac.containsAny("ushers")      // true

// Match.range indexes the original text: text.substring(match.range) == matched region

// DSL style, with optional case folding
val ng = buildAhoCorasick {
    caseFolding = CaseFolding.ASCII
    +"badword"
    +"worseword"
    addAll(moreWords)
}
ng.containsAny("No BadWord here?")  // true
```

The automaton is built once in the constructor and is immutable afterwards — share one instance
and use it concurrently from any number of threads. Words cannot be added later; build a new
instance instead (for incremental building, collect words first or use `AhoCorasick.Builder`).

## Details

- **Match semantics** — `findAll` returns the leftmost-longest non-overlapping matches: candidates
  are taken by start position, the longest wins at the same start, and anything overlapping an
  accepted match is dropped. `findOverlapping` returns the raw Aho-Corasick output: every
  occurrence of every word. Both are ordered by position and deterministic — match positions and
  counts depend only on the set of words, never on insertion order (the one exception: which
  spelling `Match.word` reports when words collide under case folding — first registered wins).
- **Case folding** — opt-in via `CaseFolding`: `ASCII` folds only `A-Z`/`a-z`;
  `UNICODE_SIMPLE` applies the Unicode simple (1:1, locale-independent) lowercase mapping per
  `Char`. Length-changing *full* folding is intentionally unsupported (`ß` never matches `ss`;
  Greek final sigma `ς` does not match `σ`) — normalize both sides yourself if you need it. In
  exchange, reported ranges are always exact indices into the original text.
- **Code-point aware** — matching is per Unicode code point: surrogate pairs (e.g. emoji) are
  handled correctly on all platforms and half of a pair never matches the pair. Ranges are `Char`
  (UTF-16) indices, like `MatchResult.range`.
- **Empty words are rejected** (`IllegalArgumentException`); duplicates are deduplicated with
  first-registered-wins (also for words that collide under case folding).
- **No recursion anywhere** — construction and scanning are iterative, so pathological
  dictionaries (one 10,000-character word) are safe even on Kotlin/Native's limited stack.

## Benchmark

Scan time is essentially flat in the dictionary size — for a 10,000-word list, `findAll` is
**~600× faster on the JVM, ~800× on Wasm and ~440× on Kotlin/Native** than a naive
`word1|word2|...` regex alternation, and **3–10× faster** than the trie-optimized regex from
[kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie). The exception is Kotlin/JS,
where V8's regex engine beats this pure-Kotlin automaton at every size tested — on JS prefer
kotlin-regexp-trie for raw scan speed. See [benchmark/RESULTS.md](benchmark/RESULTS.md) for full
results and methodology.

## Supported platforms

All Kotlin targets. The implementation is pure common code with zero dependencies.

| Platform | Targets |
|---|---|
| JVM | `jvm` |
| JS | `js` |
| Wasm | `wasmJs`, `wasmWasi` |
| macOS / iOS | `macosX64`, `macosArm64`, `iosArm64`, `iosX64`, `iosSimulatorArm64` |
| watchOS / tvOS | `watchosArm32`, `watchosArm64`, `watchosX64`, `watchosSimulatorArm64`, `watchosDeviceArm64`, `tvosArm64`, `tvosX64`, `tvosSimulatorArm64` |
| Linux / Windows | `linuxX64`, `linuxArm64`, `mingwX64` |
| Android Native | `androidNativeArm32`, `androidNativeArm64`, `androidNativeX86`, `androidNativeX64` |

## Credits

The algorithm is from Alfred V. Aho and Margaret J. Corasick,
["Efficient string matching: an aid to bibliographic search"](https://dl.acm.org/doi/10.1145/360825.360855)
(CACM, 1975) — the algorithm behind `fgrep`. Implementations this library learned from:

- [BurntSushi/aho-corasick](https://github.com/BurntSushi/aho-corasick) (Rust) — the design
  reference for match semantics and the pragmatic take on case insensitivity
- [org.ahocorasick](https://github.com/robert-bor/aho-corasick) (Java) by Robert Bor
- [daachorse](https://github.com/daac-tools/daachorse) (Rust) — a double-array Aho-Corasick
  automaton ("Engineering faster double-array Aho-Corasick automata", Kanda et al., 2023), itself
  the latest step in the double-array trie lineage (Aoe 1989 → ChaSen → MeCab/Darts →
  darts-clone). A future version of this library may adopt the double-array layout.

## License

[MIT License](LICENSE)
