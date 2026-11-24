package com.adstrosoftware.diceware

import kotlin.test.Test
import kotlin.test.assertEquals

class DiceTest {
    @Test
    fun eachDieIsOneToSixAndBytesThatWouldBiasTheFaceAreRejected() {
        // 252..255 would give faces 1..4 a fifth chance out of 256, so they are re-drawn.
        val random = ScriptedRandom(0, 5, 251, 252, 253, 254, 255, 3)
        val dice = Dice(random)

        assertEquals(listOf(1, 6, 6, 4), List(4) { dice.roll() })
        assertEquals(0, random.remaining)
    }
}
