package dev.hsbrysk.ahocorasick

/**
 * A rare-character prefilter in the spirit of the `RareBytes` prefilters of BurntSushi's
 * aho-corasick crate: pick (greedily) one rare character per dictionary word so that every match
 * must contain at least one character of the resulting *required set*, then locate occurrences of
 * those characters with [String.indexOf] — which compiles to the platform's native, typically
 * SIMD-accelerated memchr on the JVM and V8 — and let the scan jump over stretches of text that
 * cannot contain a match.
 *
 * The scan may only consult the prefilter **at the root state**: a non-root state means a partial
 * match is in progress and jumping would lose it. From the root at index `i` with the next
 * required-character occurrence at `c`, any match starting in `i until c - (maxWordLength - 1)`
 * would end before `c` and therefore contain a required character before `c` — contradicting `c`
 * being the next occurrence — so jumping to `c - (maxWordLength - 1)` skips no match. A jump
 * target landing on the low half of a surrogate pair is moved back one `Char` so the pair is
 * never read as two lone surrogates.
 *
 * The prefilter is purely an optimization and never changes results. It is built only when the
 * required set stays small ([MAX_REQUIRED_CHARS] folded characters, [MAX_SEARCH_CHARS] after
 * expanding case-folding preimages, e.g. `k` → `k`/`K`/`K`), and each scan additionally
 * measures its own effectiveness through a [Cursor] and turns itself off mid-scan when it is not
 * skipping enough text to pay for itself (dense candidates), falling back to the plain automaton
 * loop. All state that varies during a scan lives in the [Cursor], so a built [Prefilter] — like
 * the automaton — is immutable and safe for concurrent reads.
 */
