package com.adstrosoftware.diceware

/** A source of cryptographically secure random bytes. Throws [RandomnessUnavailableException] rather than fall back. */
fun interface RandomBytes {
    fun fill(bytes: ByteArray)
}

class RandomnessUnavailableException(message: String) : Exception(message)

/** The operating system's secure random generator. */
expect fun secureRandomBytes(): RandomBytes

/** Rolls one six-sided die at a time from [random]. */
class Dice(private val random: RandomBytes) {
    private val byte = ByteArray(1)

    fun roll(): Int {
        while (true) {
            random.fill(byte)
            val value = byte[0].toInt() and 0xFF
            // 252 = 42 * 6: rejecting 252..255 keeps all six faces exactly equally likely.
            if (value < 252) return value % 6 + 1
        }
    }
}
