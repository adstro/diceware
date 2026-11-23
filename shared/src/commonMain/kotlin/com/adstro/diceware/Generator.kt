package com.adstro.diceware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface GeneratorState {
    data class Ready(val passphrase: String) : GeneratorState

    /** Secure randomness failed, so no Passphrase is shown (never a weaker fallback). */
    data object RandomnessUnavailable : GeneratorState
}

/** Generator screen state: a Passphrase rolled as soon as it is created, and a fresh one on [regenerate]. */
class Generator(
    private val wordList: WordList,
    random: RandomBytes,
    private val wordCount: Int = 6,
) {
    private val dice = Dice(random)
    private val mutableState = MutableStateFlow(roll())
    val state: StateFlow<GeneratorState> = mutableState.asStateFlow()

    fun regenerate() {
        mutableState.value = roll()
    }

    private fun roll(): GeneratorState = try {
        val words = List(wordCount) { wordList.resolve(List(wordList.dicePerRoll) { dice.roll() }) }
        GeneratorState.Ready(words.joinToString(" "))
    } catch (e: RandomnessUnavailableException) {
        GeneratorState.RandomnessUnavailable
    }
}

/** The production Generator: the given Diceware-format Word List rolled by the OS secure random generator. */
fun createGenerator(wordListText: String): Generator = Generator(WordList.parse(wordListText), secureRandomBytes())
