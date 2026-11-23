package com.adstro.diceware

/** Hands out the scripted bytes in order; fails like a broken CSPRNG once they run out. */
class ScriptedRandom(vararg bytes: Int) : RandomBytes {
    private val script = ArrayDeque(bytes.toList())

    override fun fill(bytes: ByteArray) {
        for (i in bytes.indices) {
            bytes[i] = (script.removeFirstOrNull() ?: throw RandomnessUnavailableException("script exhausted")).toByte()
        }
    }

    val remaining get() = script.size
}

/** Bytes that make [Dice] produce exactly these faces (face f comes from byte f - 1). */
fun diceBytes(vararg faces: Int): IntArray = faces.map { it - 1 }.toIntArray()
