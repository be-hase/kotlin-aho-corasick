package dev.hsbrysk.ahocorasick

/**
 * Relocates a freshly built [Nfa] into the interleaved double-array layout of [CompactAutomaton].
 *
 * Base search follows the paper's Chain + SkipForward heuristics: free slots are kept in a
 * doubly-linked list restricted to a sliding window of the newest [NUM_FREE_BLOCKS] blocks.
 * Blocks that slide out of the window keep their leftover free slots forever vacant — that only
 * wastes a little memory, never correctness — which bounds the search cost per state.
 *
 * `check` is written only when a child is placed into a slot (never speculatively), so a slot
 * that was skipped by the search can never verify as someone's child: false transitions are
 * structurally impossible.
 *
 * Build-time-only memory: the window allocates three arrays of `NUM_FREE_BLOCKS × blockLen`
 * entries (two int, one boolean), i.e. it scales with the alphabet size (like [CodeMapper]'s
 * table) and is discarded after construction.
 */
internal class DoubleArrayBuilder(private val blockLen: Int) {
    private var data = IntArray(blockLen shl STATE_SHIFT)
    private var numSlots = 0
    private val window = FreeSlotWindow(blockLen)
    private var maxUsedSlot = 0

    /** Returns the trimmed `data` array; its length stays a multiple of [blockLen]. */
    fun place(nfa: Nfa): IntArray {
        allocBlock()
        window.fix(ROOT_STATE)
        val stateToSlot = IntArray(nfa.numStates)
        placeStates(nfa, stateToSlot)
        writeFailsAndOutputs(nfa, stateToSlot)
        return data.copyOf(((maxUsedSlot / blockLen + 1) * blockLen) shl STATE_SHIFT)
    }

    private fun placeStates(
        nfa: Nfa,
        stateToSlot: IntArray,
    ) {
        val queue = IntArray(nfa.numStates)
        var head = 0
        var tail = 0
        val labels = IntArray(blockLen)
        val children = IntArray(blockLen)
        queue[tail++] = ROOT_STATE
        while (head < tail) {
            val state = queue[head++]
            val slot = stateToSlot[state]
            var count = 0
            var edge = nfa.edges.head[state]
            while (edge >= 0) {
                labels[count] = nfa.edges.label[edge]
                children[count] = nfa.edges.target[edge]
                count++
                edge = nfa.edges.next[edge]
            }
            if (count == 0) {
                continue // base stays NO_BASE
            }
            val base = findBase(labels, count)
            data[slot shl STATE_SHIFT] = base
            for (i in 0 until count) {
                val childSlot = base xor labels[i]
                window.fix(childSlot)
                data[(childSlot shl STATE_SHIFT) or CHECK_OFFSET] = slot
                stateToSlot[children[i]] = childSlot
                if (childSlot > maxUsedSlot) {
                    maxUsedSlot = childSlot
                }
                queue[tail++] = children[i]
            }
        }
    }

    /**
     * Finds a base placing every label into a currently free slot. All candidate child slots
     * `(free slot xor labels[0]) xor labels[i]` share the free slot's block (labels are smaller
     * than the power-of-two block length), so they are always inside the window and in bounds.
     */
    private fun findBase(
        labels: IntArray,
        count: Int,
    ): Int {
        var slot = window.firstFree()
        while (slot >= 0) {
            val base = slot xor labels[0]
            if (allSlotsFree(base, labels, count)) {
                return base
            }
            slot = window.nextFree(slot)
        }
        // No fit among the windowed free slots: a brand-new empty block fits any label set.
        val blockStart = numSlots
        allocBlock()
        return blockStart
    }

    private fun allSlotsFree(
        base: Int,
        labels: IntArray,
        count: Int,
    ): Boolean {
        for (i in 0 until count) {
            if (!window.isFree(base xor labels[i])) {
                return false
            }
        }
        return true
    }

    private fun allocBlock() {
        val start = numSlots
        val required = (start + blockLen) shl STATE_SHIFT
        if (required > data.size) {
            var newSize = data.size * 2
            while (newSize < required) {
                newSize *= 2
            }
            data = data.copyOf(newSize)
        }
        for (slot in start until start + blockLen) {
            val index = slot shl STATE_SHIFT
            data[index] = NO_BASE
            data[index or CHECK_OFFSET] = NO_CHECK
            data[index or OUTPUT_OFFSET] = NO_OUTPUT
            // FAIL keeps copyOf's zero; it is overwritten for every placed slot.
        }
        numSlots += blockLen
        window.pushBlock(start)
    }

    private fun writeFailsAndOutputs(
        nfa: Nfa,
        stateToSlot: IntArray,
    ) {
        for (state in 0 until nfa.numStates) {
            val index = stateToSlot[state] shl STATE_SHIFT
            data[index or FAIL_OFFSET] = stateToSlot[nfa.fail[state]]
            data[index or OUTPUT_OFFSET] = nfa.outputPos[state]
        }
    }

    /**
     * The doubly-linked free-slot list over the newest [NUM_FREE_BLOCKS] blocks. Ring buffers are
     * indexed by `slot and mask`, which is unambiguous because only window-resident slots are
     * ever passed in.
     */
    private class FreeSlotWindow(private val blockLen: Int) {
        private val capacity = NUM_FREE_BLOCKS * blockLen
        private val mask = capacity - 1
        private val next = IntArray(capacity)
        private val prev = IntArray(capacity)
        private val free = BooleanArray(capacity)
        private var head = NO_SLOT
        private var tail = NO_SLOT
        private var windowStart = 0

        fun pushBlock(blockStart: Int) {
            if (blockStart - windowStart >= capacity) {
                evictOldestBlock()
            }
            for (slot in blockStart until blockStart + blockLen) {
                append(slot)
            }
        }

        fun firstFree(): Int = head

        fun nextFree(slot: Int): Int = next[slot and mask]

        fun isFree(slot: Int): Boolean = free[slot and mask]

        fun fix(slot: Int) {
            if (free[slot and mask]) {
                unlink(slot)
            }
        }

        private fun evictOldestBlock() {
            for (slot in windowStart until windowStart + blockLen) {
                if (free[slot and mask]) {
                    unlink(slot)
                }
            }
            windowStart += blockLen
        }

        private fun append(slot: Int) {
            val index = slot and mask
            free[index] = true
            next[index] = NO_SLOT
            prev[index] = tail
            if (tail == NO_SLOT) {
                head = slot
            } else {
                next[tail and mask] = slot
            }
            tail = slot
        }

        private fun unlink(slot: Int) {
            val index = slot and mask
            free[index] = false
            val before = prev[index]
            val after = next[index]
            if (before == NO_SLOT) {
                head = after
            } else {
                next[before and mask] = after
            }
            if (after == NO_SLOT) {
                tail = before
            } else {
                prev[after and mask] = before
            }
        }
    }
}

/**
 * SkipForward window size in blocks; larger packs tighter, smaller builds faster. Must be a
 * power of two: [FreeSlotWindow]'s ring indexing relies on the window capacity
 * (`NUM_FREE_BLOCKS * blockLen`) being one.
 */
private const val NUM_FREE_BLOCKS = 16

private const val NO_SLOT = -1
