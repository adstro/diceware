package com.adstro.diceware

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

/** How the user shapes a Passphrase; the defaults are the most memorable strong Passphrase. */
data class PassphraseOptions(
    val wordCount: Int = 6,
    val separator: Separator = Separator.SPACE,
    val capitalise: Boolean = false,
    val appendDigit: Boolean = false,
    val appendSymbol: Boolean = false,
    val wordList: EffWordList = EffWordList.LARGE,
) {
    companion object {
        val WORD_COUNTS = 1..20
    }
}

private val wordCountKey = intPreferencesKey("word_count")
private val separatorKey = stringPreferencesKey("separator")
private val capitaliseKey = booleanPreferencesKey("capitalise")
private val appendDigitKey = booleanPreferencesKey("append_digit")
private val appendSymbolKey = booleanPreferencesKey("append_symbol")
private val wordListKey = stringPreferencesKey("word_list")

/** Saved settings are untrusted on read: anything unknown or out of range falls back to the default. */
internal fun Preferences.toOptions(): PassphraseOptions {
    val defaults = PassphraseOptions()
    return PassphraseOptions(
        wordCount = this[wordCountKey]?.coerceIn(PassphraseOptions.WORD_COUNTS) ?: defaults.wordCount,
        separator = Separator.entries.firstOrNull { it.name == this[separatorKey] } ?: defaults.separator,
        capitalise = this[capitaliseKey] ?: defaults.capitalise,
        appendDigit = this[appendDigitKey] ?: defaults.appendDigit,
        appendSymbol = this[appendSymbolKey] ?: defaults.appendSymbol,
        wordList = EffWordList.entries.firstOrNull { it.name == this[wordListKey] } ?: defaults.wordList,
    )
}

internal fun PassphraseOptions.writeTo(preferences: androidx.datastore.preferences.core.MutablePreferences) {
    preferences[wordCountKey] = wordCount
    preferences[separatorKey] = separator.name
    preferences[capitaliseKey] = capitalise
    preferences[appendDigitKey] = appendDigit
    preferences[appendSymbolKey] = appendSymbol
    preferences[wordListKey] = wordList.name
}
