package dev.hsbrysk.ahocorasick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AhoCorasickTest {
    @Test
    fun emptyDictionaryFindsNothing() {
        val ac = AhoCorasick()
        assertEquals(emptyList(), ac.findAll("some text"))
        assertEquals(emptyList(), ac.findOverlapping("some text"))
        assertFalse(ac.containsAny("some text"))
    }

    @Test
    fun emptyTextFindsNothing() {
        val ac = AhoCorasick("foo", "bar")
        assertEquals(emptyList(), ac.findAll(""))
        assertEquals(emptyList(), ac.findOverlapping(""))
        assertFalse(ac.containsAny(""))
    }

    @Test
    fun emptyWordIsRejected() {
        assertFailsWith<IllegalArgumentException> { AhoCorasick("") }
        assertFailsWith<IllegalArgumentException> { AhoCorasick(listOf("foo", "")) }
        assertFailsWith<IllegalArgumentException> { buildAhoCorasick { +"" } }
    }

    @Test
    fun duplicateWordsAreDeduplicated() {
        val ac = AhoCorasick("foo", "foo")
        assertEquals(listOf(Match("foo", 0..2)), ac.findOverlapping("foo"))
        assertEquals("AhoCorasick(wordCount=1, caseFolding=NONE)", ac.toString())
    }

    @Test
    fun foldCollisionFirstRegisteredWins() {
        val ac = AhoCorasick(listOf("Abc", "aBC"), CaseFolding.ASCII)
        assertEquals(listOf(Match("Abc", 1..3)), ac.findAll("xabcx"))
        assertEquals("AhoCorasick(wordCount=1, caseFolding=ASCII)", ac.toString())
    }

    @Test
    fun dslBuilder() {
        val ac = buildAhoCorasick {
            caseFolding = CaseFolding.ASCII
            +"foo"
            add("bar")
            addAll(listOf("baz"))
        }
        assertEquals(
            listOf(Match("foo", 0..2), Match("bar", 4..6), Match("baz", 8..10)),
            ac.findAll("FOO BAR BAZ"),
        )
    }

    @Test
    fun builderFluentChaining() {
        val ac = AhoCorasick.Builder()
            .add("a")
            .addAll(listOf("b"))
            .build()
        assertEquals(listOf(Match("a", 0..0), Match("b", 1..1)), ac.findAll("ab"))
    }

    @Test
    fun varargAndIterableConstructorsAreEquivalent() {
        val text = "she sells sea shells"
        val fromVararg = AhoCorasick("she", "sea")
        val fromIterable = AhoCorasick(listOf("she", "sea"))
        assertEquals(fromVararg.findAll(text), fromIterable.findAll(text))
        assertEquals(fromVararg.findOverlapping(text), fromIterable.findOverlapping(text))
    }

    @Test
    fun toStringReportsWordCountAndCaseFolding() {
        assertEquals(
            "AhoCorasick(wordCount=2, caseFolding=UNICODE_SIMPLE)",
            AhoCorasick(listOf("foo", "bar"), CaseFolding.UNICODE_SIMPLE).toString(),
        )
    }

    @Test
    fun matchEqualityIsStructural() {
        assertEquals(Match("ab", 0..1), Match("ab", 0..1))
    }

    @Test
    fun instanceIsReusableAcrossManyScans() {
        // Instances are immutable after construction, so results must be identical on every scan.
        // A true multi-threaded test is not possible in commonTest with kotlin-test alone; thread
        // safety follows from immutability (all fields are assigned before the constructor
        // returns).
        val ac = AhoCorasick("ab", "bc")
        val expected = ac.findOverlapping("xabcx")
        repeat(1000) {
            assertEquals(expected, ac.findOverlapping("xabcx"))
        }
        assertTrue(ac.containsAny("xabcx"))
    }
}
