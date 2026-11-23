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
import okio.Path.Companion.toPath
import kotlin.math.log2

sealed interface GeneratorState {
    /** Saved settings are still being read; nothing is rolled until they are known. */
    data object Loading : GeneratorState

    data class Ready(val passphrase: String, val strengthBits: Double) : GeneratorState {
        val isWeak: Boolean get() = strengthBits < WEAK_BELOW_BITS
    }

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
    private val bundled: Map<EffWordList, WordList>,
    random: RandomBytes,
    private val settings: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val dice = Dice(random)
    private val mutableState = MutableStateFlow<GeneratorState>(GeneratorState.Loading)
    val state: StateFlow<GeneratorState> = mutableState.asStateFlow()

    private val mutableOptions = MutableStateFlow(PassphraseOptions())
    val options: StateFlow<PassphraseOptions> = mutableOptions.asStateFlow()

    /** Word Lists screen state: every Word List, in the order shown. */
    val wordLists: StateFlow<List<WordListInfo>> =
        MutableStateFlow(EffWordList.entries.map { bundled.getValue(it).info(it) }).asStateFlow()

    init {
        scope.launch {
            mutableOptions.value = settings.data.first().toOptions()
            regenerate()
        }
    }

    fun regenerate() {
        mutableState.value = roll(options.value)
    }

    fun setWordCount(count: Int) = update { it.copy(wordCount = count.coerceIn(PassphraseOptions.WORD_COUNTS)) }
    fun setSeparator(separator: Separator) = update { it.copy(separator = separator) }
    fun setCapitalise(on: Boolean) = update { it.copy(capitalise = on) }
    fun setAppendDigit(on: Boolean) = update { it.copy(appendDigit = on) }
    fun setAppendSymbol(on: Boolean) = update { it.copy(appendSymbol = on) }
    fun setWordList(wordList: EffWordList) = update { it.copy(wordList = wordList) }

    private fun update(change: (PassphraseOptions) -> PassphraseOptions) {
        val options = change(options.value)
        mutableOptions.value = options
        regenerate()
        scope.launch { settings.edit { options.writeTo(it) } }
    }

    private fun roll(options: PassphraseOptions): GeneratorState = try {
        val wordList = bundled.getValue(options.wordList)
        val words = List(options.wordCount) {
            val word = wordList.resolve(List(wordList.dicePerRoll) { dice.roll() })
            if (options.capitalise) word.replaceFirstChar { it.uppercase() } else word
        }
        var passphrase = words.joinToString(options.separator.text)
        // Capitalisation is guessable, so it adds no Strength.
        var bits = options.wordCount * wordList.dicePerRoll * log2(6.0)
        if (options.appendDigit) {
            passphrase += rollDigit()
            bits += log2(10.0)
        }
        if (options.appendSymbol) {
            passphrase += SYMBOLS[twoDiceOutcome() / 3]
            bits += log2(12.0)
        }
        GeneratorState.Ready(passphrase, bits)
    } catch (e: RandomnessUnavailableException) {
        GeneratorState.RandomnessUnavailable
    }

    /** Outcomes 0–29 give each digit three chances; the other 6 are rolled again so no digit is favoured. */
    private fun rollDigit(): Int {
        while (true) {
            val outcome = twoDiceOutcome()
            if (outcome < 30) return outcome / 3
        }
    }

    /** A Roll of two dice as one of 36 equally likely outcomes, 0–35. */
    private fun twoDiceOutcome(): Int = (dice.roll() - 1) * 6 + (dice.roll() - 1)
}

// DataStore allows one instance per file per process, but apps and tests may create several Generators.
// shortcut: not thread-safe, fine while Generators are only created on the main thread.
private val settingsByPath = mutableMapOf<String, DataStore<Preferences>>()

/**
 * The production Generator: the bundled EFF Word Lists, whose Diceware-format text [readBundled] returns,
 * rolled by the OS secure random generator, with settings saved in the DataStore Preferences file at
 * [settingsPath] (ending `.preferences_pb`).
 */
fun createGenerator(readBundled: (EffWordList) -> String, settingsPath: String, scope: CoroutineScope): Generator = Generator(
    EffWordList.entries.associateWith { WordList.parse(readBundled(it)) },
    secureRandomBytes(),
    settingsByPath.getOrPut(settingsPath) { PreferenceDataStoreFactory.createWithPath { settingsPath.toPath() } },
    scope,
)

/** For Swift, which can't make a [CoroutineScope]: the Generator lives on the main thread for the app's lifetime. */
fun createGenerator(readBundled: (EffWordList) -> String, settingsPath: String): Generator =
    createGenerator(readBundled, settingsPath, MainScope())
