package dev.hsbrysk.ahocorasick

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rare-character prefilter is invisible in the public API, so these tests pin down its
 * observable property — results identical to the plain automaton scan — on the workloads that
 * exercise its code paths: sparse texts (jumps taken), dense texts (runtime self-disable),
 * dictionaries too diverse for it (build-time disable), jump targets inside surrogate pairs, and
 * case-folding preimages (`K` U+212A etc.) that a naive `indexOf` of the folded character would
 * miss.
 */
class PrefilterTest {
    @Test
    fun sparseTextMatchesAtEveryPlantedPosition() {
        // Filler has no 'k'/'q'; every word does, so scans jump. Words are planted at the very
        // start, the very end, back-to-back, and mid-text.
        val words = listOf("kite", "quest", "akaso")
        val ac = AhoCorasick(words)
        val filler = "aabb ccdd eeff gghh iijj ".repeat(40)
        val text = "kite" + filler + "quest" + filler + "kitequest" + filler + "akaso" +
            filler + "kite"
        val expected = bruteForceOverlapping(words, text)
        assertTrue(expected.size >= 6)
        assertEquals(expected, ac.findOverlapping(text))
        assertEquals(greedyLeftmostLongest(expected), ac.findAll(text))
        assertTrue(ac.containsAny(text))
        assertFalse(ac.containsAny(filler))
    }

    @Test
    fun sparseFuzzAgreesWithBruteForceOracle() {
        val random = Random(20260807)
        repeat(200) {
            val words = List(random.nextInt(1, 12)) { randomWord(random) }.distinct()
            val text = randomText(random)
            val ac = AhoCorasick(words)
            val expected = bruteForceOverlapping(words, text)
            assertEquals(expected, ac.findOverlapping(text))
            assertEquals(greedyLeftmostLongest(expected), ac.findAll(text))
            assertEquals(expected.isNotEmpty(), ac.containsAny(text))
        }
    }

    @Test
    fun asciiFoldedSparseFuzzAgreesWithBruteForceOracle() {
        val random = Random(7_2026)
        repeat(200) {
            val words = List(random.nextInt(1, 12)) { randomWord(random, mixedCase = true) }
                .distinctBy { it.lowercase() }
            val text = randomText(random, mixedCase = true)
            val ac = AhoCorasick(words, CaseFolding.ASCII)
            val expected = bruteForceOverlapping(words, text, ignoreCase = true)
            assertEquals(expected, ac.findOverlapping(text))
            assertEquals(greedyLeftmostLongest(expected), ac.findAll(text))
            assertEquals(expected.isNotEmpty(), ac.containsAny(text))
        }
    }

    @Test
    fun jumpTargetInsideSurrogatePairStaysCodePointExact() {
        // A lone low surrogate as the only word makes the rewind 0, so the jump target is the
        // candidate itself — the low half of a real pair. Without the pair-boundary adjustment
        // the scan would start mid-pair, read a lone low surrogate and report a false match.
        val loneLow = Char(0xDE00).toString()
        val pair = Char(0xD83D).toString() + Char(0xDE00).toString()
        val ac = AhoCorasick(loneLow)

        val pairOnly = "a".repeat(100) + pair + "a".repeat(20)
        assertEquals(emptyList(), ac.findAll(pairOnly))
        assertEquals(emptyList(), ac.findOverlapping(pairOnly))
        assertFalse(ac.containsAny(pairOnly))

        // A genuine lone low surrogate later in the text must still be found.
        val withLone = "a".repeat(100) + pair + "a".repeat(10) + loneLow + "a".repeat(10)
        assertEquals(listOf(Match(loneLow, 112..112)), ac.findAll(withLone))
        assertTrue(ac.containsAny(withLone))
    }

    @Test
    fun surrogatePairWordIsFoundAfterAJump() {
        val pair = Char(0xD83D).toString() + Char(0xDE00).toString()
        val word = pair + "go"
        val ac = AhoCorasick(word)
        val text = "filler text without matches ".repeat(30) + word + " tail"
        assertEquals(listOf(Match(word, 840..843)), ac.findAll(text))
        assertTrue(ac.containsAny(text))
    }

    @Test
    fun unicodeFoldingPreimagesAreSearched() {
        // The rare character of "kelvin" is 'k'; the text spells it with the Kelvin sign
        // U+212A, which only a preimage-aware candidate search can find.
        val kelvin = AhoCorasick(listOf("kelvin"), CaseFolding.UNICODE_SIMPLE)
        val text = "aeiou ".repeat(60) + "KELVIN degrees"
        assertEquals(listOf(Match("kelvin", 360..365)), kelvin.findAll(text))
        assertTrue(kelvin.containsAny(text))

        // Ångström sign U+212B folds to 'å' (U+00E5).
        val angstrom = AhoCorasick(listOf("ångström"), CaseFolding.UNICODE_SIMPLE)
        val text2 = "aeiou ".repeat(60) + "ÅNGSTRÖM units"
        assertEquals(listOf(Match("ångström", 360..367)), angstrom.findAll(text2))

        // Dotted capital I U+0130 folds to plain 'i'; with 'i' as the word's only character it
        // is necessarily the chosen rare character, so the candidate search must go through the
        // U+0130 preimage (the filler contains no i/I).
        val dotted = AhoCorasick(listOf("iii"), CaseFolding.UNICODE_SIMPLE)
        val text3 = "aeou ".repeat(80) + "İİİ"
        assertEquals(listOf(Match("iii", 400..402)), dotted.findAll(text3))
    }

