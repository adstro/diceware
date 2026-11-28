package dev.adstro.diceware

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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
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
            PassphraseOptions(
                6,
                Separator.SPACE,
                capitalize = false,
                appendDigit = false,
                appendSymbol = false,
                wordList = EffWordList.LARGE,
            ),
            generator.options.value,
        )
    }

    @Test
    fun changingTheWordCountRerollsWithThatManyWords() = runTest {
        val generator = generator(ScriptedRandom(*ones(24), *diceBytes(*IntArray(32) { 2 })))
        generator.ready()

        generator.change(OptionChange.SetWordCount(8))

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

        generator.change(OptionChange.SetWordCount(0))
        assertEquals(1, generator.options.value.wordCount)
        assertEquals("w1111", generator.ready().passphrase)

        generator.change(OptionChange.SetWordCount(21))
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
        generator.change(OptionChange.SetWordCount(2))

        for ((separator, passphrase) in expected) {
            generator.change(OptionChange.SetSeparator(separator))
            assertEquals(passphrase, generator.ready().passphrase)
        }
    }

    @Test
    fun capitalizingUppercasesTheFirstLetterOfEveryWordAndAddsNoStrength() = runTest {
        val generator = generator(ScriptedRandom(*ones(48)))
        val before = generator.ready().strengthBits

        generator.change(OptionChange.SetCapitalize(true))

        val ready = generator.ready()
        assertEquals(List(6) { "W1111" }.joinToString(" "), ready.passphrase)
        assertEquals(before, ready.strengthBits)
    }

    @Test
    fun appendedDigitComesFromATwoDiceRollAndRerollsTheSixUnmappedOutcomes() = runTest {
        // (6,1) is outcome 30 of 0–35: unmapped, so rolled again; (4,3) is outcome 20 → digit 6.
        val generator = generator(ScriptedRandom(*ones(24), *ones(24), *diceBytes(6, 1, 4, 3)))
        val before = generator.ready().strengthBits

        generator.change(OptionChange.SetAppendDigit(true))

        val ready = generator.ready()
        assertEquals(List(6) { "w1111" }.joinToString(" ") + "6", ready.passphrase)
        assertEquals(before + 3.32, ready.strengthBits, 0.01)
    }

    @Test
    fun aPassphraseListsItsWordsAsShownSoScreenReadersCanAnnounceEachOne() = runTest {
        val generator = generator(
            ScriptedRandom(*ones(24), *ones(24), *diceBytes(1, 2, 3, 4, 6, 6, 6, 6, *IntArray(16) { 1 }, 4, 3)),
        )
        generator.ready()

        generator.change(OptionChange.SetCapitalize(true))
        generator.change(OptionChange.SetAppendDigit(true))

        val ready = generator.ready()
        assertEquals(listOf("W1234", "W6666") + List(4) { "W1111" }, ready.words)
        assertEquals("W1234 W6666 W1111 W1111 W1111 W11116", ready.passphrase)
    }

    @Test
    fun aPassphraseSplitsIntoPartsShownWithTheirSeparatorAndSpokenAsJustTheWordOrCharacter() {
        val ready = GeneratorState.Ready("Ab-zoom7!", 20.0, listOf("Ab", "zoom"))

        assertEquals(
            listOf(
                PassphrasePart("Ab-", "Ab"),
                PassphrasePart("zoom", "zoom"),
                PassphrasePart("7", "7"),
                PassphrasePart("!", "!"),
            ),
            ready.parts,
        )
        // No Separator: the Words simply follow each other.
        assertEquals(listOf("ab", "cd"), GeneratorState.Ready("abcd", 20.0, listOf("ab", "cd")).parts.map { it.shown })
    }

    @Test
    fun everyDigitHasExactlyThreeOfTheThirtyMappedOutcomes() = runTest {
        val outcomes = (1..6).flatMap { a -> (1..6).map { b -> listOf(a, b) } }.take(30)
        val generator = generator(
            ScriptedRandom(
                *ones(24 + 4),
                *outcomes.flatMap { ones(4).toList() + diceBytes(*it.toIntArray()).toList() }.toIntArray(),
            ),
        )
        generator.ready()
        generator.change(OptionChange.SetWordCount(1))
        val digits = mutableListOf<Char>()

        generator.change(OptionChange.SetAppendDigit(true))
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
            ScriptedRandom(
                *ones(24 + 4),
                *outcomes.flatMap { ones(4).toList() + diceBytes(*it.toIntArray()).toList() }.toIntArray(),
            ),
        )
        generator.ready()
        generator.change(OptionChange.SetWordCount(1))
        val symbols = mutableListOf<Char>()

        generator.change(OptionChange.SetAppendSymbol(true))
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
        generator.change(OptionChange.SetWordCount(1))
        generator.change(OptionChange.SetAppendDigit(true))

        generator.change(OptionChange.SetAppendSymbol(true))

        val ready = generator.ready()
        assertEquals("w11112!", ready.passphrase)
        assertEquals(4 * 2.585 + 3.322 + 3.585, ready.strengthBits, 0.01)
    }

    @Test
    fun settingsAreRestoredInTheNextSession() = runTest {
        val fileSystem = FakeFileSystem()
        val firstSession = Job()
        val first = generator(
            ScriptedRandom(*ones(24 + 5 * 3 * 4 + 10)),
            fileSystem,
            CoroutineScope(coroutineContext + firstSession),
        )
        first.ready()
        first.change(OptionChange.SetWordCount(3))
        first.change(OptionChange.SetSeparator(Separator.HYPHEN))
        first.change(OptionChange.SetCapitalize(true))
        first.change(OptionChange.SetAppendDigit(true))
        first.change(OptionChange.SetAppendSymbol(true))
        testScheduler.advanceUntilIdle()
        firstSession.cancelAndJoin()

        val second = generator(ScriptedRandom(*ones(12 + 4)), fileSystem)

        assertEquals("W1111-W1111-W11110!", second.ready().passphrase)
        assertEquals(PassphraseOptions(3, Separator.HYPHEN, true, true, true), second.options.value)
    }

    @Test
    fun effLargeIsTheDefaultSelectedWordList() = runTest {
        val generator = generator(ScriptedRandom(*ones(30)), wordLists = effShaped)

        val ready = generator.ready()
        assertEquals(EffWordList.LARGE, generator.options.value.wordList)
        assertEquals(List(6) { "large11111" }.joinToString(" "), ready.passphrase)
    }

    @Test
    fun switchingToAShortWordListRerollsWithFourDicePerWord() = runTest {
        val random = ScriptedRandom(*ones(30), *diceBytes(*IntArray(24) { 2 }))
        val generator = generator(random, wordLists = effShaped)
        generator.ready()

        generator.change(OptionChange.SetWordList(EffWordList.SHORT_2))

        val ready = generator.ready()
        assertEquals(EffWordList.SHORT_2, generator.options.value.wordList)
        assertEquals(List(6) { "short2_2222" }.joinToString(" "), ready.passphrase)
        // 6 × 4 × log₂6 ≈ 62.04 bits.
        assertEquals(62.04, ready.strengthBits, 0.01)
        assertEquals(0, random.remaining)
    }

    @Test
    fun switchingBackToEffLargeRerollsWithFiveDicePerWord() = runTest {
        val random = ScriptedRandom(*ones(30), *ones(24), *diceBytes(*IntArray(30) { 3 }))
        val generator = generator(random, wordLists = effShaped)
        generator.ready()
        generator.change(OptionChange.SetWordList(EffWordList.SHORT_1))

        generator.change(OptionChange.SetWordList(EffWordList.LARGE))

        val ready = generator.ready()
        assertEquals(List(6) { "large33333" }.joinToString(" "), ready.passphrase)
        // 6 × 5 × log₂6 ≈ 77.55 bits.
        assertEquals(77.55, ready.strengthBits, 0.01)
        assertEquals(0, random.remaining)
    }

    @Test
    fun selectedWordListIsRestoredInTheNextSession() = runTest {
        val fileSystem = FakeFileSystem()
        val firstSession = Job()
        val first = generator(
            ScriptedRandom(*ones(30 + 24)),
            fileSystem,
            CoroutineScope(coroutineContext + firstSession),
            effShaped,
        )
        first.ready()
        first.change(OptionChange.SetWordList(EffWordList.SHORT_1))
        testScheduler.advanceUntilIdle()
        firstSession.cancelAndJoin()

        val second = generator(ScriptedRandom(*ones(24)), fileSystem, wordLists = effShaped)

        assertEquals(List(6) { "short1_1111" }.joinToString(" "), second.ready().passphrase)
        assertEquals(EffWordList.SHORT_1, second.options.value.wordList)
    }

    @Test
    fun wordListsShowEachEffWordListWithItsWordCountAndStrengthPerWord() = runTest {
        val wordLists = generator(ScriptedRandom(), wordLists = effShaped).library.wordLists.first { it.isNotEmpty() }

        assertEquals(listOf(EffWordList.LARGE, EffWordList.SHORT_1, EffWordList.SHORT_2), wordLists.map { it.id })
        assertEquals(listOf(7776L, 1296L, 1296L), wordLists.map { it.wordCount })
        // log₂6⁵ ≈ 12.92 and log₂6⁴ ≈ 10.34 bits per Word.
        assertEquals(12.92, wordLists[0].strengthPerWordBits, 0.01)
        assertEquals(10.34, wordLists[1].strengthPerWordBits, 0.01)
        assertEquals(10.34, wordLists[2].strengthPerWordBits, 0.01)
    }

    @Test
    fun randomIsTheDefaultModeAndDiceModeShowsNoPassphraseUntilDiceAreEntered() = runTest {
        val random = ScriptedRandom(*ones(24))
        val generator = generator(random)
        assertEquals(Mode.RANDOM, generator.options.value.mode)
        generator.ready()

        generator.change(OptionChange.SetMode(Mode.DICE))

        assertEquals(Mode.DICE, generator.options.value.mode)
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)
        // Dice Mode never tops up with the app's random generator.
        assertEquals(0, random.remaining)
    }

    @Test
    fun diceModeIsRestoredInTheNextSession() = runTest {
        val fileSystem = FakeFileSystem()
        val firstSession = Job()
        val first = generator(ScriptedRandom(*ones(24)), fileSystem, CoroutineScope(coroutineContext + firstSession))
        first.ready()
        first.change(OptionChange.SetMode(Mode.DICE))
        testScheduler.advanceUntilIdle()
        firstSession.cancelAndJoin()

        val second = generator(ScriptedRandom(), fileSystem)

        assertIs<GeneratorState.AwaitingDice>(second.settled())
        assertEquals(Mode.DICE, second.options.value.mode)
    }

    @Test
    fun diceEntryAsksForOneRollOfFourDicePerWordAndShowsEachWordOnceItsRollIsComplete() = runTest {
        val generator = diceMode(wordCount = 2)
        assertEquals(DiceEntryState(wordCount = 2, step = DiceStep.WORD, diceInRoll = 4), generator.diceEntry.value)

        generator.enterDice(1, 2, 3)
        assertEquals(listOf(1, 2, 3), generator.diceEntry.value.roll)
        assertEquals(emptyList(), generator.diceEntry.value.words)

        generator.enterDice(4)
        assertEquals(listOf("w1234"), generator.diceEntry.value.words)
        assertEquals(emptyList(), generator.diceEntry.value.roll)
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)

        generator.enterDice(6, 6, 6, 6)

        val entry = generator.diceEntry.value
        assertEquals(listOf("w1234", "w6666"), entry.words)
        assertEquals(DiceStep.DONE, entry.step)
        assertEquals(0, entry.diceInRoll)
        val ready = assertIs<GeneratorState.Ready>(generator.state.value)
        assertEquals("w1234 w6666", ready.passphrase)
        // 2 × 4 × log₂6.
        assertEquals(20.68, ready.strengthBits, 0.01)
    }

    @Test
    fun aMistypedDieCanBeRemovedAndReEnteredEvenAfterItsWordIsShown() = runTest {
        val generator = diceMode(wordCount = 2)
        generator.enterDice(1, 2, 3, 5)
        assertEquals(listOf("w1235"), generator.diceEntry.value.words)

        generator.removeLastDie()

        assertEquals(emptyList(), generator.diceEntry.value.words)
        assertEquals(listOf(1, 2, 3), generator.diceEntry.value.roll)
        generator.enterDice(4, 2, 2, 2, 2)
        assertEquals("w1234 w2222", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)

        // Correcting after the last Roll takes the Passphrase away again until it is complete.
        generator.removeLastDie()
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)
        generator.enterDie(3)
        assertEquals("w1234 w2223", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)
    }

    @Test
    fun removingADieWithNoneEnteredDoesNothing() = runTest {
        val generator = diceMode(wordCount = 1)

        generator.removeLastDie()

        assertEquals(DiceEntryState(wordCount = 1, step = DiceStep.WORD, diceInRoll = 4), generator.diceEntry.value)
    }

    @Test
    fun theDigitTakesATwoDiceRollAndAnUnmappedOutcomePromptsARollAgain() = runTest {
        val generator = diceMode(wordCount = 1)
        generator.change(OptionChange.SetAppendDigit(true))
        generator.enterDice(1, 1, 1, 1)
        assertEquals(DiceEntryState(listOf("w1111"), 1, DiceStep.DIGIT, diceInRoll = 2), generator.diceEntry.value)

        // (6,1) is outcome 30 of 0–35: no digit, so the user rolls the two dice again.
        generator.enterDice(6, 1)
        assertEquals(
            DiceEntryState(listOf("w1111"), 1, DiceStep.DIGIT, diceInRoll = 2, rerollDigit = true),
            generator.diceEntry.value,
        )
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)

        // (4,3) is outcome 20 → digit 6.
        generator.enterDice(4, 3)
        assertEquals(DiceStep.DONE, generator.diceEntry.value.step)
        assertEquals(false, generator.diceEntry.value.rerollDigit)
        val ready = assertIs<GeneratorState.Ready>(generator.state.value)
        assertEquals("w11116", ready.passphrase)
        assertEquals(4 * 2.585 + 3.322, ready.strengthBits, 0.01)
    }

    @Test
    fun theSymbolTakesATwoDiceRollThreeOutcomesPerSymbolAfterTheDigit() = runTest {
        val generator = diceMode(wordCount = 1)
        generator.change(OptionChange.SetAppendDigit(true))
        generator.change(OptionChange.SetAppendSymbol(true))
        generator.enterDice(2, 2, 2, 2, 2, 1)
        assertEquals(DiceStep.SYMBOL, generator.diceEntry.value.step)
        assertEquals(2, generator.diceEntry.value.diceInRoll)

        // (6,6) is outcome 35 → the 12th symbol, '?'.
        generator.enterDice(6, 6)

        assertEquals("w22222?", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)
    }

    @Test
    fun everySymbolHasExactlyThreeOfTheThirtySixTwoDiceOutcomes() = runTest {
        val generator = diceMode(wordCount = 1)
        generator.change(OptionChange.SetAppendSymbol(true))
        val symbols = (1..6).flatMap { a ->
            (1..6).map { b ->
                generator.regenerate()
                generator.enterDice(1, 1, 1, 1, a, b)
                assertIs<GeneratorState.Ready>(generator.state.value).passphrase.last()
            }
        }

        assertEquals("!@#$%&*-_+=?".flatMap { listOf(it, it, it) }, symbols)
    }

    @Test
    fun diceBeyondTheLastRollAreIgnoredAndFacesOutside1To6AreRejected() = runTest {
        val generator = diceMode(wordCount = 1)
        generator.enterDice(0, 7, 1, 2, 3, 4)

        generator.enterDie(5)

        assertEquals("w1234", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)
        assertEquals(emptyList(), generator.diceEntry.value.roll)
    }

    @Test
    fun changingAnySettingResetsTheDiceEntry() = runTest {
        val generator = diceMode(wordCount = 2)
        val changes = listOf(
            OptionChange.SetWordCount(3),
            OptionChange.SetSeparator(Separator.HYPHEN),
            OptionChange.SetCapitalize(true),
            OptionChange.SetAppendDigit(true),
            OptionChange.SetAppendSymbol(true),
            OptionChange.SetWordList(EffWordList.SHORT_1),
        )

        for (change in changes) {
            generator.enterDice(1, 2, 3, 4, 5)
            generator.change(change)
            assertEquals(emptyList(), generator.diceEntry.value.words)
            assertEquals(emptyList(), generator.diceEntry.value.roll)
            assertIs<GeneratorState.AwaitingDice>(generator.state.value)
        }
    }

    @Test
    fun startingDiceEntryKeepsAnUnfinishedEntryButStartsOverAFinishedOne() = runTest {
        val generator = diceMode(wordCount = 1)
        generator.enterDice(1, 2)

        generator.startDiceEntry()
        assertEquals(listOf(1, 2), generator.diceEntry.value.roll)

        generator.enterDice(3, 4)
        generator.startDiceEntry()
        assertEquals(DiceEntryState(wordCount = 1, step = DiceStep.WORD, diceInRoll = 4), generator.diceEntry.value)
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)
    }

    @Test
    fun eachRollTakesAsManyDiceAsTheSelectedWordListNeedsFourFiveOrSix() = runTest {
        val generator = diceMode(
            wordCount = 1,
            wordLists = mapOf(
                EffWordList.LARGE to rollNamed(5, "large"),
                EffWordList.SHORT_1 to rollNamed(4, "short1_"),
                // Stands in for a 6⁶ Custom Word List.
                EffWordList.SHORT_2 to rollNamed(6, "six"),
            ),
        )
        assertEquals(5, generator.diceEntry.value.diceInRoll)
        generator.enterDice(1, 2, 3, 4, 5)
        assertEquals("large12345", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)

        generator.change(OptionChange.SetWordList(EffWordList.SHORT_1))
        assertEquals(4, generator.diceEntry.value.diceInRoll)
        generator.enterDice(6, 5, 4, 3)
        assertEquals("short1_6543", assertIs<GeneratorState.Ready>(generator.state.value).passphrase)

        generator.change(OptionChange.SetWordList(EffWordList.SHORT_2))
        assertEquals(6, generator.diceEntry.value.diceInRoll)
        generator.enterDice(1, 2, 3, 4, 5)
        assertIs<GeneratorState.AwaitingDice>(generator.state.value)
        generator.enterDie(6)
        val ready = assertIs<GeneratorState.Ready>(generator.state.value)
        assertEquals("six123456", ready.passphrase)
        // 1 × 6 × log₂6.
        assertEquals(15.51, ready.strengthBits, 0.01)
    }

    @Test
    fun switchingBackToRandomModeRollsWithTheSecureGeneratorAgain() = runTest {
        val generator = generator(ScriptedRandom(*ones(24), *diceBytes(*IntArray(24) { 5 })))
        generator.ready()
        generator.change(OptionChange.SetMode(Mode.DICE))
        generator.enterDice(1, 2)

        generator.change(OptionChange.SetMode(Mode.RANDOM))

        assertEquals(
            List(6) { "w5555" }.joinToString(" "),
            assertIs<GeneratorState.Ready>(generator.state.value).passphrase,
        )
    }

    @Test
    fun choosingACustomWordListRollsItsWordsWithItsDicePerRoll() = runTest {
        val random = ScriptedRandom(*ones(24), *diceBytes(*IntArray(30) { 2 }))
        val generator = generator(random)
        generator.ready()
        val id = generator.importCustom(5, "custom")

        generator.change(OptionChange.SetWordList(id))

        val ready = generator.ready()
        assertEquals(List(6) { "custom22222" }.joinToString(" "), ready.passphrase)
        assertEquals(77.55, ready.strengthBits, 0.01)
        assertEquals(0, random.remaining)
    }

    @Test
    fun aSelectedCustomWordListIsRestoredInTheNextSession() = runTest {
        val fileSystem = FakeFileSystem()
        // The first session keeps its list open; the OS lets the next one manage it regardless.
        fileSystem.allowDeletingOpenFiles = true
        val firstSession = Job()
        val first = generator(
            ScriptedRandom(*ones(24 + 30)),
            fileSystem,
            CoroutineScope(coroutineContext + firstSession),
        )
        first.ready()
        first.change(OptionChange.SetWordList(first.importCustom(5, "custom")))
        testScheduler.advanceUntilIdle()
        firstSession.cancelAndJoin()

        val second = generator(ScriptedRandom(*ones(30)), fileSystem)

        assertEquals(List(6) { "custom11111" }.joinToString(" "), second.ready().passphrase)
    }

    @Test
    fun deletingTheSelectedWordListFallsBackToEffLarge() = runTest {
        val generator = generator(ScriptedRandom(*ones(30 + 24 + 30)), wordLists = effShaped)
        generator.ready()
        val id = generator.importCustom(4, "custom")
        generator.change(OptionChange.SetWordList(id))

        generator.library.delete(id)

        val ready = generator.state.filterIsInstance<GeneratorState.Ready>().first { it.passphrase.startsWith("large") }
        assertEquals(List(6) { "large11111" }.joinToString(" "), ready.passphrase)
        assertEquals(EffWordList.LARGE, generator.options.value.wordList)
    }

    /** Imports a Diceware-format Custom Word List whose Words name their Roll, e.g. `custom11111`. */
    private suspend fun Generator.importCustom(dice: Int, prefix: String): CustomWordListId {
        val text = allRolls(dice).joinToString("\n") { "$it\t$prefix$it" }
        library.import("$prefix.txt", text.length.toLong()) { Buffer().writeUtf8(text) }
        assertEquals(ImportState.Imported(prefix), library.importState.value)
        return library.wordLists.value.last().id as CustomWordListId
    }

    /** Shaped like the EFF Word Lists (Large 6⁵, Shorts 6⁴); each Word names its list and Roll. */
    private val effShaped: Map<EffWordList, WordList> = mapOf(
        EffWordList.LARGE to rollNamed(5, "large"),
        EffWordList.SHORT_1 to rollNamed(4, "short1_"),
        EffWordList.SHORT_2 to rollNamed(4, "short2_"),
    )

    private fun rollNamed(dice: Int, prefix: String): WordList =
        WordList.parse(allRolls(dice).joinToString("\n") { "$it\t$prefix$it" })

    /**
     * A Generator in Dice Mode with [wordCount] Words; its random source is empty, so any random Roll fails the
     * test.
     */
    private suspend fun TestScope.diceMode(
        wordCount: Int,
        wordLists: Map<EffWordList, WordList> = EffWordList.entries.associateWith { rollNamedList },
    ): Generator {
        val generator = generator(ScriptedRandom(), wordLists = wordLists)
        generator.settled()
        generator.change(OptionChange.SetMode(Mode.DICE))
        generator.change(OptionChange.SetWordCount(wordCount))
        return generator
    }

    private fun Generator.enterDice(vararg faces: Int) = faces.forEach { enterDie(it) }

    private fun ones(dice: Int): IntArray = diceBytes(*IntArray(dice) { 1 })

    private fun TestScope.generator(
        random: RandomBytes,
        fileSystem: FakeFileSystem = FakeFileSystem(),
        scope: CoroutineScope = backgroundScope,
        wordLists: Map<EffWordList, WordList> = EffWordList.entries.associateWith { rollNamedList },
    ) = Generator(
        WordListLibrary({ wordLists }, fileSystem, "/lists".toPath(), scope, StandardTestDispatcher(testScheduler)),
        random,
        settingsIn(fileSystem, scope),
        scope,
    )

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
