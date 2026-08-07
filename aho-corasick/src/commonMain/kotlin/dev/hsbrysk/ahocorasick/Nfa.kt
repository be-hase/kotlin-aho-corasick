package dev.hsbrysk.ahocorasick

/**
 * The classic Aho-Corasick automaton in linked-list-trie form, the intermediate stage before the
 * double-array relocation: states keep their discovery ids, and the edges of a state are a chain
 * of (label, target) pairs starting at `edgeHead[state]`. Labels are [CodeMapper] ids of the
 * case-folded code points. Everything is flat `IntArray`s built with iterative loops, so
 * arbitrarily deep tries stay safe on Kotlin/Native's limited stack.
 *
 * Words are matched by the output forest: `outputPos[state] >= 0` iff the state or one of its
 * failure ancestors ends a word, and following [outputParentPositions] from that position emits
 * the state's own word first, then the failure chain's words — the same order as v1's output
 * links. Forest entries reference forest positions, never state ids, so they survive the
 * double-array relocation unchanged.
 */
internal class Nfa private constructor(
    val numStates: Int,
    val edges: Edges,
    val fail: IntArray,
    val outputPos: IntArray,
    val outputs: OutputForest,
    val storedWords: List<String>,
) {
    /** The edge chains of the linked-list trie: state's first edge in [head], then [next]. */
    class Edges(
        val head: IntArray,
        val label: IntArray,
        val target: IntArray,
        val next: IntArray,
    )

    /** The output forest: unique word indexes chained towards failure-ancestor words. */
    class OutputForest(
        val wordIndexes: IntArray,
        val parentPositions: IntArray,
    )

    companion object {
        fun build(
            words: List<String>,
            caseFolding: CaseFolding,
            mapper: CodeMapper,
        ): Nfa {
            val trie = Trie()
            val stored = mutableListOf<String>()
            for (word in words) {
                val state = trie.insert(word, caseFolding, mapper)
                trie.markWord(state, word, stored)
            }
            return trie.buildLinks(stored)
        }
    }

    /** Mutable trie accumulator; [buildLinks] freezes it into an [Nfa]. */
    private class Trie {
        val edgeHead = IntArrayList()
        val edgeLabel = IntArrayList()
        val edgeTarget = IntArrayList()
        val edgeNext = IntArrayList()
        val wordIndexes = IntArrayList()

        init {
            addState() // the root, state 0
        }

        fun insert(
            word: String,
            caseFolding: CaseFolding,
            mapper: CodeMapper,
        ): Int {
            var state = ROOT
            var index = 0
            while (index < word.length) {
                val codePoint = word.codePointAt(index)
                // Always mapped: the frequency pass has counted every folded code point.
                val label = mapper.map(caseFolding.fold(codePoint))
                val child = findChild(state, label)
                state = if (child >= 0) child else addChild(state, label)
                index += charCount(codePoint)
            }
            return state
        }

        fun markWord(
            state: Int,
            word: String,
            stored: MutableList<String>,
        ) {
            if (wordIndexes[state] < 0) {
                wordIndexes[state] = stored.size
                stored.add(word)
            }
        }

        fun findChild(
            state: Int,
            label: Int,
        ): Int {
            var edge = edgeHead[state]
            while (edge >= 0) {
                if (edgeLabel[edge] == label) {
                    return edgeTarget[edge]
                }
                edge = edgeNext[edge]
            }
            return -1
        }

        fun addState(): Int {
            edgeHead.add(-1)
            wordIndexes.add(-1)
            return edgeHead.size - 1
        }

        fun addChild(
            state: Int,
            label: Int,
        ): Int {
            val child = addState()
            edgeLabel.add(label)
            edgeTarget.add(child)
            edgeNext.add(edgeHead[state])
            edgeHead[state] = edgeLabel.size - 1
            return child
        }

        /**
         * Builds the failure links and the output forest breadth-first, so every link points to
         * an already-completed shallower state (which is also what makes `outputPos[fail[s]]`
         * available when state `s` is processed).
         */
        fun buildLinks(storedWords: List<String>): Nfa {
            val numStates = edgeHead.size
            val fail = IntArray(numStates)
            val outputPos = IntArray(numStates) { -1 }
            val outputWordIndexes = IntArrayList()
            val outputParentPositions = IntArrayList()
            val queue = IntArray(numStates)
            var head = 0
            var tail = 0

            fun assignOutput(state: Int) {
                val inherited = outputPos[fail[state]]
                outputPos[state] = if (wordIndexes[state] >= 0) {
                    outputWordIndexes.add(wordIndexes[state])
                    outputParentPositions.add(inherited)
                    outputWordIndexes.size - 1
                } else {
                    inherited
                }
            }

            var edge = edgeHead[ROOT]
            while (edge >= 0) {
                val child = edgeTarget[edge]
                fail[child] = ROOT
                assignOutput(child)
                queue[tail++] = child
                edge = edgeNext[edge]
            }
            while (head < tail) {
                val state = queue[head++]
                edge = edgeHead[state]
                while (edge >= 0) {
                    val child = edgeTarget[edge]
                    // state is at depth >= 1, so the fallback target is at depth >= 1 too and
                    // can never be child itself.
                    fail[child] = advance(fail[state], edgeLabel[edge], fail)
                    assignOutput(child)
                    queue[tail++] = child
                    edge = edgeNext[edge]
                }
            }
            return Nfa(
                numStates = numStates,
                edges = Edges(
                    head = edgeHead.toIntArray(),
                    label = edgeLabel.toIntArray(),
                    target = edgeTarget.toIntArray(),
                    next = edgeNext.toIntArray(),
                ),
                fail = fail,
                outputPos = outputPos,
                outputs = OutputForest(
                    wordIndexes = outputWordIndexes.toIntArray(),
                    parentPositions = outputParentPositions.toIntArray(),
                ),
                storedWords = storedWords,
            )
        }

        /**
         * The goto function on the trie: follows failure links from [state] until a state with a
         * [label] child is found, or stays at the root on a complete miss.
         */
        fun advance(
            state: Int,
            label: Int,
            fail: IntArray,
        ): Int {
            var current = state
            while (true) {
                val next = findChild(current, label)
                if (next >= 0) {
                    return next
                }
                if (current == ROOT) {
                    return ROOT
                }
                current = fail[current]
            }
        }
    }
}

private const val ROOT = 0

/** A growable [IntArray]; the tiny subset of ArrayList<Int> needed here, without boxing. */
private class IntArrayList {
    private var values = IntArray(INITIAL_CAPACITY)

    var size: Int = 0
        private set

    operator fun get(index: Int): Int = values[index]

    operator fun set(
        index: Int,
        value: Int,
    ) {
        values[index] = value
    }

    fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(size * 2)
        }
        values[size++] = value
    }

    fun toIntArray(): IntArray = values.copyOf(size)
}

private const val INITIAL_CAPACITY = 16
