package com.adstro.diceware

/**
 * A Word List of 6ⁿ Words, each identified by a Roll of n dice.
 * The only way to get a Word out is [resolve] with a Roll (ADR 0002): there is deliberately no index access.
 */
class WordList private constructor(
    val dicePerRoll: Int,
    private val wordsByRoll: Map<String, String>,
) {
    fun resolve(roll: List<Int>): String {
        require(roll.size == dicePerRoll && roll.all { it in 1..6 }) { "A Roll needs $dicePerRoll dice, each 1-6: $roll" }
        return wordsByRoll.getValue(roll.joinToString(""))
    }

    companion object {
        /**
         * Parses Diceware format: one `Roll<whitespace>Word` per line.
         * shortcut: holds the list in memory and does minimal validation; the streamed parser/validator replaces this.
         */
        fun parse(text: String): WordList {
            val wordsByRoll = text.lineSequence().filter { it.isNotBlank() }.associate { line ->
                val parts = line.trim().split(Regex("\\s+"))
                require(parts.size == 2 && parts[0].all { it in '1'..'6' }) { "Not a Diceware line: $line" }
                parts[0] to parts[1]
            }
            val dicePerRoll = wordsByRoll.keys.firstOrNull()?.length ?: 0
            val expected = (1..dicePerRoll).fold(1) { acc, _ -> acc * 6 }
            require(wordsByRoll.size == expected && wordsByRoll.keys.all { it.length == dicePerRoll }) {
                "A Word List needs one Word for each of the $expected Rolls of $dicePerRoll dice"
            }
            return WordList(dicePerRoll, wordsByRoll)
        }
    }
}
