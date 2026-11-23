package com.adstro.diceware

import kotlin.math.log2

/** Widely accepted by sites; 12 symbols so each gets exactly 3 of a two-dice Roll's 36 outcomes. */
private const val SYMBOLS = "!@#$%&*-_+=?"

/**
 * A Passphrase built Roll by Roll from a source of dice: the one Roll resolution for both modes (ADR 0002).
 * In Dice Mode the source runs out mid-way, and the draft then holds the Words built so far.
 */
internal class Draft(private val options: PassphraseOptions, private val wordList: WordList) {
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
        get() = options.wordCount * bitsPerRoll(wordList.dicePerRoll) +
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
