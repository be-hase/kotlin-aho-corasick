# Benchmark Results

Comparison of scanning a text for a keyword list three ways: a naive `word1|word2|...` regex
alternation, the trie-optimized regex produced by
[kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie), and `AhoCorasick.findAll`
([kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark)). Raw JSON reports live in
[`results/`](results/).

## Environment

- Date: 2026-08-06
- Machine: Apple M4, 32 GB RAM, macOS 26.6
- JDK: Eclipse Adoptium (Temurin) 17.0.17; Node 24.10.0 for js/wasmJs
- Kotlin: 2.4.10, kotlinx-benchmark 0.4.17 (JMH 1.37 on JVM), regexp-trie 0.0.1
- Config: 5 warmups + 5 iterations × 1 s, average time (ms/op), lower is better

## Setup

Deterministic (fixed seed): a vocabulary of syllable-based pseudo-words (2–5 syllables, so words
naturally share prefixes like real dictionaries), of which `wordCount` words become the keyword
list. The scanned text is 1,000 vocabulary words joined with spaces. `findAll` match counting is
measured; all three contenders report leftmost-longest non-overlapping matches (the naive
alternation is built longest-first), so they do the same work. See
[`AhoCorasickBenchmark.kt`](src/commonMain/kotlin/dev/hsbrysk/ahocorasick/benchmark/AhoCorasickBenchmark.kt).

## Results

### findAll: naive alternation vs regexp-trie vs AhoCorasick (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick | AC vs naive | AC vs regexp-trie |
|---|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 1.066 ± 0.021 | 0.205 ± 0.001 | 0.069 ± 0.001 | **15×** | **3.0×** |
| jvm | 1000 | 9.368 ± 0.066 | 0.375 ± 0.016 | 0.116 ± 0.003 | **81×** | **3.2×** |
| jvm | 10000 | 116.887 ± 16.254 | 0.913 ± 0.011 | 0.195 ± 0.008 | **599×** | **4.7×** |
| js (Node) | 100 | 0.024 ± 0.000 | 0.023 ± 0.000 | 0.250 ± 0.003 | 0.10× | 0.09× |
| js (Node) | 1000 | 0.032 ± 0.000 | 0.032 ± 0.000 | 0.422 ± 0.007 | 0.08× | 0.08× |
| js (Node) | 10000 | 0.143 ± 0.001 | 0.111 ± 0.001 | 0.672 ± 0.008 | 0.21× | 0.16× |
| wasmJs (Node) | 100 | 5.617 ± 0.026 | 0.462 ± 0.002 | 0.168 ± 0.001 | **33×** | **2.7×** |
| wasmJs (Node) | 1000 | 71.040 ± 0.361 | 0.805 ± 0.002 | 0.274 ± 0.001 | **259×** | **2.9×** |
| wasmJs (Node) | 10000 | 432.732 ± 1.644 | 2.333 ± 0.006 | 0.536 ± 0.003 | **807×** | **4.4×** |
| macosArm64 | 100 | 4.120 ± 0.007 | 0.547 ± 0.001 | 0.187 ± 0.000 | **22×** | **2.9×** |
| macosArm64 | 1000 | 39.395 ± 0.213 | 1.023 ± 0.011 | 0.291 ± 0.004 | **135×** | **3.5×** |
| macosArm64 | 10000 | 308.771 ± 1.025 | 7.181 ± 0.079 | 0.698 ± 0.003 | **442×** | **10×** |

### Automaton build cost: `AhoCorasick(words)` (ms/op)

| Target | wordCount = 100 | wordCount = 1000 | wordCount = 10000 |
|---|---:|---:|---:|
| jvm | 0.019 ± 0.001 | 0.235 ± 0.008 | 3.937 ± 0.130 |
| js (Node) | 0.075 ± 0.001 | 1.007 ± 0.003 | 16.069 ± 0.067 |
| wasmJs (Node) | 0.040 ± 0.000 | 1.153 ± 0.019 | 19.435 ± 0.132 |
| macosArm64 | 0.062 ± 0.000 | 0.817 ± 0.009 | 14.708 ± 0.391 |

## Observations

- **Aho-Corasick's scan time is essentially flat in the dictionary size** (JVM: 0.069 → 0.195
  ms/op for 100 → 10,000 words), which is the whole point of the algorithm: cost is text length +
  matches, not words. The naive alternation degrades linearly with the word count, so the gap
  explodes — **~600× on the JVM, ~800× on Wasm, ~440× on Kotlin/Native at 10,000 words**.
- Against the trie-optimized regex from kotlin-regexp-trie, Aho-Corasick is a further **3–5×
  faster on the JVM and Wasm, up to ~10× on Kotlin/Native at 10,000 words** — the regex still
  re-walks the pattern at every text position, while the automaton never rescans.
- **Kotlin/JS is the exception**: V8's Irregexp compiles regexes (including huge alternations) to
  optimized machine code with Boyer-Moore-style skipping, and it beats this pure-Kotlin automaton's
  per-code-point `HashMap` transitions at every dictionary size tested — even 10,000 words. On JS,
  prefer [kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie) for raw scan speed;
  Aho-Corasick still wins there on capability (overlapping matches, no regex pattern-size limits).
  A future double-array (`IntArray`-based) engine should close this gap.
- Building the automaton for 10,000 words costs 4–20 ms; build once and reuse the instance (it is
  immutable and thread-safe).

## Reproducing

```bash
# All benchmark targets runnable on the current host
./gradlew :benchmark:benchmark

# Individual targets
./gradlew :benchmark:jvmBenchmark :benchmark:jsBenchmark :benchmark:wasmJsBenchmark :benchmark:macosArm64Benchmark
```

JSON reports are written to `benchmark/build/reports/benchmarks/main/<timestamp>/`.
