package com.adstrosoftware.diceware

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** The character placed between Words. */
enum class Separator(val text: String) {
    SPACE(" "),
    HYPHEN("-"),
    PERIOD("."),
    UNDERSCORE("_"),
    NONE(""),
}

/** Where the dice come from: the secure random generator, or physical dice the user enters (Dice Mode). */
enum class Mode { RANDOM, DICE }

/** How the user shapes a Passphrase; the defaults are the most memorable strong Passphrase. */
data class PassphraseOptions(
    val wordCount: Int = 6,
    val separator: Separator = Separator.SPACE,
    val capitalize: Boolean = false,
    val appendDigit: Boolean = false,
    val appendSymbol: Boolean = false,
    val wordList: WordListId = EffWordList.LARGE,
    val mode: Mode = Mode.RANDOM,
) {
    companion object {
        val WORD_COUNTS = 1..20
    }
}

private val wordCountKey = intPreferencesKey("word_count")
private val separatorKey = stringPreferencesKey("separator")
private val capitalizeKey = booleanPreferencesKey("capitalize")
private val appendDigitKey = booleanPreferencesKey("append_digit")
private val appendSymbolKey = booleanPreferencesKey("append_symbol")
private const val CUSTOM_PREFIX = "custom:"
private val wordListKey = stringPreferencesKey("word_list")
private val modeKey = stringPreferencesKey("mode")

/** Saved settings are untrusted on read: anything unknown or out of range falls back to the default. */
internal fun Preferences.toOptions(): PassphraseOptions {
    val defaults = PassphraseOptions()
    return PassphraseOptions(
        wordCount = this[wordCountKey]?.coerceIn(PassphraseOptions.WORD_COUNTS) ?: defaults.wordCount,
        separator = Separator.entries.firstOrNull { it.name == this[separatorKey] } ?: defaults.separator,
        capitalize = this[capitalizeKey] ?: defaults.capitalize,
        appendDigit = this[appendDigitKey] ?: defaults.appendDigit,
        appendSymbol = this[appendSymbolKey] ?: defaults.appendSymbol,
        wordList = this[wordListKey]?.let { saved ->
            EffWordList.entries.firstOrNull { it.name == saved }
                ?: saved.removePrefix(CUSTOM_PREFIX).takeIf { saved.startsWith(CUSTOM_PREFIX) }?.let(::CustomWordListId)
        } ?: defaults.wordList,
        mode = Mode.entries.firstOrNull { it.name == this[modeKey] } ?: defaults.mode,
    )
}

internal fun PassphraseOptions.writeTo(preferences: androidx.datastore.preferences.core.MutablePreferences) {
    preferences[wordCountKey] = wordCount
    preferences[separatorKey] = separator.name
    preferences[capitalizeKey] = capitalize
    preferences[appendDigitKey] = appendDigit
    preferences[appendSymbolKey] = appendSymbol
    preferences[wordListKey] = when (val id = wordList) {
        is EffWordList -> id.name
        is CustomWordListId -> CUSTOM_PREFIX + id.sha256
    }
    preferences[modeKey] = mode.name
}
