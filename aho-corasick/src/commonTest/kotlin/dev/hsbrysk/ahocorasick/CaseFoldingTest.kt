package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Non-ASCII characters are written as \uXXXX escapes so the tested code points are unambiguous in
// the source (no normalization or encoding surprises).
class CaseFoldingTest {
    @Test
    fun noneIsCaseSensitive() {
        val ac = AhoCorasick(listOf("ABC"), CaseFolding.NONE)
        assertFalse(ac.containsAny("abc"))
        assertTrue(ac.containsAny("xABCx"))
    }

    @Test
    fun asciiFoldsOnlyAsciiLetters() {
        val ac = AhoCorasick(listOf("AbC"), CaseFolding.ASCII)
        assertEquals(listOf(Match("AbC", 2..4)), ac.findAll("xxabcxx"))
        assertEquals(listOf(Match("AbC", 2..4)), ac.findAll("xxABCxx"))
        // Non-ASCII letters are compared verbatim under ASCII folding.
        val nonAscii = AhoCorasick(listOf("Å"), CaseFolding.ASCII) // Å
        assertFalse(nonAscii.containsAny("å")) // å
        assertTrue(nonAscii.containsAny("Å"))
    }

    @Test
    fun asciiRangeIsExactlyAToZ() {
        // '@' (0x40) and '[' (0x5B) sit just outside A-Z; folding them would map to '`' and '{'.
        val ac = AhoCorasick(listOf("@", "["), CaseFolding.ASCII)
        assertFalse(ac.containsAny("`{"))
        assertEquals(listOf(Match("@", 0..0), Match("[", 1..1)), ac.findAll("@["))
    }

    @Test
    fun unicodeSimpleFoldsBmp() {
        val ac = AhoCorasick(listOf("über"), CaseFolding.UNICODE_SIMPLE) // über
        assertEquals(listOf(Match("über", 0..3)), ac.findAll("ÜBER")) // ÜBER
        val sigma = AhoCorasick(listOf("Σ"), CaseFolding.UNICODE_SIMPLE) // Σ
        assertTrue(sigma.containsAny("σ")) // σ
    }

    @Test
    fun finalSigmaDoesNotFoldToSigma() {
        // Unicode simple *lowercase* keeps final sigma as-is, unlike simple case folding (ς→σ)
        // and unlike what regex IGNORE_CASE engines do. Documented on CaseFolding.UNICODE_SIMPLE.
        val finalSigma = AhoCorasick(listOf("ς"), CaseFolding.UNICODE_SIMPLE) // ς
        assertFalse(finalSigma.containsAny("σ")) // σ
        assertTrue(finalSigma.containsAny("ς"))
        val capitalSigma = AhoCorasick(listOf("Σ"), CaseFolding.UNICODE_SIMPLE) // Σ
        assertTrue(capitalSigma.containsAny("σ")) // σ
        assertFalse(capitalSigma.containsAny("ς")) // ς
    }

    @Test
    fun turkishAndKelvinAndAngstromPins() {
        // Cross-platform pins for the simple lowercase mapping; locale-independent by design.
        val dottedI = AhoCorasick(listOf("İ"), CaseFolding.UNICODE_SIMPLE) // İ
        assertTrue(dottedI.containsAny("i"))
        val dotlessI = AhoCorasick(listOf("ı"), CaseFolding.UNICODE_SIMPLE) // ı
        assertFalse(dotlessI.containsAny("i"))
        val plainI = AhoCorasick(listOf("I"), CaseFolding.UNICODE_SIMPLE)
        assertTrue(plainI.containsAny("i"))
        assertFalse(plainI.containsAny("ı")) // ı
        val kelvin = AhoCorasick(listOf("K"), CaseFolding.UNICODE_SIMPLE) // Kelvin sign
        assertTrue(kelvin.containsAny("k"))
        val angstrom = AhoCorasick(listOf("Å"), CaseFolding.UNICODE_SIMPLE) // Ångström sign
        assertTrue(angstrom.containsAny("å")) // å
    }

    @Test
    fun sharpSIsNotFullyFolded() {
        // Full case folding (ß→ss) changes string length and is intentionally unsupported.
        val sharpS = AhoCorasick(listOf("ß"), CaseFolding.UNICODE_SIMPLE) // ß
        assertFalse(sharpS.containsAny("ss"))
        assertFalse(sharpS.containsAny("SS"))
        // The capital ẞ has a 1:1 simple lowercase mapping to ß, so it does fold.
        val capitalSharpS = AhoCorasick(listOf("ẞ"), CaseFolding.UNICODE_SIMPLE) // ẞ
        assertTrue(capitalSharpS.containsAny("ß")) // ß
    }

    @Test
    fun supplementaryPlaneIsNotFolded() {
        // Deseret capital 𐐀 (U+10400) and small 𐐨 (U+10428); no code-point-level lowercase in
        // the common stdlib, so supplementary-plane letters are compared verbatim.
        val capital = "𐐀"
        val small = "𐐨"
        val deseret = AhoCorasick(listOf(capital), CaseFolding.UNICODE_SIMPLE)
        assertFalse(deseret.containsAny(small))
        assertEquals(listOf(Match(capital, 0..1)), deseret.findAll(capital))
    }

    @Test
    fun foldingWithSurrogateAdjacentText() {
        // Case folding must not disturb surrogate-pair stepping: ASCII letters fold while the
        // emoji around them are compared verbatim, and ranges stay exact.
        val emoji = "😀"
        val ac = AhoCorasick(listOf("aB"), CaseFolding.ASCII)
        val text = emoji + "ab" + emoji + "AB"
        assertEquals(
            listOf(Match("aB", 2..3), Match("aB", 6..7)),
            ac.findAll(text),
        )
    }

    @Test
    fun foldedMatchRangesPointIntoOriginalText() {
        val ac = AhoCorasick(listOf("foo"), CaseFolding.ASCII)
        val text = "xxFoOxx"
        val match = ac.findAll(text).single()
        assertEquals(2..4, match.range)
        assertEquals("FoO", text.substring(match.range))
        assertEquals("foo", match.word)
    }
}
