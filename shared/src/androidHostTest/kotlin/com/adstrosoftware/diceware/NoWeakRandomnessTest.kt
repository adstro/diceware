package com.adstrosoftware.diceware

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Guards the core's sources: no kotlin.random, nor the stdlib helpers that use it behind the scenes. */
class NoWeakRandomnessTest {
    private val banned = Regex("""kotlin\.random|\.random\(|\.shuffled?\(|java\.util\.Random\b|Math\.random""")

    @Test
    fun coreMainSourcesNeverUseNonCryptographicRandomness() {
        // Host tests run with the module directory as working directory.
        val sources = File("src").walk()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("Main${File.separator}") }
            .toList()
        assertTrue(sources.any { it.name == "Dice.kt" }, "Guard scanned nothing: ${File("src").absolutePath}")

        val offenders = sources.asSequence()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "$file:${i + 1}".takeIf { banned.containsMatchIn(line) }
                }
            }
            .toList()

        assertEquals(emptyList(), offenders)
    }
}
