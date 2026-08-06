package dev.hsbrysk.ahocorasick

/**
 * Controls how [AhoCorasick] folds character case, applied identically to the dictionary words at
 * construction time and to the searched text at scan time.
 *
 * Folding always maps one `Char` to one `Char`, so reported [Match.range]s are exact indices into
 * the original text with no index remapping. As a consequence, *full* case folding — mappings that
 * change the string length, such as `ß` ↔ `ss` — is intentionally not supported on any mode.
 * Callers who need it should normalize both the dictionary and the text themselves before matching.
 */
public enum class CaseFolding {
    /**
     * No folding; matching is fully case-sensitive.
     */
    NONE,

    /**
     * Folds only the ASCII letters `A`-`Z` to `a`-`z`. Safe, fast and locale-independent; every
     * non-ASCII character is compared verbatim.
     */
    ASCII,

    /**
     * Folds every code point in the Basic Multilingual Plane with the Unicode *simple* (1:1,
     * locale-independent) lowercase mapping, i.e. [Char.lowercaseChar]. Supplementary-plane code
     * points (e.g. Deseret, Adlam) are not folded, because the common stdlib has no code-point
     * level lowercase.
     *
     * Known differences from full case folding and from regex `IGNORE_CASE` engines:
     * - `ß` does not match `ss`/`SS` (full folding), but `ẞ` (U+1E9E) matches `ß`.
     * - Greek final sigma `ς` (U+03C2) is already lowercase and is kept as-is, so `ς` and `σ` do
     *   not match each other; `Σ` matches `σ` only.
     * - `İ` (U+0130) folds to plain `i`; `ı` (U+0131) folds to itself. There is no Turkish-locale
     *   behavior.
     * - Kelvin sign `K` (U+212A) matches `k`, Ångström sign `Å` (U+212B) matches `å`.
     */
    UNICODE_SIMPLE,

    ;

    internal fun fold(codePoint: Int): Int = when (this) {
        NONE -> codePoint
        ASCII ->
            if (codePoint in 'A'.code..'Z'.code) codePoint + ('a'.code - 'A'.code) else codePoint
        UNICODE_SIMPLE ->
            if (codePoint <= Char.MAX_VALUE.code) codePoint.toChar().lowercaseChar().code else codePoint
    }
}
