package dev.adstro.diceware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LargeWordListMemoryTest {
    // The heap is measured while the import really runs on other threads, so the test uses the real dispatchers.
    @Suppress("InjectDispatcher")
    @Test
    fun importingA6To7WordListKeepsTheLiveHeapSmallerThanTheListItself() = runBlocking {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "diceware-memory-${System.nanoTime()}"
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val library = WordListLibrary({ emptyEffLists() }, FileSystem.SYSTEM, directory, scope)
            library.wordLists.first { it.isNotEmpty() }
            val baseline = liveHeap()
            var peak = 0L
            // A GC at every progress step measures what the import keeps alive, not its garbage.
            val sampler = launch(Dispatchers.Default) {
                library.importState.collect {
                    if (it is ImportState.Importing) peak = maxOf(peak, liveHeap() - baseline)
                }
            }

            val size = (0 until COUNT).sumOf { "w$it\n".length.toLong() }
            library.import("huge.txt", size) { Lines(COUNT) }
            sampler.cancel()

            assertEquals(ImportState.Imported("huge"), library.importState.value)
            val wordList = assertNotNull(library.wordList(library.wordLists.value.last().id))
            assertEquals("w${COUNT - 1}", wordList.resolve(List(DICE_PER_ROLL) { DIE_FACES }))
            val retained = liveHeap() - baseline
            // The 279,936 Words as Strings alone would take well over 10 MB.
            assertTrue(peak < MAX_PEAK_HEAP_BYTES, "peak live heap grew by $peak bytes")
            assertTrue(retained < MAX_RETAINED_HEAP_BYTES, "the imported list keeps $retained bytes alive")
        } finally {
            scope.cancel()
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private fun liveHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(2) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun emptyEffLists(): Map<EffWordList, WordList> {
        val tiny = object : WordList {
            override val dicePerRoll = 4
            override fun resolve(roll: List<Int>) = "x"
        }
        return EffWordList.entries.associateWith { tiny }
    }

    private companion object {
        const val DICE_PER_ROLL = 7
        const val COUNT = 279_936 // 6⁷
        const val MAX_PEAK_HEAP_BYTES = 8_000_000
        const val MAX_RETAINED_HEAP_BYTES = 1_000_000
    }
}

/** [count] plain-format Words (w0, w1, …), produced as they are read. */
// Leaves room for one more line ("w" and up to 15 digits) so a read never overshoots byteCount.
private const val MAX_LINE_BYTES = 16

private class Lines(private val count: Int) : Source {
    private var next = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (next == count) return -1
        val start = sink.size
        while (next < count && sink.size - start < byteCount - MAX_LINE_BYTES) sink.writeUtf8("w${next++}\n")
        return sink.size - start
    }

    override fun timeout() = Timeout.NONE

    override fun close() = Unit
}
