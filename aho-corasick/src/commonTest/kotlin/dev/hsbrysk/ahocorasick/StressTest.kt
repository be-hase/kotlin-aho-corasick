package dev.hsbrysk.ahocorasick

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StressTest {
    @Test
    fun longWordAndLongTextDoNotOverflowTheStack() {
        // Construction builds a 10_000-node-deep trie chain and the scan visits every position;
        // everything is iterative, so no platform stack limit is hit (deep recursion crashes the
        // process on Kotlin/Native).
        val word = "a".repeat(10_000)
        val ac = AhoCorasick(word)
        val text = "a".repeat(20_000)
        assertEquals(
            listOf(Match(word, 0..9_999), Match(word, 10_000..19_999)),
            ac.findAll(text),
        )
        assertEquals(10_001, ac.findOverlapping(text).size)
        assertTrue(ac.containsAny(text))
    }

    @Test
    fun matchesAgreeWithBruteForceOracle() {
        // All words of length 2..5 over {a, b} share heavy prefixes and suffixes, exercising the
        // failure and output links; results are cross-checked against a naive indexOf-style scan.
        val words = buildList {
            for (length in 2..5) {
                var count = 1
                repeat(length) { count *= 2 }
                for (bits in 0 until count) {
                    add(
                        buildString {
                            var value = bits
                            repeat(length) {
                                append(if (value and 1 == 0) 'a' else 'b')
                                value = value shr 1
                            }
                        },
                    )
                }
            }
        }
        val text = buildString {
            val random = Random(42)
            repeat(2_000) { append(if (random.nextBoolean()) 'a' else 'b') }
        }
        val ac = AhoCorasick(words)

        val expectedOverlapping = bruteForceOverlapping(words, text)
        assertEquals(expectedOverlapping, ac.findOverlapping(text))

        val expectedAll = greedyLeftmostLongest(expectedOverlapping)
        assertEquals(expectedAll, ac.findAll(text))
    }

    @Test
    fun foldedMatchesAgreeWithBruteForceOracle() {
        // Same differential idea under ASCII folding: mixed-case words over {a, B} against a
        // mixed-case text; the oracle folds with ASCII-only ignoreCase semantics.
        val random = Random(7)
        val words = buildList {
            repeat(50) {
                add(
                    buildString {
                        repeat(random.nextInt(2, 5)) {
                            append(if (random.nextBoolean()) (if (random.nextBoolean()) 'a' else 'A') else 'B')
                        }
                    },
                )
            }
        }.distinctBy { it.lowercase() }
        val text = buildString {
            repeat(1_000) { append(if (random.nextBoolean()) (if (random.nextBoolean()) 'A' else 'b') else 'a') }
        }
        val ac = AhoCorasick(words, CaseFolding.ASCII)
        val expected = bruteForceOverlapping(words, text, ignoreCase = true)
        assertEquals(expected, ac.findOverlapping(text))
        assertEquals(greedyLeftmostLongest(expected), ac.findAll(text))
    }

    private fun bruteForceOverlapping(
        words: List<String>,
        text: String,
        ignoreCase: Boolean = false,
    ): List<Match> = buildList {
        for (start in text.indices) {
            for (word in words) {
                if (text.regionMatches(start, word, 0, word.length, ignoreCase = ignoreCase)) {
                    add(Match(word, start until start + word.length))
                }
            }
        }
    }.sortedWith(compareBy({ it.range.first }, { it.range.last }))

    private fun greedyLeftmostLongest(overlapping: List<Match>): List<Match> = buildList {
        var nextStart = 0
        for (match in overlapping.sortedWith(compareBy({ it.range.first }, { -it.range.last }))) {
            if (match.range.first >= nextStart) {
                add(match)
                nextStart = match.range.last + 1
            }
        }
    }
}
