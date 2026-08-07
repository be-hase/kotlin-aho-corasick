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
 * Compares scanning a text for a keyword list four ways: a naive `word1|word2|...` regex
 * alternation, the trie-optimized regex produced by
 * [kotlin-regexp-trie](https://github.com/be-hase/kotlin-regexp-trie), [AhoCorasick.findAll]
 * (the double-array v2), and [LegacyAhoCorasick.findAll] (the frozen HashMap-trie v1). All
 * report leftmost-longest non-overlapping matches, so the match counts are identical.
 *
 * The setup is fully deterministic (fixed seed): a vocabulary of syllable-based words (which
 * naturally share prefixes, like real dictionaries do), of which [wordCount] words become the
 * keyword list, and a text of [TEXT_TOKENS] vocabulary words to scan.
 */
@State(Scope.Benchmark)
class AhoCorasickBenchmark {
    @Param("100", "1000", "10000")
    var wordCount = 0

    private lateinit var words: List<String>
    private lateinit var naiveRegex: Regex
    private lateinit var trieRegex: Regex
    private lateinit var ahoCorasick: AhoCorasick
    private lateinit var legacyAhoCorasick: LegacyAhoCorasick
    private lateinit var text: String

    @Setup
    fun setup() {
        val random = Random(SEED)
        // Vocabulary is larger than the keyword list so the text contains non-keywords too.
        val vocabulary = generateWords(wordCount * 2, random)
        words = vocabulary.shuffled(random).take(wordCount)
        // Longest-first so the naive alternation also prefers the longest keyword at the same
        // start position, matching the leftmost-longest semantics of the other two.
        naiveRegex = Regex(words.sortedByDescending { it.length }.joinToString("|", "(?:", ")"))
        trieRegex = RegexpTrie(words).toRegex()
        ahoCorasick = AhoCorasick(words)
        legacyAhoCorasick = LegacyAhoCorasick(words)
        text = List(TEXT_TOKENS) { vocabulary.random(random) }.joinToString(" ")
    }

    @Benchmark
    fun naiveAlternationFindAll(): Int = naiveRegex.findAll(text).count()

    @Benchmark
    fun regexpTrieFindAll(): Int = trieRegex.findAll(text).count()

    @Benchmark
    fun ahoCorasickFindAll(): Int = ahoCorasick.findAll(text).size

    @Benchmark
    fun ahoCorasickBuild(): AhoCorasick = AhoCorasick(words)

    @Benchmark
    fun legacyFindAll(): Int = legacyAhoCorasick.findAll(text).size

    @Benchmark
    fun legacyBuild(): Any = LegacyAhoCorasick(words)

    private fun generateWords(
        count: Int,
        random: Random,
    ): List<String> {
        val result = LinkedHashSet<String>()
        while (result.size < count) {
            val syllableCount = random.nextInt(2, 6)
            result.add((1..syllableCount).joinToString("") { SYLLABLES.random(random) })
        }
        return result.toList()
    }

    companion object {
        private const val SEED = 42
        private const val TEXT_TOKENS = 1_000

        private val SYLLABLES = listOf(
            "ka", "ki", "ku", "ke", "ko", "sa", "shi", "su", "se", "so",
            "ta", "chi", "tsu", "te", "to", "na", "ni", "nu", "ne", "no",
            "ha", "hi", "fu", "he", "ho", "ma", "mi", "mu", "me", "mo",
            "ra", "ri", "ru", "re", "ro", "ya", "yu", "yo", "wa", "n",
        )
    }
}
