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

    private val root = Node()
    private val storedWords: List<String>
    private val storedWordLengths: IntArray

    init {
        val stored = mutableListOf<String>()
        for (word in words) {
            require(word.isNotEmpty()) { "words must not contain an empty string" }
            var node = root
            var index = 0
            while (index < word.length) {
                val codePoint = word.codePointAt(index)
                node = node.children.getOrPut(caseFolding.fold(codePoint)) { Node() }
                index += charCount(codePoint)
            }
            if (node.wordIndex < 0) {
                node.wordIndex = stored.size
                stored.add(word)
            }
        }
        storedWords = stored
        storedWordLengths = IntArray(stored.size) { stored[it].length }
        buildLinks()
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
        var node = root
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            node = advance(node, caseFolding.fold(codePoint))
            if (node.hasOutput) {
                return true
            }
            index += charCount(codePoint)
        }
        return false
    }

    override fun toString(): String = "AhoCorasick(wordCount=${storedWords.size}, caseFolding=$caseFolding)"

    /**
     * Builds the failure links (longest proper suffix that is also a trie path), output links
     * (nearest terminal suffix) and the precomputed [Node.hasOutput] flag, breadth-first, so every
     * link points to an already-completed shallower node. Iterative on an [ArrayDeque]; nothing in
     * this class recurses, keeping deep tries safe on Kotlin/Native's limited stack.
     */
    private fun buildLinks() {
        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.fail = root
            child.hasOutput = child.wordIndex >= 0
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for ((codePoint, child) in node.children) {
                // node is at depth >= 1, so the fallback target is at depth >= 1 too and can never
                // be child itself.
                child.fail = advance(node.fail, codePoint)
                child.outputLink = if (child.fail.wordIndex >= 0) child.fail else child.fail.outputLink
                child.hasOutput = child.wordIndex >= 0 || child.fail.hasOutput
                queue.addLast(child)
            }
        }
    }

    /**
     * The goto function: follows failure links from [from] until a node with a [codePoint] child
     * is found, or stays at the root on a complete miss.
     */
    private fun advance(
        from: Node,
        codePoint: Int,
    ): Node {
        var node = from
        while (true) {
            val next = node.children[codePoint]
            if (next != null) {
                return next
            }
            if (node === root) {
                return root
            }
            node = node.fail
        }
    }

    private fun collectMatches(text: String): MutableList<Match> {
        val matches = mutableListOf<Match>()
        var node = root
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            node = advance(node, caseFolding.fold(codePoint))
            val end = index + charCount(codePoint)
            var out = if (node.wordIndex >= 0) node else node.outputLink
            while (out != null) {
                // Folding is 1 Char -> 1 Char, so the folded path length equals the original
                // word length and the start index is exact.
                matches.add(Match(storedWords[out.wordIndex], (end - storedWordLengths[out.wordIndex]) until end))
                out = out.outputLink
            }
            index = end
        }
        return matches
    }

    private class Node {
        /** Keys are case-folded code points. */
        val children: MutableMap<Int, Node> = HashMap()

        /** Index into [storedWords] when this node is the end of a word, or -1. */
        var wordIndex: Int = -1

        /** Failure link; the root links to itself. */
        var fail: Node = this

        /** Nearest proper-suffix node that ends a word, forming a chain that emits all outputs. */
        var outputLink: Node? = null

        /** Whether this node or any node on its failure chain ends a word. */
        var hasOutput: Boolean = false
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

/**
 * Returns the code point starting at [index]: a high surrogate followed by a low surrogate
 * combines into one supplementary code point, and any other `Char` — including a lone
 * surrogate — is its own code point.
 */
private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return MIN_SUPPLEMENTARY_CODE_POINT +
                ((high.code - Char.MIN_HIGH_SURROGATE.code) shl SURROGATE_DECODE_SHIFT) +
                (low.code - Char.MIN_LOW_SURROGATE.code)
        }
    }
    return high.code
}

private fun charCount(codePoint: Int): Int = if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

private const val MIN_SUPPLEMENTARY_CODE_POINT = 0x10000
private const val SURROGATE_DECODE_SHIFT = 10
