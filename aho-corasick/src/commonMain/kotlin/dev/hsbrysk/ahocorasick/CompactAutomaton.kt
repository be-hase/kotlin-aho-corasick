package dev.hsbrysk.ahocorasick

/**
 * The compact double-array Aho-Corasick automaton, following the charwise daachorse layout
 * ("Engineering faster double-array Aho-Corasick automata", Kanda et al. 2023): one interleaved
 * [IntArray] with four ints per state — base, check, fail and output position — so each state is
 * 16 contiguous bytes and a transition is `child = base xor mappedCode` verified by
 * `check[child] == parent`. Failure transitions follow the `fail` field and stop at the root
 * (`δ(root, c) = root`).
 *
 * Sentinels are all -1: `base = -1` means "no children" (0 is a legal base), `check = -1` marks
 * the root and every never-occupied slot (parent ids are >= 0, so such a slot can never verify),
 * and `outputPos = -1` means "no word ends here or on the failure chain".
 *
 * The array length is always a multiple of the block length (the alphabet size rounded up to a
 * power of two), and mapped codes are smaller than the block length, so `base xor mappedCode`
 * stays inside base's block and never needs a bounds guard.
 */
internal class CompactAutomaton(
    private val data: IntArray,
    val outputWordIndexes: IntArray,
    val outputParentPositions: IntArray,
    private val mapper: CodeMapper,
) {
    /** Returns the dense alphabet id for a folded code point, or [CodeMapper.INVALID_CODE]. */
    fun mapCode(foldedCodePoint: Int): Int = mapper.map(foldedCodePoint)

    /**
     * The goto function: follows failure links from [from] until a state with a [mappedCode]
     * child is found, or stays at the root on a complete miss.
     */
    fun nextState(
        from: Int,
        mappedCode: Int,
    ): Int {
        var state = from
        while (true) {
            val base = data[state shl STATE_SHIFT]
            if (base >= 0) {
                val child = base xor mappedCode
                if (data[(child shl STATE_SHIFT) or CHECK_OFFSET] == state) {
                    return child
                }
            }
            if (state == ROOT_STATE) {
                return ROOT_STATE
            }
            state = data[(state shl STATE_SHIFT) or FAIL_OFFSET]
        }
    }

    /** The state's position in the output forest, or -1 when it emits nothing. */
    fun outputPosOf(state: Int): Int = data[(state shl STATE_SHIFT) or OUTPUT_OFFSET]

    class Built(
        val automaton: CompactAutomaton,
        val storedWords: List<String>,
    )

    companion object {
        fun build(
            words: List<String>,
            caseFolding: CaseFolding,
        ): Built {
            val mapper = CodeMapper.build(countFoldedCodePoints(words, caseFolding))
            val nfa = Nfa.build(words, caseFolding, mapper)
            val data = DoubleArrayBuilder(blockLenFor(mapper.alphabetSize)).place(nfa)
            val automaton = CompactAutomaton(
                data = data,
                outputWordIndexes = nfa.outputs.wordIndexes,
                outputParentPositions = nfa.outputs.parentPositions,
                mapper = mapper,
            )
            return Built(automaton, nfa.storedWords)
        }

        private fun countFoldedCodePoints(
            words: List<String>,
            caseFolding: CaseFolding,
        ): Map<Int, Int> {
            val frequencies = HashMap<Int, Int>()
            for (word in words) {
                require(word.isNotEmpty()) { "words must not contain an empty string" }
                var index = 0
                while (index < word.length) {
                    val codePoint = word.codePointAt(index)
                    val folded = caseFolding.fold(codePoint)
                    frequencies[folded] = (frequencies[folded] ?: 0) + 1
                    index += charCount(codePoint)
                }
            }
            return frequencies
        }

        /** The alphabet size rounded up to a power of two, at least [MIN_BLOCK_LEN]. */
        private fun blockLenFor(alphabetSize: Int): Int {
            if (alphabetSize <= MIN_BLOCK_LEN) {
                return MIN_BLOCK_LEN
            }
            val highestBit = alphabetSize.takeHighestOneBit()
            return if (highestBit == alphabetSize) alphabetSize else highestBit shl 1
        }
    }
}

internal const val ROOT_STATE = 0

/** Four ints per state: `data[state shl STATE_SHIFT or OFFSET]`. */
internal const val STATE_SHIFT = 2
internal const val CHECK_OFFSET = 1
internal const val FAIL_OFFSET = 2
internal const val OUTPUT_OFFSET = 3

internal const val NO_BASE = -1
internal const val NO_CHECK = -1
internal const val NO_OUTPUT = -1

private const val MIN_BLOCK_LEN = 2
