package dev.hsbrysk.ahocorasick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

// JVM-only: commonTest cannot spawn threads with kotlin-test alone. Thread safety is by
// construction (all fields are assigned before the constructor returns and never mutated); these
// tests exercise that claim with real concurrent readers.
class ConcurrencyTest {
    @Test
    fun concurrentReadsReturnIdenticalResults() {
        val words = List(500) { "w${it}ab" } + listOf("he", "she", "his", "hers")
        val ac = AhoCorasick(words)
        val texts = List(20) { i -> "ushers w${i}ab and his w${i + 1}ab ".repeat(5) }
        val expected = texts.map { ac.findOverlapping(it) to ac.findAll(it) }

        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val mismatches = AtomicInteger()
        try {
            val tasks = (0 until threadCount).map { t ->
                pool.submit {
                    start.await()
                    repeat(200) { i ->
                        val index = (t + i) % texts.size
                        val text = texts[index]
                        if (ac.findOverlapping(text) != expected[index].first) mismatches.incrementAndGet()
                        if (ac.findAll(text) != expected[index].second) mismatches.incrementAndGet()
                        if (!ac.containsAny(text)) mismatches.incrementAndGet()
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
        assertEquals(0, mismatches.get())
    }

    @Test
    fun freshlyConstructedInstanceIsVisibleToOtherThreads() {
        // Construct repeatedly and read each instance from several other threads right away.
        // (Any cross-thread handoff establishes happens-before, so this is a smoke test of safe
        // publication, not a memory-model torture test.)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(50) {
                val ac = AhoCorasick("he", "she", "his", "hers")
                val results = (0 until 4).map {
                    pool.submit<List<Match>> { ac.findOverlapping("ushers") }
                }
                results.forEach {
                    assertEquals(
                        listOf(Match("she", 1..3), Match("he", 2..3), Match("hers", 2..5)),
                        it.get(10, TimeUnit.SECONDS),
                    )
                }
            }
        } finally {
            pool.shutdown()
        }
    }
}
