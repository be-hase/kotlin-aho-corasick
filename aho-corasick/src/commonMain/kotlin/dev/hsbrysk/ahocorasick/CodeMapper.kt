package dev.hsbrysk.ahocorasick

/**
 * Maps case-folded code points to the dense alphabet ids used by the double-array automaton
 * (the "Mapped" scheme of charwise daachorse): ids are ranks in decreasing frequency order, so
 * frequent characters get small ids and their transitions cluster near the front of the array.
 * Code points that never occur in the dictionary map to [INVALID_CODE].
 *
 * The table is indexed directly by code point up to the largest one seen, so its memory is
 * proportional to that maximum (a dictionary containing an emoji pays ~4 bytes per code point
 * below it) — the known trade-off of this scheme.
 */
internal class CodeMapper private constructor(
    private val table: IntArray,
    val alphabetSize: Int,
) {
    /** [codePoint] must be non-negative. */
    fun map(codePoint: Int): Int = if (codePoint < table.size) table[codePoint] else INVALID_CODE

    companion object {
        const val INVALID_CODE = -1

        fun build(frequencies: Map<Int, Int>): CodeMapper {
            if (frequencies.isEmpty()) {
                return CodeMapper(IntArray(0), 0)
            }
            // Ties broken by ascending code point so the ranking is identical on every platform,
            // independent of the map's iteration order.
            val codes = frequencies.keys.sortedWith(
                compareByDescending<Int> { frequencies.getValue(it) }.thenBy { it },
            )
            val table = IntArray(codes.max() + 1) { INVALID_CODE }
            codes.forEachIndexed { rank, code -> table[code] = rank }
            return CodeMapper(table, codes.size)
        }
    }
}
