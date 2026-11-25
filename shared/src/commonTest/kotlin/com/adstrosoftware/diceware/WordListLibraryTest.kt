package com.adstrosoftware.diceware

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.Timeout
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class WordListLibraryTest {
    private val fileSystem = FakeFileSystem()

    @Test
    fun importsAPlainListNamedFromItsFileWithRollsAssignedInOrder() = runTest {
        val library = library()

        library.importText("animals.txt", plain(4))

        assertEquals(ImportState.Imported("animals"), library.importState.value)
        val custom = library.loaded().last()
        assertEquals("animals", custom.name)
        assertEquals(1296L, custom.wordCount)
        assertEquals(10.34, custom.strengthPerWordBits, 0.01)
        val wordList = library.wordList(custom.id)!!
        assertEquals("w0", wordList.resolve(listOf(1, 1, 1, 1)))
        assertEquals("w7", wordList.resolve(listOf(1, 1, 2, 2)))
        assertEquals("w1295", wordList.resolve(listOf(6, 6, 6, 6)))
    }

    @Test
    fun importsADicewareListInAnyLineOrderResolvingEachRollToItsWord() = runTest {
        val library = library()
        // Reversed, with tabs or spaces between Roll and Word and surrounding whitespace trimmed.
        val text = rolls(4).reversed().joinToString("\n") { if (it.endsWith("1")) "  $it   x$it \r" else "$it\tx$it" }

        library.importText("dice.txt", text)

        assertEquals(ImportState.Imported("dice"), library.importState.value)
        val wordList = library.wordList(library.loaded().last().id)!!
        assertEquals(4, wordList.dicePerRoll)
        assertEquals("x1111", wordList.resolve(listOf(1, 1, 1, 1)))
        assertEquals("x3526", wordList.resolve(listOf(3, 5, 2, 6)))
        assertEquals("x6666", wordList.resolve(listOf(6, 6, 6, 6)))
    }

    @Test
    fun blankLinesAndAByteOrderMarkAreNotWords() = runTest {
        val library = library()

        library.importText("bom.txt", "﻿" + plain(4).replace("\nw5\n", "\nw5\n\n   \n") + "\n")

        assertEquals(ImportState.Imported("bom"), library.importState.value)
        assertEquals("w0", library.wordList(library.loaded().last().id)!!.resolve(listOf(1, 1, 1, 1)))
    }

    @Test
    fun rejectsAPlainListThatIsNotExactly6ToTheNWords() = runTest {
        assertRejected(ImportError.WrongWordCount(1295), plain(4).substringBeforeLast("\n"))
        assertRejected(ImportError.WrongWordCount(1297), plain(4) + "\nextra")
        // 6³ is a power of six but too few Words.
        assertRejected(ImportError.WrongWordCount(216), plain(3))
        assertRejected(ImportError.WrongWordCount(0), "\n  \n")
    }

    @Test
    fun rejectsADicewareListMissingARollNamingTheRollOrWithTooFewDice() = runTest {
        // One Roll left out: 1295 Words, no repeats.
        assertRejected(ImportError.RollNotFound("1111"), diceware(4).lines().drop(1).joinToString("\n"))
        assertRejected(
            ImportError.RollNotFound("1122"),
            diceware(4).lines().filterIndexed { i, _ -> i != 7 }.joinToString("\n"),
        )
        assertRejected(ImportError.RollNotFound("6666"), diceware(4).lines().dropLast(1).joinToString("\n"))
        assertRejected(ImportError.WrongWordCount(216), diceware(3))
    }

    @Test
    fun aRollMayHaveMoreThanTwentyDice() = runTest {
        // 6²⁵ overflows a Long: the missing Roll is still named, not the dice count refused.
        val ones = "1".repeat(24)
        assertRejected(ImportError.RollNotFound(ones + "3"), "${ones}1\ta\n${ones}2\tb")
    }

    @Test
    fun aFirstLineWithoutARollInAnOtherwiseDicewareListIsAMissingRoll() = runTest {
        assertRejected(ImportError.MissingRoll(1), "w0\n" + diceware(4).lines().drop(1).joinToString("\n"))
    }

    @Test
    fun rejectsDuplicateWordsIgnoringCaseNamingTheLine() = runTest {
        assertRejected(ImportError.DuplicateWord(11, "W3", 4), plain(4).replace("\nw10\n", "\nW3\n"))
        assertRejected(ImportError.DuplicateWord(11, "W3", 4), diceware(4).replace("\tw10\n", "\tW3\n"))
    }

    @Test
    fun rejectsWordsContainingWhitespace() = runTest {
        assertRejected(ImportError.WordHasWhitespace(8), plain(4).replace("\nw7\n", "\nice cream\n"))
        assertRejected(ImportError.WordHasWhitespace(8), diceware(4).replace("\tw7\n", "\tice cream\n"))
        // Unicode whitespace too: a no-break space and an em space.
        assertRejected(ImportError.WordHasWhitespace(8), plain(4).replace("\nw7\n", "\nice\u00A0cream\n"))
        assertRejected(ImportError.WordHasWhitespace(8), diceware(4).replace("\tw7\n", "\tice\u2003cream\n"))
    }

    @Test
    fun rejectsInvalidMixedLengthMissingAndRepeatedRolls() = runTest {
        assertRejected(ImportError.InvalidRoll(3), diceware(4).replace("1113\t", "1173\t"))
        assertRejected(ImportError.InvalidRoll(3), diceware(4).replace("1113\t", "x113\t"))
        assertRejected(ImportError.MixedRollLength(3), diceware(4).replace("1113\t", "11131\t"))
        assertRejected(ImportError.MissingRoll(3), diceware(4).replace("1113\t", ""))
        assertRejected(ImportError.RepeatedRoll(3, 2), diceware(4).replace("1113\t", "1112\t"))
    }

    @Test
    fun rejectsLinesTooLongToBeAWordAndTextThatIsNotUtf8() = runTest {
        assertRejected(ImportError.LineTooLong(2), plain(4).replace("\nw1\n", "\n" + "a".repeat(2000) + "\n"))
        val library = library()
        val bytes = Buffer().writeUtf8("w0\n").writeByte(0xC3).writeUtf8(plain(4).substringAfter("w0"))

        library.import("bad.txt", bytes.size) { bytes }

        assertEquals(ImportState.Failed(ImportError.NotText(2)), library.importState.value)
    }

    @Test
    fun aRejectedImportLeavesNothingBehind() = runTest {
        val library = library()
        library.loaded()
        val before = fileSystem.listRecursively("/lists".toPath()).toList()

        library.importText("bad.txt", plain(4).replace("\nw7\n", "\nw1\n"))

        assertEquals(ImportState.Failed(ImportError.DuplicateWord(8, "w1", 2)), library.importState.value)
        assertEquals(3, library.wordLists.value.size)
        assertEquals(before, fileSystem.listRecursively("/lists".toPath()).toList())
    }

    @Test
    fun theSameWordsImportedAgainAreRejectedAsAlreadyImported() = runTest {
        val library = library()
        library.importText("animals.txt", plain(4))

        // Same Words in Roll order, in Diceware format this time.
        library.importText("again.txt", diceware(4))

        assertEquals(ImportState.Failed(ImportError.AlreadyImported("animals")), library.importState.value)
        assertEquals(4, library.loaded().size)
    }

    @Test
    fun anEffWordListImportedAsACustomOneIsRejectedAsAlreadyThere() = runTest {
        assertRejected(ImportError.AlreadyImportedAsEff(EffWordList.SHORT_1), effText(EffWordList.SHORT_1))
    }

    @Test
    fun customWordListsAreThereInTheNextSessionAndCanBeRenamedAndDeleted() = runTest {
        val first = library()
        first.importText("animals.txt", plain(4))
        val id = first.loaded().last().id as CustomWordListId
        first.rename(id, "  Beasts ")
        first.wordLists.first { it.last().name == "Beasts" }

        // The first session's library still has the list open; the OS allows deleting it anyway.
        fileSystem.allowDeletingOpenFiles = true
        val second = library()
        assertEquals(listOf(null, null, null, "Beasts"), second.loaded().map { it.name })
        assertEquals("w7", second.wordList(id)!!.resolve(listOf(1, 1, 2, 2)))

        second.delete(id)

        second.wordLists.first { it.size == 3 }
        assertEquals(null, second.wordList(id))
        assertEquals(listOf("/lists/index.json".toPath()), fileSystem.list("/lists".toPath()))
        assertEquals(3, library().loaded().size)
    }

    @Test
    fun anIndexSavedByAnEarlierBuildStillLoads() = runTest {
        val first = library()
        first.importText("animals.txt", plain(4))
        val id = first.loaded().last().id as CustomWordListId
        // The index format so far: a Custom Word List has no "eff" field.
        fileSystem.write("/lists/index.json".toPath()) {
            writeUtf8("""[{"sha256":"${id.sha256}","name":"Beasts","dicePerRoll":4}]""")
        }

        fileSystem.allowDeletingOpenFiles = true
        val second = library()

        assertEquals("Beasts", second.loaded().last().name)
        assertEquals("w7", second.wordList(id)!!.resolve(listOf(1, 1, 2, 2)))
    }

    @Test
    fun aBlankNameIsIgnored() = runTest {
        val library = library()
        library.importText("animals.txt", plain(4))
        val id = library.loaded().last().id as CustomWordListId

        library.rename(id, "   ")
        testScheduler.advanceUntilIdle()

        assertEquals("animals", library.wordLists.value.last().name)
    }

    @Test
    fun aCanceledImportImportsNothing() = runTest {
        val library = library()
        library.loaded()
        val importing = launch { library.importText("big.txt", plain(6)) }
        library.importState.first { it is ImportState.Importing && it.progress > 0 }

        library.cancelImport()
        importing.join()

        assertEquals(ImportState.Idle, library.importState.value)
        assertEquals(3, library.wordLists.value.size)
        assertEquals(emptyList(), fileSystem.list("/lists".toPath()))
    }

    @Test
    fun runningOutOfSpaceFailsCleanlyWithNothingImported() = runTest {
        val full = object : ForwardingFileSystem(fileSystem) {
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                // The disk fills up once the import has written most of its files.
                if ("by-word" in file.toString()) throw IOException("No space left on device")
                return super.sink(file, mustCreate)
            }
        }
        val library = library(full)

        library.importText("big.txt", plain(6))

        assertEquals(ImportState.Failed(ImportError.StorageFailed), library.importState.value)
        assertEquals(3, library.loaded().size)
        assertEquals(emptyList(), fileSystem.list("/lists".toPath()))
    }

    @Test
    fun aFileThatCanNotBeReadIsRejectedAsUnreadable() = runTest {
        val library = library()

        library.import("gone.txt", -1) { throw IOException("revoked") }

        assertEquals(ImportState.Failed(ImportError.Unreadable), library.importState.value)
    }

    @Test
    fun a6To7WordListStreamsInAndEveryRollResolvesToItsWord() = runTest {
        val library = library()
        val count = 279_936 // 6⁷

        // Generated as it is read, so the test itself never holds the list either.
        library.import("huge.txt", -1) { GeneratedPlainList(count) }

        assertEquals(ImportState.Imported("huge"), library.importState.value)
        val info = library.loaded().last()
        assertEquals(count.toLong(), info.wordCount)
        val wordList = library.wordList(info.id)!!
        assertEquals("w0", wordList.resolve(List(7) { 1 }))
        assertEquals("w${count - 1}", wordList.resolve(List(7) { 6 }))
        // Roll 3416251 is the base-6 number 2305140 = 117,780.
        assertEquals("w117780", wordList.resolve(listOf(3, 4, 1, 6, 2, 5, 1)))
    }

    private suspend fun TestScope.assertRejected(expected: ImportError, text: String) {
        val library = library()
        library.importText("list.txt", text)
        assertEquals(ImportState.Failed(expected), library.importState.value)
    }

    private fun TestScope.library(fileSystem: FileSystem = this@WordListLibraryTest.fileSystem) = WordListLibrary(
        { EffWordList.entries.associateWith { WordList.parse(effText(it)) } },
        fileSystem,
        "/lists".toPath(),
        backgroundScope,
        StandardTestDispatcher(testScheduler),
    )

    private fun effText(list: EffWordList) = rolls(4).joinToString("\n") { "$it\t${list.name}$it" }

    private suspend fun WordListLibrary.loaded() = wordLists.first { it.isNotEmpty() }

    private suspend fun WordListLibrary.importText(fileName: String, text: String) =
        import(fileName, text.length.toLong()) { Buffer().writeUtf8(text) }

    /** One Word per line for 6ⁿ Words: w0, w1, … */
    private fun plain(dice: Int) = (0 until pow6(dice)).joinToString("\n") { "w$it" }

    /** Diceware format in Roll order: `1111\tw0`, `1112\tw1`, … */
    private fun diceware(dice: Int) = rolls(dice).withIndex().joinToString("\n") { (i, roll) -> "$roll\tw$i" }

    private fun pow6(n: Int) = (1..n).fold(1) { acc, _ -> acc * 6 }

    private fun rolls(dice: Int): List<String> =
        if (dice == 0) listOf("") else rolls(dice - 1).flatMap { prefix -> (1..6).map { prefix + it } }
}

/** A plain-format list of [count] Words (w0, w1, …) produced as it is read. */
private class GeneratedPlainList(private val count: Int) : Source {
    private var next = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (next == count) return -1
        val start = sink.size
        while (next < count && sink.size - start < byteCount - 16) sink.writeUtf8("w${next++}\n")
        return sink.size - start
    }

    override fun timeout() = Timeout.NONE

    override fun close() = Unit
}
