package dev.adstro.diceware

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
