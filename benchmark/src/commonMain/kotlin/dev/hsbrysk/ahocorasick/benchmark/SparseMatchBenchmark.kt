package dev.hsbrysk.ahocorasick.benchmark

import dev.hsbrysk.ahocorasick.AhoCorasick
import dev.hsbrysk.regexptrie.RegexpTrie
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlin.random.Random

/**
 * The sparse-match counterpart of [AhoCorasickBenchmark]: a long text in which keywords are rare
 * (one planted keyword every [PLANT_EVERY] tokens, ~2% of tokens) and — crucially — the filler
 * text does not contain the letter `k` while every keyword does. This models the workload class
 * where dictionary words carry at least one character that is rare in the scanned text (banned
 * words with uncommon kanji, product codes, brand names in prose, …), which is exactly the regime
 * a rare-character prefilter targets. The dense benchmark ([AhoCorasickBenchmark], text ~50%
 * keywords) stays the regression guard for the opposite regime.
 *
 * Deterministic (fixed seed). All contenders report leftmost-longest non-overlapping matches.
 */
@State(Scope.Benchmark)
class SparseMatchBenchmark {
    @Param("100", "1000", "10000")
    var wordCount = 0

    private lateinit var words: List<String>
    private lateinit var naiveRegex: Regex
    private lateinit var trieRegex: Regex
    private lateinit var ahoCorasick: AhoCorasick
    private lateinit var text: String

    @Setup
    fun setup() {
        val random = Random(SEED)
        words = generateKeywords(wordCount, random)
        naiveRegex = Regex(words.sortedByDescending { it.length }.joinToString("|", "(?:", ")"))
        trieRegex = RegexpTrie(words).toRegex()
        ahoCorasick = AhoCorasick(words)
        val filler = generateFillerWords(random)
        text = List(TEXT_TOKENS) { index ->
            if ((index + 1) % PLANT_EVERY == 0) words.random(random) else filler.random(random)
        }.joinToString(" ")
    }

    @Benchmark
    fun naiveAlternationFindAll(): Int = naiveRegex.findAll(text).count()

    @Benchmark
    fun regexpTrieFindAll(): Int = trieRegex.findAll(text).count()

    @Benchmark
    fun ahoCorasickFindAll(): Int = ahoCorasick.findAll(text).size

    /** Every keyword contains exactly one `k`-syllable; `k` never occurs in the filler. */
    private fun generateKeywords(
        count: Int,
        random: Random,
    ): List<String> {
        val result = LinkedHashSet<String>()
        while (result.size < count) {
            val syllableCount = random.nextInt(2, 6)
            val rareAt = random.nextInt(syllableCount)
            result.add(
                (0 until syllableCount).joinToString("") { index ->
                    if (index == rareAt) RARE_SYLLABLES.random(random) else COMMON_SYLLABLES.random(random)
                },
            )
        }
        return result.toList()
    }

    private fun generateFillerWords(random: Random): List<String> {
        val result = LinkedHashSet<String>()
        while (result.size < FILLER_WORDS) {
            val syllableCount = random.nextInt(2, 6)
            result.add((1..syllableCount).joinToString("") { COMMON_SYLLABLES.random(random) })
        }
        return result.toList()
    }

    companion object {
        private const val SEED = 42
        private const val TEXT_TOKENS = 5_000
        private const val PLANT_EVERY = 50
        private const val FILLER_WORDS = 2_000

        private val RARE_SYLLABLES = listOf("ka", "ki", "ku", "ke", "ko")

        // The dense benchmark's syllable set minus the k-syllables.
        private val COMMON_SYLLABLES = listOf(
            "sa", "shi", "su", "se", "so",
            "ta", "chi", "tsu", "te", "to", "na", "ni", "nu", "ne", "no",
            "ha", "hi", "fu", "he", "ho", "ma", "mi", "mu", "me", "mo",
            "ra", "ri", "ru", "re", "ro", "ya", "yu", "yo", "wa", "n",
        )
    }
}
