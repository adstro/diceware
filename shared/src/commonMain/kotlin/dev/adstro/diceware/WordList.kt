package dev.adstro.diceware

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
                require(parts.size == 2 && isRoll(parts[0])) { "Not a Diceware line: $line" }
                parts[0] to parts[1]
            }
            val dicePerRoll = wordsByRoll.keys.firstOrNull()?.length ?: 0
            val expected = pow6(dicePerRoll)
            require(wordsByRoll.size.toLong() == expected && wordsByRoll.keys.all { it.length == dicePerRoll }) {
                "A Word List needs one Word for each of the $expected Rolls of $dicePerRoll dice"
            }
            return InMemoryWordList(dicePerRoll, wordsByRoll)
        }
    }
}

private class InMemoryWordList(override val dicePerRoll: Int, private val wordsByRoll: Map<String, String>) : WordList {
    override fun resolve(roll: List<Int>): String {
        requireRoll(roll, dicePerRoll)
        return wordsByRoll.getValue(roll.joinToString(""))
    }
}

/** Whether [text] is a Roll written out: one digit 1–6 per die. */
internal fun isRoll(text: String): Boolean = text.isNotEmpty() && text.all { it in '1'..'6' }

/** What every [WordList.resolve] checks first: [roll] is [dicePerRoll] dice, each 1–6. */
internal fun requireRoll(roll: List<Int>, dicePerRoll: Int) =
    require(roll.size == dicePerRoll && roll.all { it in 1..DIE_FACES }) {
        "A Roll needs $dicePerRoll dice, each 1-6: $roll"
    }

/** 6ⁿ: how many Rolls there are of [n] dice, and so how many Words a Word List with them has. */
internal fun pow6(n: Int): Long {
    var result = 1L
    repeat(n) { result *= DIE_FACES }
    return result
}

/** The Strength one Roll of [dicePerRoll] dice adds: log₂ of its 6ⁿ equally likely outcomes. */
internal fun bitsPerRoll(dicePerRoll: Int): Double = dicePerRoll * log2(DIE_FACES.toDouble())

/** Every Roll of [dice] dice in order, 11…1 to 66…6, counting up from the last like an odometer. */
internal fun rollsInOrder(dice: Int): Sequence<List<Int>> = sequence {
    val roll = IntArray(dice) { 1 }
    while (true) {
        yield(roll.toList())
        var i = dice - 1
        while (i >= 0 && roll[i] == DIE_FACES) roll[i--] = 1
        if (i < 0) break
        roll[i]++
    }
}

/** Identifies a Word List: one of the bundled [EffWordList]s or a [CustomWordListId]. */
sealed interface WordListId

/**
 * The EFF Word Lists bundled with the app, in the order they are shown; [LARGE] is the default.
 * [url] is where eff.org serves each: on www.eff.org, as eff.org itself redirects there, to another host.
 */
enum class EffWordList(val fileName: String, val url: String) : WordListId {
    LARGE("eff_large_wordlist.txt", "https://www.eff.org/files/2016/07/18/eff_large_wordlist.txt"),
    SHORT_1("eff_short_wordlist_1.txt", "https://www.eff.org/files/2016/09/08/eff_short_wordlist_1.txt"),
    SHORT_2("eff_short_wordlist_2_0.txt", "https://www.eff.org/files/2016/09/08/eff_short_wordlist_2_0.txt"),
}

/** A Custom Word List, identified by the SHA-256 of its Words in Roll order. */
data class CustomWordListId(val sha256: String) : WordListId

/**
 * One row of the Word Lists screen; [name] is null for EFF Word Lists, which the apps name themselves.
 * [updated] is true for an EFF Word List replaced by a newer copy from eff.org.
 */
data class WordListInfo(
    val id: WordListId,
    val name: String?,
    val wordCount: Long,
    val strengthPerWordBits: Double,
    val updated: Boolean = false,
)

internal fun wordListInfo(id: WordListId, name: String?, dicePerRoll: Int, updated: Boolean = false) =
    WordListInfo(id, name, pow6(dicePerRoll), bitsPerRoll(dicePerRoll), updated)
