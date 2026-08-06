package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Surrogate pairs, the ZWJ and combining characters are written as \uXXXX escapes (or built at
// runtime) so the tested code points are unambiguous in the source; the Kotlin/JS compiler can
// also mangle lone-surrogate string literals when emitting JS.
class UnicodeTest {
    @Test
    fun surrogatePairWordsMatchWithCharRanges() {
        val emoji = "😀" // 😀 U+1F600
        val ac = AhoCorasick(emoji)
        val text = "a" + emoji + "b"
        val match = ac.findAll(text).single()
        assertEquals(1..2, match.range) // two UTF-16 chars
        assertEquals(emoji, text.substring(match.range))
    }

    @Test
    fun surrogatePairIsNotMatchedByItsHalves() {
        val emoji = "😀"
        val loneHigh = Char(0xD83D).toString()
        val halfDictionary = AhoCorasick(loneHigh)
        assertFalse(halfDictionary.containsAny("a" + emoji + "b"))
        val pairDictionary = AhoCorasick(emoji)
        assertFalse(pairDictionary.containsAny("a" + loneHigh + "b"))
    }

    @Test
    fun loneSurrogateInTextDoesNotCrash() {
        val loneHigh = Char(0xD83D).toString()
        val loneLow = Char(0xDE00).toString()
        val ac = AhoCorasick("abc")
        val text = loneHigh + "abc" + loneLow + loneHigh
        assertEquals(listOf(Match("abc", 1..3)), ac.findAll(text))
        assertTrue(ac.containsAny(text))
    }

    @Test
    fun loneSurrogateWordMatchesItself() {
        val loneHigh = Char(0xD83D).toString()
        val ac = AhoCorasick(loneHigh)
        assertEquals(listOf(Match(loneHigh, 1..1)), ac.findAll("a" + loneHigh + "b"))
    }

    @Test
    fun wordEndingInLoneHighSurrogateDoesNotMatchPairedText() {
        // In the text the high surrogate combines with the following low surrogate into one code
        // point, so a word ending in the lone high surrogate must not match there.
        val loneHigh = Char(0xD83D).toString()
        val ac = AhoCorasick("a" + loneHigh)
        assertFalse(ac.containsAny("a😀"))
        assertTrue(ac.containsAny("a" + loneHigh + "b"))
    }

    @Test
    fun cjkMatching() {
        val ac = AhoCorasick("正規表現", "正規化", "トライ")
        val text = "正規表現と正規化とトライ木"
        assertEquals(
            listOf(Match("正規表現", 0..3), Match("正規化", 5..7), Match("トライ", 9..11)),
            ac.findOverlapping(text),
        )
        assertEquals(ac.findOverlapping(text), ac.findAll(text))
    }

    @Test
    fun combiningCharactersMatchPerCodePoint() {
        // Matching is per code point, not per grapheme cluster: "e" matches the base letter of a
        // decomposed "é" (e + U+0301 combining acute).
        val ac = AhoCorasick("e")
        assertEquals(listOf(Match("e", 0..0)), ac.findAll("e\u0301"))
    }

    @Test
    fun emojiZwjSequenceMatchesExactCodePoints() {
        // Family emoji: man U+1F468, ZWJ U+200D, woman U+1F469, ZWJ, boy U+1F466.
        val man = "👨"
        val family = "👨\u200D👩\u200D👦"
        val sequenceDictionary = AhoCorasick(family)
        assertEquals(listOf(Match(family, 0..7)), sequenceDictionary.findAll(family))
        // Code-point semantics: the man emoji alone is found inside the ZWJ sequence.
        val manDictionary = AhoCorasick(man)
        assertEquals(listOf(Match(man, 0..1)), manDictionary.findAll(family))
    }
}
