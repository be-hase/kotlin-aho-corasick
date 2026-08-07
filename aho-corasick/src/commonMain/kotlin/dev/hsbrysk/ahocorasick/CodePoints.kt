package dev.hsbrysk.ahocorasick

/**
 * Returns the code point starting at [index]: a high surrogate followed by a low surrogate
 * combines into one supplementary code point, and any other `Char` — including a lone
 * surrogate — is its own code point.
 */
internal fun String.codePointAt(index: Int): Int {
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

internal fun charCount(codePoint: Int): Int = if (codePoint >= MIN_SUPPLEMENTARY_CODE_POINT) 2 else 1

private const val MIN_SUPPLEMENTARY_CODE_POINT = 0x10000
private const val SURROGATE_DECODE_SHIFT = 10
