package dev.hsbrysk.ahocorasick

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Targets the failure modes specific to the double-array layout — false transitions into slots
 * of unrelated states, free-list window sliding, block-length rounding — which the {a, b}
 * alphabet of [StressTest] is too narrow to exercise (a wide alphabet gives `base xor c` a much
 * larger collision space).
 */
class DoubleArrayTest {
    @Test
    fun wideAlphabetMatchesAgreeWithBruteForceOracle() {
        val random = Random(11)
        val alphabet = ('a'..'t').toList()
        val words = buildList {
            repeat(200) {
                add(
                    buildString {
                        repeat(random.nextInt(1, 5)) { append(alphabet.random(random)) }
                    },
                )
            }
        }.distinct()
        val text = buildString {
            repeat(2_000) { append(alphabet.random(random)) }
        }
        val ac = AhoCorasick(words)
        val expected = bruteForceOverlapping(words, text)
        assertEquals(expected, ac.findOverlapping(text))
        assertEquals(greedyLeftmostLongest(expected), ac.findAll(text))
    }

    @Test
    fun everyTwoCharacterCombinationAgreesWithBruteForceOracle() {
        // Scans every pair of alphabet characters; pairs that form no word must not match via a
        // false transition (a slot whose check accidentally verifies), and pairs that do form a
        // word must all be found.
        val alphabet = ('a'..'p').toList()
        val random = Random(12)
        val words = buildList {
            repeat(40) { add("${alphabet.random(random)}${alphabet.random(random)}") }
            repeat(20) { add(alphabet.random(random).toString()) }
        }.distinct()
        val ac = AhoCorasick(words)
        for (first in alphabet) {
            for (second in alphabet) {
                val text = "$first$second"
                assertEquals(bruteForceOverlapping(words, text), ac.findOverlapping(text), "text=$text")
            }
        }
    }

    @Test
    fun thousandsOfStatesSurviveWindowSlidingAndBlockGrowth() {
        // 5_000 words build tens of thousands of states, so placement runs far past the 16-block
        // free-slot window; every word must still match itself, and a random text must agree with
        // the oracle.
        val random = Random(13)
        val alphabet = ('a'..'z') + ('0'..'9')
        val words = buildList {
            repeat(5_000) {
                add(
                    buildString {
                        repeat(random.nextInt(3, 9)) { append(alphabet.random(random)) }
                    },
                )
            }
        }.distinct()
        val ac = AhoCorasick(words)
        for (word in words) {
            assertTrue(
                ac.findOverlapping(word).contains(Match(word, word.indices)),
                "word=$word must match itself",
            )
        }
        val text = buildString {
            repeat(500) { append(alphabet.random(random)) }
        }
        assertEquals(bruteForceOverlapping(words, text), ac.findOverlapping(text))
    }

    @Test
    fun alphabetSizesAroundPowerOfTwoBlockBoundaries() {
        // The block length is the alphabet size rounded up to a power of two; sizes just below,
        // at and above a boundary exercise the rounding and the per-block slot masks.
        for (alphabetSize in intArrayOf(2, 3, 4, 5, 255, 256, 257)) {
            val chars = List(alphabetSize) { 'A' + it }
            val words = buildList {
                chars.forEach { add(it.toString()) }
                for (i in 0 until chars.size - 1) {
                    add("${chars[i]}${chars[i + 1]}")
                }
            }
            val text = buildString { chars.forEach(::append) }
            val ac = AhoCorasick(words)
            assertEquals(
                bruteForceOverlapping(words, text),
                ac.findOverlapping(text),
                "alphabetSize=$alphabetSize",
            )
        }
    }

    @Test
    fun nulAndExtremeCodePointsMatchExactly() {
        // Written as \uXXXX escapes so the tested code points are unambiguous in the source.
        val nulWord = "\u0000a"
        val maxBmpWord = "\uFFFF"
        val emojiWord = "\uD83D\uDE00b" // U+1F600 GRINNING FACE + 'b'
        val ac = AhoCorasick(nulWord, maxBmpWord, emojiWord)
        assertEquals(
            listOf(Match(nulWord, 0..1), Match(maxBmpWord, 2..2), Match(emojiWord, 3..5)),
            ac.findAll("\u0000a\uFFFF\uD83D\uDE00b"),
        )
        // U+1F601 is one past the dictionary's largest code point: the mapper table lookup is
        // out of range and must reset to the root, not read out of bounds.
        assertFalse(ac.containsAny("\uD83D\uDE01\uD83D\uDE01"))
        assertTrue(ac.containsAny("\uD83D\uDE01\uD83D\uDE00b"))
    }

    @Test
    fun emptyDictionaryMatchesNothing() {
        for (ac in listOf(AhoCorasick(), buildAhoCorasick {})) {
            assertEquals(emptyList(), ac.findAll("any text at all"))
            assertEquals(emptyList(), ac.findOverlapping("any text at all"))
            assertFalse(ac.containsAny("any text at all"))
            assertEquals(emptyList(), ac.findAll(""))
            assertFalse(ac.containsAny(""))
        }
    }

    private fun bruteForceOverlapping(
        words: List<String>,
        text: String,
    ): List<Match> = buildList {
        for (start in text.indices) {
            for (word in words) {
                if (text.regionMatches(start, word, 0, word.length)) {
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
