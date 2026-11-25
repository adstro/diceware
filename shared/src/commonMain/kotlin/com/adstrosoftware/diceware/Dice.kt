package com.adstrosoftware.diceware

/** A source of cryptographically secure random bytes. Throws [RandomnessUnavailableException] rather than fall back. */
fun interface RandomBytes {
    fun fill(bytes: ByteArray)
}

class RandomnessUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The operating system's secure random generator. */
expect fun secureRandomBytes(): RandomBytes

/** The faces of a die are 1 to this. */
const val DIE_FACES = 6

/** The largest multiple of [DIE_FACES] a byte can hold (42 × 6), so taking bytes below it modulo 6 favors no face. */
private const val FAIR_BYTE_LIMIT = 252

/** Rolls one six-sided die at a time from [random]. */
class Dice(private val random: RandomBytes) {
    private val byte = ByteArray(1)

    fun roll(): Int {
        while (true) {
            random.fill(byte)
            val value = byte[0].toInt() and 0xFF
            if (value < FAIR_BYTE_LIMIT) return value % DIE_FACES + 1
        }
    }
}
