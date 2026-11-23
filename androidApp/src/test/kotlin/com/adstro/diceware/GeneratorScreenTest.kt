package com.adstro.diceware

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class GeneratorScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val effLargeWords: Set<String> by lazy {
        RuntimeEnvironment.getApplication().assets.open("eff_large_wordlist.txt").bufferedReader()
            .readLines().map { it.substringAfter('\t') }.toSet()
    }

    private fun shownPassphrase(): String =
        rule.onNodeWithTag(PASSPHRASE_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    @Test
    fun opensWithASixWordPassphraseFromTheEffLargeWordList() {
        val words = shownPassphrase().split(" ")

        assertEquals(6, words.size)
        assertTrue("$words not all in EFF Large", effLargeWords.containsAll(words))
    }

    @Test
    fun regenerateShowsAFreshPassphrase() {
        val first = shownPassphrase()

        rule.onNodeWithText(rule.activity.getString(R.string.regenerate)).performClick()

        // Same 6 Words twice in a row: 1 in 7776⁶ (~2⁻⁷⁷).
        assertNotEquals(first, shownPassphrase())
    }

    @Test
    fun keepsThePassphraseAcrossRecreation() {
        val first = shownPassphrase()

        rule.activityRule.scenario.recreate()

        assertEquals(first, shownPassphrase())
    }

    @Test
    fun showsAnErrorAndNoPassphraseWhenSecureRandomnessFails() {
        rule.runOnUiThread {
            rule.activity.setContent { DicewareTheme { GeneratorScreen(GeneratorState.RandomnessUnavailable, onRegenerate = {}) } }
        }

        rule.onNodeWithText(rule.activity.getString(R.string.randomness_unavailable)).assertIsDisplayed()
        rule.onNodeWithTag(PASSPHRASE_TAG).assertDoesNotExist()
    }
}
