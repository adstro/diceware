package com.adstro.diceware

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.math.log2

sealed interface GeneratorState {
    /** Saved settings are still being read; nothing is rolled until they are known. */
    data object Loading : GeneratorState

    data class Ready(val passphrase: String, val strengthBits: Double) : GeneratorState {
        val isWeak: Boolean get() = strengthBits < WEAK_BELOW_BITS
    }

    /** Dice Mode: no Passphrase until the user has entered every Roll. */
    data object AwaitingDice : GeneratorState

    /** Secure randomness failed, so no Passphrase is shown (never a weaker fallback). */
    data object RandomnessUnavailable : GeneratorState
}

/** Strength below this many bits gets a warning. */
const val WEAK_BELOW_BITS = 64.0

/** Widely accepted by sites; 12 symbols so each gets exactly 3 of a two-dice Roll's 36 outcomes. */
private const val SYMBOLS = "!@#$%&*-_+=?"

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
    fun setCapitalise(on: Boolean) = update { it.copy(capitalise = on) }
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
        GeneratorState.Ready(draft.passphrase, draft.strengthBits)
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
            mutableState.value = GeneratorState.Ready(draft.passphrase, draft.strengthBits)
        } catch (e: NeedMoreDice) {
            mutableState.value = GeneratorState.AwaitingDice
        }
        mutableDiceEntry.value = draft.entry(roll = enteredDice.drop(draft.diceInCompleteRolls))
    }
}

private object NeedMoreDice : Exception()

/**
 * A Passphrase built Roll by Roll from a source of dice: the one Roll resolution for both modes (ADR 0002).
 * In Dice Mode the source runs out mid-way, and the draft then holds the Words built so far.
 */
private class Draft(private val options: PassphraseOptions, private val wordList: WordList) {
    val words = mutableListOf<String>()
    var digit: Int? = null
    var symbol: Char? = null
    var rerollDigit = false
    var diceInCompleteRolls = 0

    fun build(nextDie: () -> Int) {
        while (words.size < options.wordCount) {
            val word = wordList.resolve(List(wordList.dicePerRoll) { nextDie() })
            words += if (options.capitalise) word.replaceFirstChar { it.uppercase() } else word
            diceInCompleteRolls += wordList.dicePerRoll
        }
        // Outcomes 0–29 give each digit three chances; the other 6 are rolled again so no digit is favoured.
        while (options.appendDigit && digit == null) {
            val outcome = twoDiceOutcome(nextDie)
            if (outcome < 30) digit = outcome / 3 else rerollDigit = true
        }
        if (options.appendSymbol) symbol = SYMBOLS[twoDiceOutcome(nextDie) / 3]
    }

    val passphrase: String
        get() = words.joinToString(options.separator.text) + (digit?.toString() ?: "") + (symbol?.toString() ?: "")

    // Capitalisation is guessable, so it adds no Strength.
    val strengthBits: Double
        get() = options.wordCount * wordList.dicePerRoll * log2(6.0) +
            (if (options.appendDigit) log2(10.0) else 0.0) +
            (if (options.appendSymbol) log2(12.0) else 0.0)

    fun entry(roll: List<Int>): DiceEntryState {
        val step = when {
            words.size < options.wordCount -> DiceStep.WORD
            options.appendDigit && digit == null -> DiceStep.DIGIT
            options.appendSymbol && symbol == null -> DiceStep.SYMBOL
            else -> DiceStep.DONE
        }
        val diceInRoll = when (step) {
            DiceStep.WORD -> wordList.dicePerRoll
            DiceStep.DIGIT, DiceStep.SYMBOL -> 2
            DiceStep.DONE -> 0
        }
        return DiceEntryState(words.toList(), options.wordCount, step, diceInRoll, roll, step == DiceStep.DIGIT && rerollDigit)
    }

    /** A Roll of two dice as one of 36 equally likely outcomes, 0–35. */
    private fun twoDiceOutcome(nextDie: () -> Int): Int =
        ((nextDie() - 1) * 6 + (nextDie() - 1)).also { diceInCompleteRolls += 2 }
}

/** What the next Roll of Dice entry is for. */
enum class DiceStep { WORD, DIGIT, SYMBOL, DONE }

/**
 * Dice entry screen state: the Words whose Rolls are complete, and the dice entered so far for the current
 * Roll, which needs [diceInRoll] dice. [rerollDigit] asks for a new two-dice Roll after an unmapped digit outcome.
 */
data class DiceEntryState(
    val words: List<String> = emptyList(),
    val wordCount: Int = 0,
    val step: DiceStep = DiceStep.WORD,
    val diceInRoll: Int = 0,
    val roll: List<Int> = emptyList(),
    val rerollDigit: Boolean = false,
)

// DataStore allows one instance per file per process, but apps and tests may create several Generators.
// shortcut: not thread-safe, fine while Generators are only created on the main thread.
private val settingsByPath = mutableMapOf<String, DataStore<Preferences>>()

// Like settings, one library per directory per process, so two never write the same index.
// shortcut: not thread-safe, fine while Generators are only created on the main thread.
private val librariesByPath = mutableMapOf<String, WordListLibrary>()

/**
 * The production Generator: the bundled EFF Word Lists, whose Diceware-format text [readBundled] returns
 * (called off the main thread), plus Custom Word Lists in [wordListsPath], rolled by the OS secure random
 * generator, with settings saved in the DataStore Preferences file at [settingsPath] (ending `.preferences_pb`).
 */
fun createGenerator(
    readBundled: (EffWordList) -> String,
    settingsPath: String,
    wordListsPath: String,
    scope: CoroutineScope,
): Generator = Generator(
    librariesByPath.getOrPut(wordListsPath) {
        // The library outlives any one screen's scope: it is shared by every Generator in the process.
        WordListLibrary({ EffWordList.entries.associateWith { WordList.parse(readBundled(it)) } }, FileSystem.SYSTEM, wordListsPath.toPath(), MainScope())
    },
    secureRandomBytes(),
    settingsByPath.getOrPut(settingsPath) { PreferenceDataStoreFactory.createWithPath { settingsPath.toPath() } },
    scope,
)

/** For Swift, which can't make a [CoroutineScope]: the Generator lives on the main thread for the app's lifetime. */
fun createGenerator(readBundled: (EffWordList) -> String, settingsPath: String, wordListsPath: String): Generator =
    createGenerator(readBundled, settingsPath, wordListsPath, MainScope())
