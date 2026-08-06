package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchingTest {
    @Test
    fun singleWordSingleMatch() {
        val ac = AhoCorasick("abc")
        assertEquals(listOf(Match("abc", 2..4)), ac.findAll("xxabcxx"))
        assertEquals(listOf(Match("abc", 2..4)), ac.findOverlapping("xxabcxx"))
    }

    @Test
    fun multipleNonOverlappingOccurrences() {
        val ac = AhoCorasick("ab")
        val expected = listOf(Match("ab", 0..1), Match("ab", 3..4), Match("ab", 6..7))
        assertEquals(expected, ac.findAll("ab ab ab"))
        assertEquals(expected, ac.findOverlapping("ab ab ab"))
    }

    @Test
    fun overlappingSuffixMatches() {
        // The classic example from the Aho-Corasick paper.
        val ac = AhoCorasick("he", "she", "his", "hers")
        assertEquals(
            listOf(Match("she", 1..3), Match("he", 2..3), Match("hers", 2..5)),
            ac.findOverlapping("ushers"),
        )
    }

    @Test
    fun wordInsideAnotherWord() {
        val ac = AhoCorasick("a", "ab", "abc")
        assertEquals(
            listOf(Match("a", 0..0), Match("ab", 0..1), Match("abc", 0..2)),
            ac.findOverlapping("abc"),
        )
    }

    @Test
    fun adjacentMatchesAreNotMerged() {
        val ac = AhoCorasick("ab")
        val expected = listOf(Match("ab", 0..1), Match("ab", 2..3))
        assertEquals(expected, ac.findAll("abab"))
        assertEquals(expected, ac.findOverlapping("abab"))
    }

    @Test
    fun containsAnyIsTrueViaOutputLink() {
        // "bc" ends mid-path of "abcd", so the hit is only visible through the output/hasOutput
        // propagation, not via a terminal node.
        val ac = AhoCorasick("abcd", "bc")
        assertTrue(ac.containsAny("abc"))
    }

    @Test
    fun containsAnyOnMissReturnsFalse() {
        val ac = AhoCorasick("abcd", "bc")
        assertFalse(ac.containsAny("abd bd xyz"))
    }

    @Test
    fun findOverlappingOrderIsByStartThenEnd() {
        // Scan-time emission order is by end position (b before abc); the returned order must be
        // by start position.
        val ac = AhoCorasick("b", "abc")
        assertEquals(
            listOf(Match("abc", 0..2), Match("b", 1..1)),
            ac.findOverlapping("abc"),
        )
    }

    @Test
    fun resultsAreDeterministicRegardlessOfInsertionOrder() {
        val words = listOf("he", "she", "his", "hers", "sher", "er", "s")
        val text = "ushers and his herds"
        val expectedAll = AhoCorasick(words).findAll(text)
        val expectedOverlapping = AhoCorasick(words).findOverlapping(text)
        listOf(
            words.reversed(),
            words.sortedBy { it.length },
            words.sortedDescending(),
        ).forEach { shuffled ->
            assertEquals(expectedAll, AhoCorasick(shuffled).findAll(text))
            assertEquals(expectedOverlapping, AhoCorasick(shuffled).findOverlapping(text))
        }
    }

    @Test
    fun crossBranchOutputChain() {
        // Every word is a suffix of the previous one; when the scan passes the "abcd" interior
        // node its output chain must emit bcd/cd/d through failure links crossing between
        // branches, while the terminal node emits only "abcde".
        val ac = AhoCorasick("abcde", "bcd", "cd", "d")
        assertEquals(
            listOf(Match("abcde", 0..4), Match("bcd", 1..3), Match("cd", 2..3), Match("d", 3..3)),
            ac.findOverlapping("abcde"),
        )
        assertEquals(listOf(Match("abcde", 0..4)), ac.findAll("abcde"))
    }

    @Test
    fun repeatedCharacterOverlapCounts() {
        // Hand-computed on "aaaa": "a"×4 + "aa"×3 + "aaa"×2 = 9 overlapping occurrences.
        val ac = AhoCorasick("a", "aa", "aaa")
        assertEquals(9, ac.findOverlapping("aaaa").size)
        assertEquals(listOf(Match("aaa", 0..2), Match("a", 3..3)), ac.findAll("aaaa"))
    }

    @Test
    fun matchRangeSubstringIsTheWord() {
        val ac = AhoCorasick("hers", "she")
        val text = "ushers"
        ac.findOverlapping(text).forEach { match ->
            assertEquals(match.word, text.substring(match.range))
        }
    }
}
