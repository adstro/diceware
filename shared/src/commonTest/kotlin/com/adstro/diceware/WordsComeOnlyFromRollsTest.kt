package com.adstro.diceware

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the hard rule (ADR 0002): every Word is `wordList.resolve(roll)` for the very dice rolled or entered
 * for it, in order, and the Word List is asked for nothing else. A Word picked any other way (one random
 * index, a shuffle, a second roll) breaks the match between dice consumed and Rolls resolved.
 */
class WordsComeOnlyFromRollsTest {
    /** Records every Roll it resolves; [WordList] has no other way to get a Word out. */
    private class RecordingWordList(override val dicePerRoll: Int) : WordList {
        val rolls = mutableListOf<List<Int>>()

        override fun resolve(roll: List<Int>): String {
            rolls += roll
            return "w" + roll.joinToString("")
        }
    }

    // 6 Words × 5 dice, every face used.
    private val faces = List(30) { (it * 5 + it / 6) % 6 + 1 }

    @Test
    fun inRandomModeEachWordIsTheResolutionOfTheNextDiceRolled() = runTest {
        val wordList = RecordingWordList(5)
        val random = ScriptedRandom(*diceBytes(*faces.toIntArray()))

        val passphrase = generator(wordList, random).state.filterIsInstance<GeneratorState.Ready>().first().passphrase

        val rolls = faces.chunked(5)
        assertEquals(rolls, wordList.rolls)
        assertEquals(rolls.map { "w" + it.joinToString("") }, passphrase.split(" "))
        assertEquals(0, random.remaining)
    }

    @Test
    fun inDiceModeEachWordIsTheResolutionOfTheDiceEntered() = runTest {
        val wordList = RecordingWordList(5)
        val generator = generator(wordList, ScriptedRandom())
        generator.state.first { it != GeneratorState.Loading }
        generator.setMode(Mode.DICE)
        wordList.rolls.clear()

        faces.forEach(generator::enterDie)

        val passphrase = (generator.state.value as GeneratorState.Ready).passphrase
        val rolls = faces.chunked(5)
        // Dice entry re-resolves the Rolls entered so far after each die; it never resolves anything else.
        assertEquals(rolls.toSet(), wordList.rolls.toSet())
        assertEquals(rolls.map { "w" + it.joinToString("") }, passphrase.split(" "))
    }

    private fun TestScope.generator(wordList: WordList, random: RandomBytes): Generator {
        val fileSystem = FakeFileSystem()
        return Generator(
            WordListLibrary({ EffWordList.entries.associateWith { wordList } }, fileSystem, "/lists".toPath(), backgroundScope, StandardTestDispatcher(testScheduler)),
            random,
            PreferenceDataStoreFactory.create(OkioStorage(fileSystem, PreferencesSerializer) { "/settings.preferences_pb".toPath() }, scope = backgroundScope),
            backgroundScope,
        )
    }
}
