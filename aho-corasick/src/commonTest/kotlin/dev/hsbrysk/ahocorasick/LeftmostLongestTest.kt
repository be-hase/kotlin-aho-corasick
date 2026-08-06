package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals

class LeftmostLongestTest {
    @Test
    fun longestWinsAtSameStart() {
        val ac = AhoCorasick("ab", "abc", "bcd")
        assertEquals(listOf(Match("abc", 0..2)), ac.findAll("abcd"))
    }

    @Test
    fun leftmostBeatsLonger() {
        val ac = AhoCorasick("ab", "bcd")
        assertEquals(listOf(Match("ab", 0..1)), ac.findAll("abcd"))
    }

    @Test
    fun longerMatchEmittedAfterShorterStillWins() {
        // During the scan "bc" is emitted (end index 2) before "abcd" (end index 3), but "abcd"
        // starts earlier — the case that breaks a naive streaming implementation.
        val ac = AhoCorasick("bc", "abcd")
        assertEquals(listOf(Match("abcd", 0..3)), ac.findAll("abcd"))
    }

    @Test
    fun greedyContinuesAfterAcceptedMatch() {
        val ac = AhoCorasick("ab", "cd")
        assertEquals(listOf(Match("ab", 0..1), Match("cd", 2..3)), ac.findAll("abcd"))
    }

    @Test
    fun droppedOverlapDoesNotBlockFollowingMatch() {
        // "cde" overlaps the accepted "abc" and is dropped; "e" must still be reported.
        val ac = AhoCorasick("abc", "cde", "e")
        assertEquals(listOf(Match("abc", 0..2), Match("e", 4..4)), ac.findAll("abcde"))
    }

    @Test
    fun identicalToOverlappingWhenNoOverlaps() {
        val ac = AhoCorasick("foo", "bar")
        val text = "a foo and a bar"
        assertEquals(ac.findOverlapping(text), ac.findAll(text))
    }
}
