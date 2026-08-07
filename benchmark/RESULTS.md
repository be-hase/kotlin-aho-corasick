# Benchmark Results

Comparison of scanning a text for a keyword list four ways: a naive `word1|word2|...` regex
alternation, the trie-optimized regex produced by
[kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie), `AhoCorasick.findAll`,
and the previous-generation implementation of this library
([kotlinx-benchmark](https://github.com/Kotlin/kotlinx-benchmark)), in two scenarios: a
**dense** text (~50% keyword tokens) and a **sparse** one (~2% keyword tokens; see the
[sparse section](#sparse-match-scan-v3-rare-character-prefilter)). Raw JSON reports live in
[`results/`](results/).

Engine naming used throughout this document — these are internal engine generations, not
release version numbers:

- **v3** — the current engine: v2 plus a **rare-character prefilter** (`Prefilter.kt`) that
  locates each word's rarest character with native (SIMD) `String.indexOf` and skips text that
  cannot contain a match, automatically disabling itself when candidates are dense. This is what
  `AhoCorasick` ships with today. The prefilter only changes the *sparse-match* regime; on the
  dense benchmark below it turns itself off, so the v2 rows remain representative of v3
  (verified with a same-conditions A/B).
- **v2** — a compact double-array automaton (`IntArray`-based, daachorse charwise layout);
  still the scan core of v3.
- **v1** — the original engine: a `HashMap`-keyed node trie, shipped in release 0.0.1 and since
  replaced. It is kept frozen as
  [`LegacyAhoCorasick`](src/commonMain/kotlin/dev/hsbrysk/ahocorasick/benchmark/LegacyAhoCorasick.kt)
  in this (unpublished) benchmark module purely for comparison.

## Environment

- Date: 2026-08-07 (raw JSON: [`results/2026-08-07-v2-double-array/`](results/2026-08-07-v2-double-array/))
- Machine: Apple M4, 32 GB RAM, macOS 26.6
- JDK: Eclipse Adoptium (Temurin) 17.0.17; Node 24.10.0 for js/wasmJs
- Kotlin: 2.4.10, kotlinx-benchmark 0.4.17 (JMH 1.37 on JVM), regexp-trie 0.0.1
- Config: 5 warmups + 5 iterations × 1 s, average time (ms/op), lower is better

## Setup (dense scenario)

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

## Sparse-match scan (v3 rare-character prefilter)

The tables above are the **dense** scenario (~50% of text tokens are keywords), where the
prefilter deliberately stands down. This section measures the opposite — and in practice more
common — regime: a long text in which keywords are rare (~2% of tokens) and every keyword
carries a character (`k`) that never occurs in the surrounding text, the workload class a
rare-character prefilter targets (banned words with uncommon kanji, product codes, brand names
in prose, …). See
[`SparseMatchBenchmark.kt`](src/commonMain/kotlin/dev/hsbrysk/ahocorasick/benchmark/SparseMatchBenchmark.kt):
5,000 tokens (~45 KB, 5× the dense text), one planted keyword every 50 tokens, fixed seed.

- Date: 2026-08-07 (raw JSON: [`results/2026-08-07-v3-prefilter/`](results/2026-08-07-v3-prefilter/));
  same machine/toolchain as above. **Absolute times in this session run higher than the v2
  session above** (power/thermal state), so compare only within a session — the within-run
  ratios are the point.

### findAll on the sparse text (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick (v3) | v3 vs naive | v3 vs regexp-trie |
|---|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 20.966 ± 3.995 | 3.443 ± 0.353 | 0.028 ± 0.006 | **~750×** | **123×** |
| jvm | 1000 | 201.257 ± 36.580 | 4.482 ± 0.619 | 0.032 ± 0.011 | **~6,300×** | **140×** |
| jvm | 10000 | 2503.302 ± 323.267 | 7.226 ± 1.110 | 0.043 ± 0.010 | **~58,000×** | **168×** |
| js (Node) | 100 | 0.228 ± 0.022 | 0.214 ± 0.013 | 0.055 ± 0.004 | **4.1×** | **3.9×** |
| js (Node) | 1000 | 0.288 ± 0.011 | 0.284 ± 0.011 | 0.070 ± 0.003 | **4.1×** | **4.1×** |
| js (Node) | 10000 | 1.451 ± 0.034 | 1.394 ± 0.020 | 0.099 ± 0.002 | **14.7×** | **14.1×** |
| wasmJs (Node) | 100 | 128.682 ± 7.560 | 14.752 ± 3.647 | 0.124 ± 0.001 | **~1,000×** | **119×** |
| wasmJs (Node) | 1000 | 2175.338 ± 315.586 | 12.338 ± 0.810 | 0.151 ± 0.004 | **~14,000×** | **82×** |
| wasmJs (Node) | 10000 | 13005.530 ± 414.937 | 17.602 ± 0.477 | 0.183 ± 0.005 | **~71,000×** | **96×** |
| macosArm64 | 100 | 78.220 ± 2.760 | 10.109 ± 0.438 | 0.074 ± 0.003 | **~1,100×** | **137×** |
| macosArm64 | 1000 | 990.744 ± 195.354 | 13.223 ± 0.533 | 0.106 ± 0.004 | **~9,300×** | **125×** |
| macosArm64 | 10000 | 7915.360 ± 328.231 | 19.854 ± 0.993 | 0.182 ± 0.008 | **~43,000×** | **109×** |

### Observations

- **Kotlin/JS finally beats V8's compiled regex — by 4.1–14.7×.** This was the point of the
  prefilter: the candidate search runs on `String.indexOf`, which V8 executes as SIMD memchr,
  so the JS engine's raw-scan advantage no longer applies to the skipped stretches. (On the
  dense benchmark V8 regex still wins by ~3–4×; the prefilter cannot help where matches are
  everywhere, and stands down.)
- On the other targets the sparse scan is **82–168× faster than the regexp-trie regex** and
  three to five orders of magnitude faster than the naive alternation, while staying essentially
  flat in both dictionary size and (per byte) text length.
- Against the same engine with the prefilter disabled (same-conditions A/B on this workload),
  the prefilter itself is worth **~5–6× on the JVM and ~6–11× on JS**.
- The same session re-ran the dense benchmark as a regression check: at 10,000 words v3 scans
  0.213 (jvm) / 0.794 (js) / 0.835 (wasmJs) / 1.582 (macosArm64) ms/op vs v1's
  0.357 / 1.812 / 1.487 / 2.194 — the same v2-era ratios, i.e. the prefilter's auto-disable
  leaves dense scans unchanged (raw JSON in the same results directory).

## Linux x64 (GitHub Actions ubuntu-latest)

`linuxX64` cannot run on the Apple Silicon development machine, so it is measured on a GitHub
Actions runner via the manual `benchmark.yml` workflow (jvm/js/wasmJs are re-run on the same
runner for a self-consistent set). Absolute times are **not comparable** to the Apple M4 tables
above — shared runners are slower and noisier — but the within-run ratios are the point and they
confirm the same picture. This is the **v3-engine** run (2026-08-07, raw JSON:
[`results/2026-08-07-linux-ci-v3/`](results/2026-08-07-linux-ci-v3/)); the earlier v2 and
v1-only runs are kept in
[`results/2026-08-07-linux-ci-v2/`](results/2026-08-07-linux-ci-v2/) and
[`results/2026-08-07-linux-ci/`](results/2026-08-07-linux-ci/).

Environment: AMD EPYC 9V74 (4 vCPU), 16 GB RAM, ubuntu-latest (ubuntu24 image 20260720.247.2),
2026-08-07; otherwise identical config.

### findAll on the dense text (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick (v3) | v1 (HashMap trie) | v3 vs naive | v3 vs regexp-trie | v3 vs v1 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 1.923 ± 0.018 | 0.443 ± 0.024 | 0.055 ± 0.000 | 0.102 ± 0.001 | **35×** | **8.1×** | **1.9×** |
| jvm | 1000 | 18.707 ± 0.079 | 0.933 ± 0.141 | 0.094 ± 0.001 | 0.173 ± 0.001 | **199×** | **9.9×** | **1.8×** |
| jvm | 10000 | 195.718 ± 1.682 | 1.956 ± 0.130 | 0.206 ± 0.010 | 0.324 ± 0.004 | **950×** | **9.5×** | **1.6×** |
| js (Node) | 100 | 0.051 ± 0.001 | 0.050 ± 0.000 | 0.228 ± 0.065 | 0.462 ± 0.019 | 0.22× | 0.22× | **2.0×** |
| js (Node) | 1000 | 0.073 ± 0.001 | 0.076 ± 0.000 | 0.310 ± 0.001 | 0.902 ± 0.011 | 0.24× | 0.25× | **2.9×** |
| js (Node) | 10000 | 0.442 ± 0.000 | 0.304 ± 0.001 | 0.646 ± 0.022 | 1.868 ± 0.160 | 0.68× | 0.47× | **2.9×** |
| wasmJs (Node) | 100 | 13.442 ± 0.050 | 1.064 ± 0.002 | 0.204 ± 0.000 | 0.374 ± 0.004 | **66×** | **5.2×** | **1.8×** |
| wasmJs (Node) | 1000 | 137.547 ± 5.705 | 1.926 ± 0.003 | 0.332 ± 0.001 | 0.764 ± 0.006 | **414×** | **5.8×** | **2.3×** |
| wasmJs (Node) | 10000 | 1117.807 ± 0.733 | 5.221 ± 0.045 | 0.905 ± 0.002 | 1.657 ± 0.006 | **1235×** | **5.8×** | **1.8×** |
| linuxX64 | 100 | 8.990 ± 0.077 | 1.226 ± 0.003 | 0.223 ± 0.001 | 0.353 ± 0.002 | **40×** | **5.5×** | **1.6×** |
| linuxX64 | 1000 | 85.387 ± 0.082 | 2.207 ± 0.007 | 0.369 ± 0.001 | 0.599 ± 0.004 | **231×** | **6.0×** | **1.6×** |
| linuxX64 | 10000 | 683.829 ± 0.781 | 17.169 ± 0.287 | 1.318 ± 0.012 | 1.617 ± 0.027 | **519×** | **13×** | **1.2×** |

### findAll on the sparse text (ms/op)

| Target | wordCount | naive alternation | regexp-trie | AhoCorasick (v3) | v3 vs naive | v3 vs regexp-trie |
|---|---:|---:|---:|---:|---:|---:|
| jvm | 100 | 16.674 ± 0.157 | 3.558 ± 0.054 | 0.021 ± 0.001 | **~790×** | **169×** |
| jvm | 1000 | 172.527 ± 2.496 | 5.324 ± 0.476 | 0.024 ± 0.000 | **~7,200×** | **222×** |
| jvm | 10000 | 2126.160 ± 26.142 | 7.299 ± 0.204 | 0.031 ± 0.000 | **~69,000×** | **235×** |
| js (Node) | 100 | 0.281 ± 0.001 | 0.281 ± 0.000 | 0.042 ± 0.000 | **6.7×** | **6.7×** |
| js (Node) | 1000 | 0.395 ± 0.001 | 0.396 ± 0.007 | 0.056 ± 0.001 | **7.1×** | **7.1×** |
| js (Node) | 10000 | 1.988 ± 0.005 | 1.825 ± 0.009 | 0.081 ± 0.000 | **24.5×** | **22.5×** |
| wasmJs (Node) | 100 | 133.840 ± 0.091 | 12.088 ± 1.954 | 0.119 ± 0.000 | **~1,100×** | **102×** |
| wasmJs (Node) | 1000 | 1341.031 ± 1.544 | 11.884 ± 0.017 | 0.138 ± 0.001 | **~9,700×** | **86×** |
| wasmJs (Node) | 10000 | 13331.645 ± 15.371 | 15.936 ± 0.020 | 0.174 ± 0.000 | **~77,000×** | **92×** |
| linuxX64 | 100 | 79.550 ± 0.094 | 10.829 ± 0.111 | 0.063 ± 0.001 | **~1,300×** | **172×** |
| linuxX64 | 1000 | 802.172 ± 3.993 | 14.562 ± 0.166 | 0.083 ± 0.001 | **~9,700×** | **175×** |
| linuxX64 | 10000 | 8131.585 ± 23.351 | 18.476 ± 0.099 | 0.122 ± 0.001 | **~67,000×** | **151×** |

### Automaton build cost: `AhoCorasick(words)` (ms/op)

| Target | engine | wordCount = 100 | wordCount = 1000 | wordCount = 10000 |
|---|---|---:|---:|---:|
| jvm | v3 double-array + prefilter | 0.049 ± 0.001 | 0.673 ± 0.043 | 8.279 ± 0.070 |
| jvm | v1 HashMap trie | 0.032 ± 0.000 | 0.453 ± 0.005 | 5.546 ± 0.201 |
| js (Node) | v3 double-array + prefilter | 0.231 ± 0.006 | 1.649 ± 0.210 | 20.720 ± 2.523 |
| js (Node) | v1 HashMap trie | 0.138 ± 0.001 | 1.730 ± 0.027 | 37.159 ± 1.377 |
| wasmJs (Node) | v3 double-array + prefilter | 0.091 ± 0.000 | 1.167 ± 0.001 | 16.958 ± 0.406 |
| wasmJs (Node) | v1 HashMap trie | 0.098 ± 0.001 | 3.542 ± 0.115 | 56.420 ± 13.227 |
| linuxX64 | v3 double-array + prefilter | 0.100 ± 0.000 | 1.254 ± 0.040 | 17.747 ± 0.207 |
| linuxX64 | v1 HashMap trie | 0.109 ± 0.000 | 1.636 ± 0.006 | 29.821 ± 1.711 |

The shared runner confirms the Apple M4 picture on both scenarios: dense scans beat v1
everywhere (1.6–1.9× on the JVM, 2.0–2.9× on JS, 1.8–2.3× on Wasm, 1.2–1.6× on Linux/Native)
and the naive alternation by **~520–1240× at 10,000 words**, with dense JS still V8's win
(0.22–0.68×) — unchanged from the v2 run, i.e. the prefilter costs dense scans nothing. On the
sparse text the prefilter beats V8's regex by **6.7–24.5× on JS** and the regexp-trie regex by
**86–235×** elsewhere. As in the v2 run, this runner's JVM builds v3 ~1.5× slower than v1
(8.3 vs 5.5 ms at 10,000 words); scan-side wins are unaffected.

## Reproducing

```bash
# All benchmark targets runnable on the current host
./gradlew :benchmark:benchmark

# Individual targets
./gradlew :benchmark:jvmBenchmark :benchmark:jsBenchmark :benchmark:wasmJsBenchmark :benchmark:macosArm64Benchmark

# Focused loops: only the sparse scenario, or only the dense AhoCorasick scan/build
./gradlew :benchmark:jvmSparseBenchmark :benchmark:jsSparseBenchmark
./gradlew :benchmark:jvmDenseBenchmark :benchmark:jsDenseBenchmark
```

JSON reports are written to `benchmark/build/reports/benchmarks/main/<timestamp>/`.

The Linux x64 numbers come from the manual `benchmark.yml` workflow
(`gh workflow run benchmark.yml`), which uploads the JSON reports plus runner info as an
artifact.
