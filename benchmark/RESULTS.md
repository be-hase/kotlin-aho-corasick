# Benchmark Results

Comparison of scanning a text for a keyword list four ways: a naive `word1|word2|...` regex
alternation, the trie-optimized regex produced by
[kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie), `AhoCorasick.findAll`
(the double-array engine, v2), and the frozen v1 HashMap-trie implementation
(`LegacyAhoCorasick`) it replaced ([kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark)).
Raw JSON reports live in [`results/`](results/).

## Environment

- Date: 2026-08-07 (raw JSON: [`results/2026-08-07-v2-double-array/`](results/2026-08-07-v2-double-array/))
- Machine: Apple M4, 32 GB RAM, macOS 26.6
- JDK: Eclipse Adoptium (Temurin) 17.0.17; Node 24.10.0 for js/wasmJs
- Kotlin: 2.4.10, kotlinx-benchmark 0.4.17 (JMH 1.37 on JVM), regexp-trie 0.0.1
- Config: 5 warmups + 5 iterations × 1 s, average time (ms/op), lower is better

## Setup

Deterministic (fixed seed): a vocabulary of syllable-based pseudo-words (2–5 syllables, so words
naturally share prefixes like real dictionaries), of which `wordCount` words become the keyword
list. The scanned text is 1,000 vocabulary words joined with spaces. `findAll` match counting is
measured; all contenders report leftmost-longest non-overlapping matches (the naive alternation
is built longest-first), so they do the same work. See
[`AhoCorasickBenchmark.kt`](src/commonMain/kotlin/dev/hsbrysk/ahocorasick/benchmark/AhoCorasickBenchmark.kt).

## Results

### findAll: naive alternation vs regexp-trie vs AhoCorasick v2 vs v1 (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick (v2) | v1 (HashMap trie) | v2 vs naive | v2 vs regexp-trie | v2 vs v1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 1.015 ± 0.003 | 0.213 ± 0.002 | 0.029 ± 0.000 | 0.076 ± 0.001 | **35×** | **7.3×** | **2.6×** |
| jvm | 1000 | 10.023 ± 0.096 | 0.366 ± 0.002 | 0.046 ± 0.001 | 0.133 ± 0.003 | **218×** | **8.0×** | **2.9×** |
| jvm | 10000 | 123.112 ± 15.820 | 0.900 ± 0.008 | 0.100 ± 0.002 | 0.191 ± 0.005 | **1231×** | **9.0×** | **1.9×** |
| js (Node) | 100 | 0.023 ± 0.000 | 0.023 ± 0.000 | 0.060 ± 0.001 | 0.237 ± 0.005 | 0.38× | 0.38× | **4.0×** |
| js (Node) | 1000 | 0.032 ± 0.000 | 0.032 ± 0.000 | 0.116 ± 0.001 | 0.416 ± 0.017 | 0.28× | 0.28× | **3.6×** |
| js (Node) | 10000 | 0.143 ± 0.001 | 0.111 ± 0.001 | 0.251 ± 0.001 | 0.639 ± 0.024 | 0.57× | 0.44× | **2.5×** |
| wasmJs (Node) | 100 | 5.515 ± 0.013 | 0.449 ± 0.001 | 0.093 ± 0.001 | 0.187 ± 0.017 | **59×** | **4.8×** | **2.0×** |
| wasmJs (Node) | 1000 | 55.667 ± 0.157 | 0.810 ± 0.002 | 0.141 ± 0.001 | 0.275 ± 0.001 | **395×** | **5.7×** | **2.0×** |
| wasmJs (Node) | 10000 | 442.703 ± 0.656 | 2.334 ± 0.006 | 0.340 ± 0.001 | 0.537 ± 0.003 | **1302×** | **6.9×** | **1.6×** |
| macosArm64 | 100 | 4.036 ± 0.015 | 0.543 ± 0.001 | 0.102 ± 0.000 | 0.187 ± 0.000 | **40×** | **5.3×** | **1.8×** |
| macosArm64 | 1000 | 38.765 ± 0.512 | 1.008 ± 0.002 | 0.186 ± 0.001 | 0.305 ± 0.030 | **208×** | **5.4×** | **1.6×** |
| macosArm64 | 10000 | 313.889 ± 27.642 | 7.050 ± 0.171 | 0.550 ± 0.005 | 0.689 ± 0.005 | **571×** | **13×** | **1.3×** |

