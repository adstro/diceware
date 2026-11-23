package com.adstro.diceware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import okio.Source
import okio.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LargeWordListMemoryTest {
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
                library.importState.collect { if (it is ImportState.Importing) peak = maxOf(peak, liveHeap() - baseline) }
            }

            val size = (0 until COUNT).sumOf { "w$it\n".length.toLong() }
            library.import("huge.txt", size) { Lines(COUNT) }
            sampler.cancel()

            assertEquals(ImportState.Imported("huge"), library.importState.value)
            val wordList = library.wordList(library.wordLists.value.last().id)!!
            assertEquals("w${COUNT - 1}", wordList.resolve(List(7) { 6 }))
            val retained = liveHeap() - baseline
            // The 279,936 Words as Strings alone would take well over 10 MB.
            assertTrue(peak < 8_000_000, "peak live heap grew by $peak bytes")
            assertTrue(retained < 1_000_000, "the imported list keeps $retained bytes alive")
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
        const val COUNT = 279_936 // 6⁷
    }
}

/** [count] plain-format Words (w0, w1, …), produced as they are read. */
private class Lines(private val count: Int) : Source {
    private var next = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (next == count) return -1
        val start = sink.size
        while (next < count && sink.size - start < byteCount - 16) sink.writeUtf8("w${next++}\n")
        return sink.size - start
    }

    override fun timeout() = Timeout.NONE

    override fun close() {}
}