internal class Prefilter private constructor(
    private val searchChars: CharArray,
    private val rewind: Int,
) {
    fun newCursor(): Cursor = Cursor()

    /**
     * Per-scan mutable state: cached next occurrence per search character + effectiveness.
     * Caches are text-derived, so a cursor is only valid for the single text it is advanced
     * over — create a fresh one per scan.
     */
    inner class Cursor {
        // -1 = not looked up yet ("stale below any index"); NO_CANDIDATE = exhausted.
        private val nextPositions = IntArray(searchChars.size) { -1 }
        private var cachedCandidate = -1
        private var skipped = 0
        private var startIndex = -1
        private var checkAt = -1

        /**
         * Called at the root state at [index]. Returns the index to resume scanning at (`>=`
         * [index]; `text.length` when no candidate remains), or [DISABLE] when the prefilter has
         * measured itself as ineffective on this text and the caller should continue with the
         * plain loop from [index].
         */
        fun advance(
            text: String,
            index: Int,
        ): Int {
            // Saturating arithmetic: beyond ~2^30 chars the doubled deadline would wrap negative
            // and collide with the "first call" sentinel. Saturation instead disarms the check
            // for the rest of the scan — defined and harmless at such text sizes.
            if (checkAt < 0) {
                startIndex = index
                checkAt = if (index >= Int.MAX_VALUE - FIRST_CHECK_INTERVAL) {
                    Int.MAX_VALUE
                } else {
                    index + FIRST_CHECK_INTERVAL
                }
            } else if (index >= checkAt) {
                if (skipped < (index - startIndex) ushr EFFECTIVENESS_SHIFT) {
                    return DISABLE
                }
                checkAt = if (index > Int.MAX_VALUE shr 1) Int.MAX_VALUE else index shl 1
            }
            val candidate = nextCandidate(text, index)
            if (candidate == NO_CANDIDATE) {
                skipped += text.length - index
                return text.length
            }
            var target = candidate - rewind
            if (target > index && text[target].isLowSurrogate() && text[target - 1].isHighSurrogate()) {
                target--
            }
            if (target <= index) {
                return index
            }
            skipped += target - index
            return target
        }

        private fun nextCandidate(
            text: String,
            from: Int,
        ): Int {
            // The minimum over per-character positions only moves when the scan passes it, so
            // consulting the prefilter on every root-state character costs O(1), not O(k).
            if (cachedCandidate >= from) {
                return cachedCandidate
            }
            var candidate = NO_CANDIDATE
            for (i in searchChars.indices) {
                var position = nextPositions[i]
                if (position < from) {
                    position = text.indexOf(searchChars[i], from)
                    if (position < 0) {
                        position = NO_CANDIDATE
                    }
                    nextPositions[i] = position
                }
                if (position < candidate) {
                    candidate = position
                }
            }
            cachedCandidate = candidate
            return candidate
        }
    }

    companion object {
        const val DISABLE = -1

        /**
         * Builds a prefilter for [storedWords], or `null` when one cannot pay off: the greedy
         * union of per-word rarest characters (words already containing a chosen character add
         * nothing) exceeds [MAX_REQUIRED_CHARS], or the preimage expansion exceeds
         * [MAX_SEARCH_CHARS].
         */
        fun build(
            storedWords: List<String>,
            caseFolding: CaseFolding,
        ): Prefilter? {
            // A linear-scanned CharArray instead of a HashSet<Char>: the set holds at most
            // MAX_REQUIRED_CHARS entries and Kotlin/JS would box every Char going through a
            // generic collection, which showed up in the build benchmark.
            val required = CharArray(MAX_REQUIRED_CHARS)
            var requiredSize = 0
            var maxWordLength = 0
            for (word in storedWords) {
                if (word.length > maxWordLength) {
                    maxWordLength = word.length
                }
                val rarest = rarestUncoveredChar(word, caseFolding, required, requiredSize) ?: continue
                if (requiredSize == MAX_REQUIRED_CHARS) {
                    return null
                }
                required[requiredSize++] = rarest
            }
            val searchChars = expandPreimages(required, requiredSize, caseFolding) ?: return null
            return Prefilter(searchChars, (maxWordLength - 1).coerceAtLeast(0))
        }

        /**
         * The folded character of [word] ranked rarest by [commonness] (ties broken by code
         * point, for determinism), or `null` when the word already contains a character of
         * [required]. Folding is 1 `Char` → 1 `Char`, so folding each `Char` individually equals
         * folding per code point (surrogates fold to themselves).
         */
        private fun rarestUncoveredChar(
            word: String,
            caseFolding: CaseFolding,
            required: CharArray,
            requiredSize: Int,
        ): Char? {
            var best = '\u0000'
            var bestScore = Int.MAX_VALUE
            for (ch in word) {
                val folded = caseFolding.fold(ch.code).toChar()
                if (containsChar(required, requiredSize, folded)) {
                    return null
                }
                val score = (commonness(folded) shl COMMONNESS_TIE_SHIFT) or folded.code
                if (score < bestScore) {
                    bestScore = score
                    best = folded
                }
            }
            return best
        }

        private fun containsChar(
            chars: CharArray,
            size: Int,
            ch: Char,
        ): Boolean {
            for (i in 0 until size) {
                if (chars[i] == ch) {
                    return true
                }
            }
            return false
        }

        /**
         * Expands the folded [required] set to every `Char` the searched (unfolded) text may
         * contain for it. [CaseFolding.UNICODE_SIMPLE] needs a full BMP sweep because preimages
         * are not enumerable locally (e.g. `k` ← `k`, `K` and the Kelvin sign U+212A).
         */
        private fun expandPreimages(
            required: CharArray,
            requiredSize: Int,
            caseFolding: CaseFolding,
        ): CharArray? {
            val chars = when (caseFolding) {
                CaseFolding.NONE -> required.copyOf(requiredSize).toList()
                CaseFolding.ASCII -> buildList {
                    for (i in 0 until requiredSize) {
                        val ch = required[i]
                        add(ch)
                        if (ch in 'a'..'z') {
                            add(ch - ASCII_CASE_GAP)
                        }
                    }
                }
                CaseFolding.UNICODE_SIMPLE -> buildList {
                    for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {
                        if (containsChar(required, requiredSize, caseFolding.fold(code).toChar())) {
                            add(code.toChar())
                        }
                    }
                }
            }
            if (chars.size > MAX_SEARCH_CHARS) {
                return null
            }
            return chars.sorted().toCharArray()
        }

        /**
         * A heuristic "how common is this character in scanned text" score (higher = more
         * common); only the relative order matters, and the runtime effectiveness check corrects
         * for texts where the heuristic is wrong.
         */
        private fun commonness(ch: Char): Int {
            val code = ch.code
            return when {
                code < ASCII_TABLE_SIZE -> ASCII_COMMONNESS[code]
                code < LATIN_EXTENDED_END -> LATIN_EXTENDED_COMMONNESS
                code in HIRAGANA -> HIRAGANA_COMMONNESS
                code in KATAKANA -> KATAKANA_COMMONNESS
                code in CJK_UNIFIED -> CJK_COMMONNESS
                code in HANGUL_SYLLABLES -> CJK_COMMONNESS
                code in SURROGATES -> SURROGATE_COMMONNESS
                code in FULLWIDTH_FORMS -> FULLWIDTH_COMMONNESS
                else -> DEFAULT_COMMONNESS
            }
        }

        private const val MAX_REQUIRED_CHARS = 8
        private const val MAX_SEARCH_CHARS = 16
        private const val FIRST_CHECK_INTERVAL = 256
        private const val EFFECTIVENESS_SHIFT = 1 // require skipping >= 1/2 of the scanned text
        private const val NO_CANDIDATE = Int.MAX_VALUE
        private const val COMMONNESS_TIE_SHIFT = 16
        private const val ASCII_CASE_GAP = 'a' - 'A'

        private const val ASCII_TABLE_SIZE = 128
        private const val LATIN_EXTENDED_END = 0x0250
        private val HIRAGANA = 0x3040..0x309F
        private val KATAKANA = 0x30A0..0x30FF
        private val CJK_UNIFIED = 0x4E00..0x9FFF
        private val HANGUL_SYLLABLES = 0xAC00..0xD7AF
        private val SURROGATES = 0xD800..0xDFFF
        private val FULLWIDTH_FORMS = 0xFF00..0xFF60

        private const val LATIN_EXTENDED_COMMONNESS = 70
        private const val HIRAGANA_COMMONNESS = 220
        private const val KATAKANA_COMMONNESS = 210
        private const val CJK_COMMONNESS = 170
        private const val SURROGATE_COMMONNESS = 40
        private const val FULLWIDTH_COMMONNESS = 90
        private const val DEFAULT_COMMONNESS = 60

        private const val CONTROL_COMMONNESS = 10
        private const val SYMBOL_COMMONNESS = 80
        private const val TOP_LETTER_COMMONNESS = 250
        private const val LETTER_COMMONNESS_STEP = 5
        private const val UPPERCASE_PENALTY = 60

        /** English letter frequency order; exactness does not matter, see [commonness]. */
        private const val LETTERS_BY_FREQUENCY = "etaoinshrdlcumwfgypbvkjxqz"

        private val ASCII_COMMONNESS = IntArray(ASCII_TABLE_SIZE) { CONTROL_COMMONNESS }.also { table ->
            fun set(
                chars: String,
                value: Int,
            ) {
                for (ch in chars) {
                    table[ch.code] = value
                }
            }
            LETTERS_BY_FREQUENCY.forEachIndexed { rank, ch ->
                val value = TOP_LETTER_COMMONNESS - rank * LETTER_COMMONNESS_STEP
                table[ch.code] = value
                table[ch.uppercaseChar().code] = value - UPPERCASE_PENALTY
            }
            set(" ", 255)
            set("\n", 230)
            set("\r", 225)
            set("\t", 210)
            set(".,", 200)
            set("'-\"", 180)
            set("01", 130)
            set("23456789", 115)
            set(":;!?()", 150)
            set("/_", 140)
            set("<>=+*&%$#@^~`|\\[]{}", SYMBOL_COMMONNESS)
        }
    }
}
