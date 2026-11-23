package com.adstro.diceware

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GeneratorTest {
    /** A Diceware-format 6⁴ Word List where each Word spells its own Roll, e.g. `1234\tw1234`. */
    private val rollNamedList: WordList = WordList.parse(
        allRolls(4).joinToString("\n") { "$it\tw$it" },
    )

    @Test
    fun opensWithSixWordPassphraseWhoseWordsAreTheRollsResolvedAgainstTheWordList() = runTest {
        // Hard rule (ADR 0002): one Roll of 4 dice per Word, nothing else drawn.
        val random = ScriptedRandom(
            *diceBytes(1, 1, 1, 1, 6, 6, 6, 6, 1, 2, 3, 4, 6, 5, 4, 3, 2, 5, 2, 5, 3, 1, 4, 1),
        )

        val ready = generator(random).ready()

        assertEquals("w1111 w6666 w1234 w6543 w2525 w3141", ready.passphrase)
        assertEquals(0, random.remaining)
    }

    @Test
    fun strengthOfSixWordsFromA6To4ListIsJustBelowTheWarningThreshold() = runTest {
        // 6 Words × 4 dice × log₂6 ≈ 62.04 bits.
        val ready = generator(ScriptedRandom(*diceBytes(*IntArray(24) { 1 }))).ready()

        assertEquals(62.04, ready.strengthBits, 0.01)
        assertEquals(true, ready.isWeak)
    }

    @Test
    fun regenerateRollsAFreshPassphrase() = runTest {
        val random = ScriptedRandom(*diceBytes(*IntArray(24) { 1 }), *diceBytes(*IntArray(24) { 2 }))
        val generator = generator(random)
        generator.ready()

        generator.regenerate()

        assertEquals(List(6) { "w2222" }.joinToString(" "), generator.ready().passphrase)
    }

    @Test
    fun showsNoPassphraseWhenSecureRandomnessFails() = runTest {
        val generator = generator(RandomBytes { throw RandomnessUnavailableException("broken") })

        assertIs<GeneratorState.RandomnessUnavailable>(generator.settled())
    }

    @Test
    fun aRandomnessFailureMidwayDiscardsTheWordsAlreadyRolled() = runTest {
        // Enough dice for two Words, then the generator breaks.
        val generator = generator(ScriptedRandom(*diceBytes(*IntArray(8) { 3 })))

        assertIs<GeneratorState.RandomnessUnavailable>(generator.settled())
    }

    @Test
    fun defaultsAreSixWordsSpaceSeparatedWithNoExtras() = runTest {
        val generator = generator(ScriptedRandom(*ones(24)))
        generator.ready()

        assertEquals(PassphraseOptions(), generator.options.value)
        assertEquals(
            PassphraseOptions(6, Separator.SPACE, capitalise = false, appendDigit = false, appendSymbol = false),
            generator.options.value,
        )
    }

    @Test
    fun changingTheWordCountRerollsWithThatManyWords() = runTest {
        val generator = generator(ScriptedRandom(*ones(24), *diceBytes(*IntArray(32) { 2 })))
        generator.ready()

        generator.setWordCount(8)

        val ready = generator.ready()
        assertEquals(List(8) { "w2222" }.joinToString(" "), ready.passphrase)
        // 8 × 4 × log₂6 ≈ 82.72 bits.
        assertEquals(82.72, ready.strengthBits, 0.01)
        assertEquals(false, ready.isWeak)
    }

    @Test
    fun wordCountStaysBetweenOneAndTwenty() = runTest {
        val generator = generator(ScriptedRandom(*ones(24 + 4 + 80)))
        generator.ready()

        generator.setWordCount(0)
        assertEquals(1, generator.options.value.wordCount)
        assertEquals("w1111", generator.ready().passphrase)

        generator.setWordCount(21)
        assertEquals(20, generator.options.value.wordCount)
    }

    @Test
    fun eachSeparatorJoinsTheWords() = runTest {
        val expected = mapOf(
            Separator.HYPHEN to "w1111-w1111",
            Separator.PERIOD to "w1111.w1111",
            Separator.UNDERSCORE to "w1111_w1111",
            Separator.NONE to "w1111w1111",
            Separator.SPACE to "w1111 w1111",
        )
        val generator = generator(ScriptedRandom(*ones(24 + 8 + expected.size * 8)))
        generator.ready()
        generator.setWordCount(2)

        for ((separator, passphrase) in expected) {
            generator.setSeparator(separator)
            assertEquals(passphrase, generator.ready().passphrase)
        }
    }

    @Test
    fun capitalisingUppercasesTheFirstLetterOfEveryWordAndAddsNoStrength() = runTest {
        val generator = generator(ScriptedRandom(*ones(48)))
        val before = generator.ready().strengthBits

        generator.setCapitalise(true)

        val ready = generator.ready()
        assertEquals(List(6) { "W1111" }.joinToString(" "), ready.passphrase)
        assertEquals(before, ready.strengthBits)
    }

    @Test
    fun appendedDigitComesFromATwoDiceRollAndRerollsTheSixUnmappedOutcomes() = runTest {
        // (6,1) is outcome 30 of 0–35: unmapped, so rolled again; (4,3) is outcome 20 → digit 6.
        val generator = generator(ScriptedRandom(*ones(24), *ones(24), *diceBytes(6, 1, 4, 3)))
        val before = generator.ready().strengthBits

        generator.setAppendDigit(true)

        val ready = generator.ready()
        assertEquals(List(6) { "w1111" }.joinToString(" ") + "6", ready.passphrase)
        assertEquals(before + 3.32, ready.strengthBits, 0.01)
    }

    @Test
    fun everyDigitHasExactlyThreeOfTheThirtyMappedOutcomes() = runTest {
        val outcomes = (1..6).flatMap { a -> (1..6).map { b -> listOf(a, b) } }.take(30)
        val generator = generator(
            ScriptedRandom(*ones(24 + 4), *outcomes.flatMap { ones(4).toList() + diceBytes(*it.toIntArray()).toList() }.toIntArray()),
        )
        generator.ready()
        generator.setWordCount(1)
        val digits = mutableListOf<Char>()

        generator.setAppendDigit(true)
        digits += generator.ready().passphrase.last()
        repeat(outcomes.size - 1) {
            generator.regenerate()
            digits += generator.ready().passphrase.last()
        }

        assertEquals("0123456789".flatMap { listOf(it, it, it) }, digits)
    }

    @Test
    fun appendedSymbolComesFromATwoDiceRollThreeOutcomesPerSymbol() = runTest {
        val outcomes = (1..6).flatMap { a -> (1..6).map { b -> listOf(a, b) } }
        val generator = generator(
            ScriptedRandom(*ones(24 + 4), *outcomes.flatMap { ones(4).toList() + diceBytes(*it.toIntArray()).toList() }.toIntArray()),
        )
        generator.ready()
        generator.setWordCount(1)
        val symbols = mutableListOf<Char>()

        generator.setAppendSymbol(true)
        symbols += generator.ready().passphrase.last()
        repeat(outcomes.size - 1) {
            generator.regenerate()
            symbols += generator.ready().passphrase.last()
        }

        assertEquals("!@#$%&*-_+=?".flatMap { listOf(it, it, it) }, symbols)
        // 1 × 4 × log₂6 + log₂12 ≈ 10.34 + 3.58.
        assertEquals(13.92, generator.ready().strengthBits, 0.01)
    }

    @Test
    fun digitThenSymbolAreAppendedAfterTheLastWord() = runTest {
        // Word count 1: (2,1) → outcome 6 → digit 2; (1,1) → outcome 0 → '!'.
        val generator = generator(ScriptedRandom(*ones(24 + 4 + 4 + 2), *ones(4), *diceBytes(2, 1, 1, 1)))
        generator.ready()
        generator.setWordCount(1)
        generator.setAppendDigit(true)

        generator.setAppendSymbol(true)

        val ready = generator.ready()
        assertEquals("w11112!", ready.passphrase)
        assertEquals(4 * 2.585 + 3.322 + 3.585, ready.strengthBits, 0.01)
    }

    @Test
    fun settingsAreRestoredInTheNextSession() = runTest {
        val fileSystem = FakeFileSystem()
        val firstSession = Job()
        val first = generator(ScriptedRandom(*ones(24 + 5 * 3 * 4 + 10)), fileSystem, CoroutineScope(coroutineContext + firstSession))
        first.ready()
        first.setWordCount(3)
        first.setSeparator(Separator.HYPHEN)
        first.setCapitalise(true)
        first.setAppendDigit(true)
        first.setAppendSymbol(true)
        testScheduler.advanceUntilIdle()
        firstSession.cancelAndJoin()

        val second = generator(ScriptedRandom(*ones(12 + 4)), fileSystem)

        assertEquals("W1111-W1111-W11110!", second.ready().passphrase)
        assertEquals(PassphraseOptions(3, Separator.HYPHEN, true, true, true), second.options.value)
    }

    private fun ones(dice: Int): IntArray = diceBytes(*IntArray(dice) { 1 })

    private fun TestScope.generator(
        random: RandomBytes,
        fileSystem: FakeFileSystem = FakeFileSystem(),
        scope: CoroutineScope = backgroundScope,
    ) = Generator(rollNamedList, random, settingsIn(fileSystem, scope), scope)

    /** DataStore Preferences over an in-memory file system, so "sessions" can share it. */
    private fun settingsIn(fileSystem: FakeFileSystem, scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            storage = OkioStorage(fileSystem, PreferencesSerializer) { "/settings.preferences_pb".toPath() },
            scope = scope,
        )

    private suspend fun Generator.settled(): GeneratorState = state.first { it != GeneratorState.Loading }

    private suspend fun Generator.ready(): GeneratorState.Ready = state.filterIsInstance<GeneratorState.Ready>().first()

    private fun allRolls(dice: Int): List<String> =
        if (dice == 0) listOf("") else allRolls(dice - 1).flatMap { prefix -> (1..6).map { prefix + it } }
}