### Automaton build cost: `AhoCorasick(words)` (ms/op)

| Target | engine | wordCount = 100 | wordCount = 1000 | wordCount = 10000 |
|---|---|---:|---:|---:|
| jvm | v2 double-array | 0.019 ± 0.000 | 0.235 ± 0.004 | 4.134 ± 0.213 |
| jvm | v1 HashMap trie | 0.018 ± 0.000 | 0.235 ± 0.007 | 3.858 ± 0.091 |
| js (Node) | v2 double-array | 0.057 ± 0.001 | 0.510 ± 0.001 | 7.667 ± 0.219 |
| js (Node) | v1 HashMap trie | 0.074 ± 0.000 | 0.869 ± 0.003 | 15.341 ± 0.054 |
| wasmJs (Node) | v2 double-array | 0.034 ± 0.000 | 0.347 ± 0.001 | 6.173 ± 0.045 |
| wasmJs (Node) | v1 HashMap trie | 0.051 ± 0.000 | 1.310 ± 0.010 | 21.398 ± 1.186 |
| macosArm64 | v2 double-array | 0.047 ± 0.000 | 0.538 ± 0.033 | 7.458 ± 0.048 |
| macosArm64 | v1 HashMap trie | 0.061 ± 0.000 | 0.812 ± 0.013 | 14.640 ± 0.132 |

## Observations

- **The double-array engine beats the v1 HashMap trie everywhere**: scans are **1.9–2.9× faster
  on the JVM, 2.5–4× on JS, 1.6–2× on Wasm and 1.3–1.8× on Kotlin/Native**. The per-code-point
  cost dropped from a boxed `HashMap` lookup to two `IntArray` reads (`base xor c`, then a check
  verification).
- **Scan time stays essentially flat in the dictionary size** (JVM: 0.029 → 0.100 ms/op for
  100 → 10,000 words) — the point of Aho-Corasick. The naive alternation degrades linearly, so
  the gap explodes: **~1200× on the JVM and Wasm, ~570× on Kotlin/Native at 10,000 words**.
  Against the trie-optimized regex from kotlin-regexp-trie the automaton is a further **~5–13×**.
- **Kotlin/JS remains the exception, but the gap collapsed**: v1 was ~5–13× slower than V8's
  Irregexp-compiled alternation; v2 is within **1.8–3.6×** (0.28–0.57×) of it. V8 still wins on
  raw scan speed — it compiles the regex to machine code with Boyer-Moore-style skipping — so on
  JS prefer [kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie) when scan speed
  is all that matters; Aho-Corasick wins on capability (overlapping matches, case-folding modes,
  no regex pattern-size limits) at a much smaller premium than before.
- **Construction got cheaper too on every non-JVM target** (at 10,000 words: JS ~2×, Wasm ~3.5×,
  Native ~2× faster; smaller dictionaries gain less), because the flat-array NFA + placement
  avoids the per-node `HashMap` allocations that dominated v1 builds. On the JVM, v2 build cost is within ~7% of v1 (4.1 vs 3.9 ms at 10,000 words).
  Build once and reuse the instance (it is immutable and thread-safe).

## Linux x64 (GitHub Actions ubuntu-latest)

> **Note:** this section was measured with the **v1 (HashMap trie) engine** on 2026-08-07,
> before the double-array rewrite; re-run `benchmark.yml` to refresh it. The cross-engine tables
> above are the current reference.

