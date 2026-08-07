package dev.hsbrysk.ahocorasick.benchmark

import dev.hsbrysk.ahocorasick.Match

/**
 * The frozen v1 implementation (HashMap-keyed trie with node objects), kept only as the baseline
 * for benchmarking against the double-array v2. Case folding is stripped because `CaseFolding.fold`
 * is internal to the library module and the benchmarks fold nothing (`CaseFolding.NONE`);
 * everything else is a verbatim copy.
 */
internal class LegacyAhoCorasick(words: Iterable<String>) {
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
                node = node.children.getOrPut(codePoint) { Node() }
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

    fun findAll(text: String): List<Match> {
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

    fun findOverlapping(text: String): List<Match> {
        val matches = collectMatches(text)
        matches.sortWith(compareBy({ it.range.first }, { it.range.last }))
        return matches
    }

    fun containsAny(text: String): Boolean {
        var node = root
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            node = advance(node, codePoint)
            if (node.hasOutput) {
                return true
            }
            index += charCount(codePoint)
        }
        return false
    }

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
                child.fail = advance(node.fail, codePoint)
                child.outputLink = if (child.fail.wordIndex >= 0) child.fail else child.fail.outputLink
                child.hasOutput = child.wordIndex >= 0 || child.fail.hasOutput
                queue.addLast(child)
            }
        }
    }

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
            node = advance(node, codePoint)
            val end = index + charCount(codePoint)
            var out = if (node.wordIndex >= 0) node else node.outputLink
            while (out != null) {
                matches.add(Match(storedWords[out.wordIndex], (end - storedWordLengths[out.wordIndex]) until end))
                out = out.outputLink
            }
            index = end
        }
        return matches
    }

    private class Node {
        val children: MutableMap<Int, Node> = HashMap()
        var wordIndex: Int = -1
        var fail: Node = this
        var outputLink: Node? = null
        var hasOutput: Boolean = false
    }
}

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