    @Test
    fun denseCandidatesDisableThePrefilterMidScanWithoutLosingMatches() {
        // The first 400 characters are nothing but the rare character 'k', so the prefilter
        // never skips and turns itself off; the match after that region must still be found by
        // the resumed plain loop.
        val ac = AhoCorasick("kite")
        val text = "k".repeat(400) + "aa".repeat(100) + "kite" + "aa".repeat(10)
        assertEquals(listOf(Match("kite", 600..603)), ac.findAll(text))
        assertTrue(ac.containsAny(text))
    }

    @Test
    fun denseMatchesEverywhereStayCorrect() {
        val words = listOf("ab", "ba")
        val ac = AhoCorasick(words)
        val text = "ab".repeat(2_000)
        val overlapping = ac.findOverlapping(text)
        assertEquals(2_000 + 1_999, overlapping.size)
        assertEquals(Match("ab", 0..1), overlapping.first())
        assertEquals(2_000, ac.findAll(text).size)
        assertTrue(ac.containsAny(text))
    }

    @Test
    fun diverseDictionaryDisablesThePrefilterAtBuildTime() {
        // 26 words with 26 distinct rare characters blow the required-set cap; everything must
        // still match through the plain loop.
        val words = ('a'..'z').map { "$it$it" }
        val ac = AhoCorasick(words)
        val text = "aa bb zz qq mm"
        assertEquals(
            listOf(Match("aa", 0..1), Match("bb", 3..4), Match("zz", 6..7), Match("qq", 9..10), Match("mm", 12..13)),
            ac.findAll(text),
        )
    }

    @Test
    fun matchStraddlingTheRewindWindowStartIsStillFound() {
        // "kite" is preceded by a long run the automaton must enter *before* the jump target
        // would suggest: a word whose rare character sits at its very end. The rewind of
        // maxWordLength - 1 must be enough to catch the match that starts maxWordLength - 1
        // before the candidate.
        val words = listOf("aaaaaaak")
        val ac = AhoCorasick(words)
        val text = "bbbb ".repeat(50) + "aaaaaaak" + " tail"
        assertEquals(listOf(Match("aaaaaaak", 250..257)), ac.findAll(text))
    }

    @Test
    fun prefilterActuallyEngagesAndJumps() {
        // Guards against a regression that silently disables the prefilter everywhere (results
        // would stay correct, so only a direct probe of the internals can catch it).
        val prefilter = Prefilter.build(listOf("kite"), CaseFolding.NONE)
        assertNotNull(prefilter)
        // Candidate 'k' at 100, rewind = maxWordLength - 1 = 3.
        assertEquals(97, prefilter.newCursor().advance("a".repeat(100) + "kite", 0))
        // No candidate at all: the whole text is skipped. (A cursor caches text-derived state,
        // so each text gets a fresh one — same contract as the scan loops.)
        assertEquals(50, prefilter.newCursor().advance("a".repeat(50), 0))

        // Build-time refusal for a dictionary with too many distinct rare characters.
        assertNull(Prefilter.build(('a'..'z').map { "$it$it" }, CaseFolding.NONE))
    }

    private fun randomWord(
        random: Random,
        mixedCase: Boolean = false,
    ): String = buildString {
        repeat(random.nextInt(1, 6)) {
            append(randomChar(random, wordAlphabet = true, mixedCase = mixedCase))
        }
    }

    private fun randomText(
        random: Random,
        mixedCase: Boolean = false,
    ): String = buildString {
        repeat(random.nextInt(50, 400)) {
            append(randomChar(random, wordAlphabet = false, mixedCase = mixedCase))
        }
    }

    /** 'z' and 'q' are rare in texts (~10%) but common in words, so prefilters engage. */
    private fun randomChar(
        random: Random,
        wordAlphabet: Boolean,
        mixedCase: Boolean,
    ): Char {
        val roll = random.nextInt(10)
        val ch = when {
            wordAlphabet -> if (roll < 4) {
                'z'
            } else if (roll < 5) {
                'q'
            } else if (roll < 8) {
                'a'
            } else {
                'b'
            }
            else -> if (roll < 1) {
                'z'
            } else if (roll < 2) {
                'q'
            } else if (roll < 6) {
                'a'
            } else {
                'b'
            }
        }
        return if (mixedCase && random.nextBoolean()) ch.uppercaseChar() else ch
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
