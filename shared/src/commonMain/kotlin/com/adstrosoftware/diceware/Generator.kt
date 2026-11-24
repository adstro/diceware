package com.adstrosoftware.diceware

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface GeneratorState {
    /** Saved settings are still being read; nothing is rolled until they are known. */
    data object Loading : GeneratorState

    /** [words] are the Words as shown in [passphrase], in order, for screen readers to announce one by one. */
    data class Ready(val passphrase: String, val strengthBits: Double, val words: List<String>) : GeneratorState {
        val isWeak: Boolean get() = strengthBits < WEAK_BELOW_BITS

        /** The Passphrase as a screen reader announces it, one part at a time: each Word, then each appended character. */
        val parts: List<PassphrasePart>
            get() {
                val parts = mutableListOf<PassphrasePart>()
                var start = 0
                words.forEachIndexed { i, word ->
                    val end = if (i < words.lastIndex) passphrase.indexOf(words[i + 1], start + word.length) else start + word.length
                    parts += PassphrasePart(passphrase.substring(start, end), word)
                    start = end
                }
                passphrase.substring(start).forEach { parts += PassphrasePart(it.toString(), it.toString()) }
                return parts
            }
    }

    /** Dice Mode: no Passphrase until the user has entered every Roll. */
    data object AwaitingDice : GeneratorState

    /** Secure randomness failed, so no Passphrase is shown (never a weaker fallback). */
    data object RandomnessUnavailable : GeneratorState
}

/** Part of a Passphrase: [shown] is a Word with the Separator after it (or a digit or symbol), [spoken] just the Word. */
data class PassphrasePart(val shown: String, val spoken: String)

/** Strength below this many bits gets a warning. */
const val WEAK_BELOW_BITS = 64.0

/**
 * Generator screen state: once the saved [options] are read, a Passphrase is rolled;
 * a fresh one on [regenerate] and on every change of a setting, which is also saved.
 */
class Generator(
    /** Every Word List, and the Word Lists screen's state and actions. */
    val library: WordListLibrary,
    random: RandomBytes,
    private val settings: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val dice = Dice(random)
    private val mutableState = MutableStateFlow<GeneratorState>(GeneratorState.Loading)
    val state: StateFlow<GeneratorState> = mutableState.asStateFlow()

    // Rolls live only here, in memory: never saved or logged.
    private val enteredDice = mutableListOf<Int>()
    private val mutableDiceEntry = MutableStateFlow(DiceEntryState())
    val diceEntry: StateFlow<DiceEntryState> = mutableDiceEntry.asStateFlow()

    private val mutableOptions = MutableStateFlow(PassphraseOptions())
    val options: StateFlow<PassphraseOptions> = mutableOptions.asStateFlow()

    init {
        scope.launch {
            library.awaitLoaded()
            val saved = settings.data.first().toOptions()
            mutableOptions.value = if (library.wordList(saved.wordList) == null) saved.copy(wordList = EffWordList.LARGE) else saved
            regenerate()
            // A deleted Selected Word List falls back to EFF Large.
            library.wordLists.collect { lists ->
                if (lists.none { it.id == options.value.wordList }) setWordList(EffWordList.LARGE)
            }
        }
    }

    /** Random mode: a fresh Passphrase. Dice Mode: start the dice entry over. */
    fun regenerate() {
        val options = options.value
        enteredDice.clear()
        if (options.mode == Mode.DICE) {
            showEntered()
        } else {
            mutableState.value = roll(options)
        }
    }

    /** Dice Mode: opening dice entry continues an unfinished entry, or starts over once the Passphrase is complete. */
    fun startDiceEntry() {
        if (diceEntry.value.step == DiceStep.DONE) regenerate()
    }

    /** Dice Mode: adds one die face (1–6) to the current Roll; ignored once every Roll is entered. */
    fun enterDie(face: Int) {
        if (face !in 1..6 || diceEntry.value.step == DiceStep.DONE) return
        enteredDice += face
        showEntered()
    }

    /** Dice Mode: takes back the last die entered, reopening the previous Roll if the current one is empty. */
    fun removeLastDie() {
        if (enteredDice.removeLastOrNull() != null) showEntered()
    }

    fun setWordCount(count: Int) = update { it.copy(wordCount = count.coerceIn(PassphraseOptions.WORD_COUNTS)) }
    fun setSeparator(separator: Separator) = update { it.copy(separator = separator) }
    fun setCapitalize(on: Boolean) = update { it.copy(capitalize = on) }
    fun setAppendDigit(on: Boolean) = update { it.copy(appendDigit = on) }
    fun setAppendSymbol(on: Boolean) = update { it.copy(appendSymbol = on) }
    fun setWordList(wordList: WordListId) = update { it.copy(wordList = wordList) }
    fun setMode(mode: Mode) = update { it.copy(mode = mode) }

    private fun update(change: (PassphraseOptions) -> PassphraseOptions) {
        val options = change(options.value)
        mutableOptions.value = options
        regenerate()
        scope.launch { settings.edit { options.writeTo(it) } }
    }

    private fun roll(options: PassphraseOptions): GeneratorState = try {
        val draft = Draft(options, selectedWordList(options))
        draft.build(dice::roll)
        GeneratorState.Ready(draft.passphrase, draft.strengthBits, draft.words.toList())
    } catch (e: RandomnessUnavailableException) {
        GeneratorState.RandomnessUnavailable
    }

    // A list deleted a moment ago is replaced by EFF Large as soon as the deletion is seen.
    private fun selectedWordList(options: PassphraseOptions): WordList =
        library.wordList(options.wordList) ?: library.wordList(EffWordList.LARGE)!!

    /** Builds from the dice entered so far; whatever they don't cover yet is what entry asks for next. */
    private fun showEntered() {
        val options = options.value
        val draft = Draft(options, selectedWordList(options))
        var used = 0
        try {
            draft.build { enteredDice.getOrNull(used++) ?: throw NeedMoreDice }
            mutableState.value = GeneratorState.Ready(draft.passphrase, draft.strengthBits, draft.words.toList())
        } catch (e: NeedMoreDice) {
            mutableState.value = GeneratorState.AwaitingDice
        }
        mutableDiceEntry.value = draft.entry(roll = enteredDice.drop(draft.diceInCompleteRolls))
    }
}

private object NeedMoreDice : Exception()
