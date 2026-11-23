package com.adstro.diceware

import kotlin.math.log2

/**
 * A Word List of 6ⁿ Words, each identified by a Roll of n dice.
 * The only way to get a Word out is [resolve] with a Roll (ADR 0002): there is deliberately no index access.
 * An interface so a disk-backed list for large Custom Word Lists can stand behind it.
 */
interface WordList {
    val dicePerRoll: Int

    fun resolve(roll: List<Int>): String

    companion object {
        /**
         * Parses the bundled EFF Word Lists (Diceware format: one `Roll<whitespace>Word` per line). They are small,
         * trusted app resources, so held in memory; imported lists go through the streamed validator instead.
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
            return InMemoryWordList(dicePerRoll, wordsByRoll)
        }
    }
}

private class InMemoryWordList(override val dicePerRoll: Int, private val wordsByRoll: Map<String, String>) : WordList {
    override fun resolve(roll: List<Int>): String {
        require(roll.size == dicePerRoll && roll.all { it in 1..6 }) { "A Roll needs $dicePerRoll dice, each 1-6: $roll" }
        return wordsByRoll.getValue(roll.joinToString(""))
    }
}

/** Identifies a Word List: one of the bundled [EffWordList]s or a [CustomWordListId]. */
sealed interface WordListId

/** The EFF Word Lists bundled with the app, in the order they are shown; [LARGE] is the default. */
enum class EffWordList(val fileName: String) : WordListId {
    LARGE("eff_large_wordlist.txt"),
    SHORT_1("eff_short_wordlist_1.txt"),
    SHORT_2("eff_short_wordlist_2_0.txt"),
}

/** A Custom Word List, identified by the SHA-256 of its Words in Roll order. */
data class CustomWordListId(val sha256: String) : WordListId

/** One row of the Word Lists screen; [name] is null for EFF Word Lists, which the apps name themselves. */
data class WordListInfo(val id: WordListId, val name: String?, val wordCount: Long, val strengthPerWordBits: Double)

internal fun wordListInfo(id: WordListId, name: String?, dicePerRoll: Int) =
    WordListInfo(id, name, pow6(dicePerRoll), dicePerRoll * log2(6.0))