`linuxX64` cannot run on the Apple Silicon development machine, so it is measured on a GitHub
Actions runner via the manual `benchmark.yml` workflow (jvm/js/wasmJs are re-run on the same
runner for a self-consistent set). Absolute times are **not comparable** to the Apple M4 tables
above — shared runners are slower and noisier — but the within-run ratios are the point and they
confirm the same picture. Raw JSON: [`results/2026-08-07-linux-ci/`](results/2026-08-07-linux-ci/).

Environment: AMD EPYC 7763 (4 vCPU), 16 GB RAM, ubuntu-latest (ubuntu24 image 20260720.247.2),
2026-08-07; otherwise identical config.

### findAll (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick | AC vs naive | AC vs regexp-trie |
|---|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 1.978 ± 0.013 | 0.422 ± 0.009 | 0.106 ± 0.005 | **19×** | **4.0×** |
| jvm | 1000 | 19.407 ± 0.105 | 0.827 ± 0.027 | 0.201 ± 0.007 | **97×** | **4.1×** |
| jvm | 10000 | 208.303 ± 0.878 | 2.120 ± 0.030 | 0.379 ± 0.007 | **549×** | **5.6×** |
| js (Node) | 100 | 0.056 ± 0.000 | 0.057 ± 0.003 | 0.544 ± 0.004 | 0.10× | 0.10× |
| js (Node) | 1000 | 0.088 ± 0.000 | 0.091 ± 0.001 | 1.012 ± 0.013 | 0.09× | 0.09× |
| js (Node) | 10000 | 0.488 ± 0.003 | 0.361 ± 0.001 | 1.723 ± 0.019 | 0.28× | 0.21× |
| wasmJs (Node) | 100 | 13.326 ± 0.008 | 1.042 ± 0.003 | 0.335 ± 0.001 | **40×** | **3.1×** |
| wasmJs (Node) | 1000 | 135.484 ± 5.580 | 1.791 ± 0.034 | 0.671 ± 0.007 | **202×** | **2.7×** |
| wasmJs (Node) | 10000 | 1081.195 ± 1.579 | 4.870 ± 0.083 | 1.536 ± 0.078 | **704×** | **3.2×** |
| linuxX64 | 100 | 8.821 ± 0.026 | 1.269 ± 0.002 | 0.354 ± 0.002 | **25×** | **3.6×** |
| linuxX64 | 1000 | 84.319 ± 0.292 | 2.226 ± 0.007 | 0.606 ± 0.005 | **139×** | **3.7×** |
| linuxX64 | 10000 | 669.746 ± 0.573 | 15.742 ± 0.455 | 1.524 ± 0.016 | **440×** | **10×** |

### Automaton build cost: `AhoCorasick(words)` (ms/op)

| Target | wordCount = 100 | wordCount = 1000 | wordCount = 10000 |
|---|---:|---:|---:|
| jvm | 0.033 ± 0.000 | 0.456 ± 0.004 | 5.544 ± 0.400 |
| js (Node) | 0.222 ± 0.021 | 2.439 ± 0.104 | 46.372 ± 5.181 |
| wasmJs (Node) | 0.090 ± 0.003 | 3.290 ± 0.157 | 47.076 ± 10.590 |
| linuxX64 | 0.107 ± 0.000 | 1.655 ± 0.055 | 26.597 ± 0.319 |

Linux/Native behaves exactly like macOS/Native: Aho-Corasick beats the naive alternation by
**~440× at 10,000 words** and the trie-optimized regex by up to **~10×**; Kotlin/JS on Linux
still favors V8's regex engine at every size.

## Reproducing

```bash
# All benchmark targets runnable on the current host
./gradlew :benchmark:benchmark

# Individual targets
./gradlew :benchmark:jvmBenchmark :benchmark:jsBenchmark :benchmark:wasmJsBenchmark :benchmark:macosArm64Benchmark
```

JSON reports are written to `benchmark/build/reports/benchmarks/main/<timestamp>/`.

The Linux x64 numbers come from the manual `benchmark.yml` workflow
(`gh workflow run benchmark.yml`), which uploads the JSON reports plus runner info as an
artifact.
