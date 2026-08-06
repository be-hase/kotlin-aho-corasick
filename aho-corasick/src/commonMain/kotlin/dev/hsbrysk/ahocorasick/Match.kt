package dev.hsbrysk.ahocorasick

/**
 * A single occurrence of a dictionary word in the searched text.
 *
 * @property word the matched word in its original spelling as registered (never the case-folded
 * form). When several registered words fold to the same key under a [CaseFolding] mode, this is
 * the first registered spelling.
 * @property range the `Char` (UTF-16) indices of the occurrence in the original text, in the same
 * convention as [MatchResult.range][kotlin.text.MatchResult.range]: `text.substring(range)` is the
 * matched region.
 */
public data class Match(
    val word: String,
    val range: IntRange,
)
