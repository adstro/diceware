package com.adstro.diceware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GeneratorTest {
    /** A Diceware-format 6⁴ Word List where each Word spells its own Roll, e.g. `1234\tw1234`. */
    private val rollNamedList: WordList = WordList.parse(
        allRolls(4).joinToString("\n") { "$it\tw$it" },
    )

    @Test
    fun opensWithSixWordPassphraseWhoseWordsAreTheRollsResolvedAgainstTheWordList() {
        // Hard rule (ADR 0002): one Roll of 4 dice per Word, nothing else drawn.
        val random = ScriptedRandom(
            *diceBytes(1, 1, 1, 1, 6, 6, 6, 6, 1, 2, 3, 4, 6, 5, 4, 3, 2, 5, 2, 5, 3, 1, 4, 1),
        )

        val generator = Generator(rollNamedList, random)

        assertEquals(
            GeneratorState.Ready("w1111 w6666 w1234 w6543 w2525 w3141"),
            generator.state.value,
        )
        assertEquals(0, random.remaining)
    }

    @Test
    fun regenerateRollsAFreshPassphrase() {
        val random = ScriptedRandom(*diceBytes(*IntArray(24) { 1 }), *diceBytes(*IntArray(24) { 2 }))
        val generator = Generator(rollNamedList, random)

        generator.regenerate()

        assertEquals(GeneratorState.Ready(List(6) { "w2222" }.joinToString(" ")), generator.state.value)
    }

    @Test
    fun showsNoPassphraseWhenSecureRandomnessFails() {
        val generator = Generator(rollNamedList, RandomBytes { throw RandomnessUnavailableException("broken") })

        assertIs<GeneratorState.RandomnessUnavailable>(generator.state.value)
    }

    @Test
    fun aRandomnessFailureMidwayDiscardsTheWordsAlreadyRolled() {
        // Enough dice for two Words, then the generator breaks.
        val generator = Generator(rollNamedList, ScriptedRandom(*diceBytes(*IntArray(8) { 3 })))

        assertIs<GeneratorState.RandomnessUnavailable>(generator.state.value)
    }

    private fun allRolls(dice: Int): List<String> =
        if (dice == 0) listOf("") else allRolls(dice - 1).flatMap { prefix -> (1..6).map { prefix + it } }
}
