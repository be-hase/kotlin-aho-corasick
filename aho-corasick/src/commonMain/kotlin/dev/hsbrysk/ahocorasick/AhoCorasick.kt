package dev.hsbrysk.ahocorasick

/**
 * Matches a fixed dictionary of words against texts in a single pass with the
 * [Aho-Corasick algorithm](https://en.wikipedia.org/wiki/Aho%E2%80%93Corasick_algorithm)
 * (a trie with failure and output links), so the scan cost is proportional to the text length
 * plus the number of occurrences (all of them, including overlapping ones) — independent of the
 * dictionary size.
 *
 * The automaton is fully built in the constructor and instances are deeply immutable afterwards,
 * so a single instance can be shared and used concurrently from multiple threads. Words cannot be
 * added to an existing instance; build a new one instead.
 *
 * Matching is exact per Unicode code point: words containing surrogate pairs (e.g. emoji) are
 * handled correctly on all platforms, and half of a surrogate pair never matches the pair.
 * Reported [Match.range]s are `Char` (UTF-16) indices into the original text. Matching is not
 * grapheme-cluster aware: a word can match inside a combining sequence (e.g. `"e"` matches the
 * base letter of `"é"`).
 *
 * Empty words are rejected with [IllegalArgumentException]. Registering the same word twice — or,
 * under a [CaseFolding] mode, two words that fold to the same key — is a no-op for the later word;
 * matches always report the first registered spelling.
 *
 * Results are deterministic: match positions and counts depend only on the set of registered
 * words, never on insertion order. The one insertion-order-dependent detail is which spelling
 * [Match.word] reports when several words collide under case folding (first registered wins, as
 * above).
 */
public class AhoCorasick public constructor(
    words: Iterable<String>,
    private val caseFolding: CaseFolding = CaseFolding.NONE,
) {
    /**
     * Creates an [AhoCorasick] matching [words] case-sensitively. Use the [Iterable] constructor
     * or [buildAhoCorasick] to select a [CaseFolding] mode.
     */
    public constructor(vararg words: String) : this(words.asIterable())

    private val automaton: CompactAutomaton
    private val storedWords: List<String>
    private val storedWordLengths: IntArray

    init {
        // Materialized because construction is two passes (code point frequencies, then the
        // trie); a single-pass Iterable must not be consumed twice.
        val built = CompactAutomaton.build(words.toList(), caseFolding)
        automaton = built.automaton
        storedWords = built.storedWords
        storedWordLengths = IntArray(storedWords.size) { storedWords[it].length }
    }

    /**
     * Returns the leftmost-longest non-overlapping matches (what `fgrep -o` reports), ordered by
     * position: candidates are considered by start index ascending — at the same start the longest
     * wins — and a candidate overlapping an already accepted match is dropped, even when it is
     * longer. For example, with the words `{ab, abc, bcd}` the text `"abcd"` yields only
     * `abc@0..2`, and with `{ab, bcd}` it yields only `ab@0..1`.
     */
    public fun findAll(text: String): List<Match> {
        val matches = collectMatches(text)
        matches.sortWith(compareBy({ it.range.first }, { -it.range.last }))
        val result = mutableListOf<Match>()
        var nextStart = 0
        for (match in matches) {
            if (match.range.first >= nextStart) {
                result.add(match)
                nextStart = match.range.last + 1
            }
        }
        return result
    }

    /**
     * Returns every occurrence of every word, including overlapping ones and words contained in
     * other words — the raw Aho-Corasick output. For example, with the words `{he, she, hers}` the
     * text `"ushers"` yields `she@1..3`, `he@2..3` and `hers@2..5`. Matches are ordered by start
     * index, then by end index.
     */
    public fun findOverlapping(text: String): List<Match> {
        val matches = collectMatches(text)
        matches.sortWith(compareBy({ it.range.first }, { it.range.last }))
        return matches
    }

    /**
     * Returns `true` as soon as any word occurs in [text], without collecting matches — the
     * cheapest way to answer "does the text contain any of the words?".
     */
    public fun containsAny(text: String): Boolean {
        var state = ROOT_STATE
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            state = step(state, codePoint)
            if (automaton.outputPosOf(state) >= 0) {
                return true
            }
            index += charCount(codePoint)
        }
        return false
    }

    override fun toString(): String = "AhoCorasick(wordCount=${storedWords.size}, caseFolding=$caseFolding)"

    /**
     * Advances by one code point. A code point absent from the dictionary alphabet has no edge
     * anywhere in the automaton, so the failure descent would collapse straight to the root —
     * done here without touching the double array.
     */
    private fun step(
        state: Int,
        codePoint: Int,
    ): Int {
        val mapped = automaton.mapCode(caseFolding.fold(codePoint))
        return if (mapped < 0) ROOT_STATE else automaton.nextState(state, mapped)
    }

    private fun collectMatches(text: String): MutableList<Match> {
        val matches = mutableListOf<Match>()
        var state = ROOT_STATE
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            state = step(state, codePoint)
            val end = index + charCount(codePoint)
            var outputPos = automaton.outputPosOf(state)
            while (outputPos >= 0) {
                val wordIndex = automaton.outputWordIndexes[outputPos]
                // Folding is 1 Char -> 1 Char, so the folded path length equals the original
                // word length and the start index is exact.
                matches.add(Match(storedWords[wordIndex], (end - storedWordLengths[wordIndex]) until end))
                outputPos = automaton.outputParentPositions[outputPos]
            }
            index = end
        }
        return matches
    }

    /**
     * Mutable accumulator for [buildAhoCorasick]; [build] performs the actual automaton
     * construction. Also usable directly in the fluent style:
     * `AhoCorasick.Builder().add("foo").addAll(words).build()`.
     */
    public class Builder {
        private val words = mutableListOf<String>()

        /** The [CaseFolding] mode to build with; defaults to [CaseFolding.NONE]. */
        public var caseFolding: CaseFolding = CaseFolding.NONE

        /**
         * Adds [word] to the dictionary.
         */
        public fun add(word: String): Builder {
            words.add(word)
            return this
        }

        /**
         * Adds all [words] to the dictionary.
         */
        public fun addAll(words: Iterable<String>): Builder {
            this.words.addAll(words)
            return this
        }

        /**
         * Adds this string to the dictionary; DSL sugar for [add] inside [buildAhoCorasick].
         */
        public operator fun String.unaryPlus() {
            add(this)
        }

        /**
         * Builds the immutable [AhoCorasick] automaton.
         */
        public fun build(): AhoCorasick = AhoCorasick(words, caseFolding)
    }
}

/**
 * Builds an [AhoCorasick] with a DSL, in the style of [buildString]:
 *
 * ```kotlin
 * val ac = buildAhoCorasick {
 *     caseFolding = CaseFolding.ASCII
 *     +"foo"
 *     +"bar"
 *     addAll(otherWords)
 * }
 * ```
 */
public fun buildAhoCorasick(block: AhoCorasick.Builder.() -> Unit): AhoCorasick =
    AhoCorasick.Builder().apply(block).build()
