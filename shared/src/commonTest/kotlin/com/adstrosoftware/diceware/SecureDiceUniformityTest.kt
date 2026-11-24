package com.adstrosoftware.diceware

import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs against the real platform generator (SecureRandom on JVM/Android, SecRandomCopyBytes on iOS). */
class SecureDiceUniformityTest {
    @Test
    fun dieFacesFromTheSecureGeneratorAreUniform() {
        val rolls = 60_000
        val dice = Dice(secureRandomBytes())
        val counts = IntArray(6)
        repeat(rolls) { counts[dice.roll() - 1]++ }

        val expected = rolls / 6.0
        val chiSquare = counts.sumOf { (it - expected) * (it - expected) / expected }
        // Critical value for 5 degrees of freedom at p = 0.0001: a fair die fails 1 run in 10,000.
        assertTrue(chiSquare < 25.745, "Die faces not uniform: counts=${counts.toList()}, chi²=$chiSquare")
    }
}
