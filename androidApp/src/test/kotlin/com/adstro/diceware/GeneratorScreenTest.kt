package com.adstro.diceware

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** Settings are saved process-wide and Robolectric reuses the files dir, so each test starts from the defaults. */
    @Before
    fun resetSettings() {
        val generator = ViewModelProvider(rule.activity)[GeneratorViewModel::class.java].generator
        rule.waitUntil { generator.state.value != GeneratorState.Loading }
        rule.runOnUiThread {
            val defaults = PassphraseOptions()
            generator.setWordCount(defaults.wordCount)
            generator.setSeparator(defaults.separator)
            generator.setCapitalise(defaults.capitalise)
            generator.setAppendDigit(defaults.appendDigit)
            generator.setAppendSymbol(defaults.appendSymbol)
        }
    }

    /** Waits out the first read of saved settings, then returns the Passphrase on screen. */
    private fun shownPassphrase(): String {
        rule.waitUntil { rule.onAllNodesWithTag(PASSPHRASE_TAG).fetchSemanticsNodes().isNotEmpty() }
        return rule.onNodeWithTag(PASSPHRASE_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
    }

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
    fun showsTheStrengthOfTheDefaultPassphrase() {
        shownPassphrase()

        // 6 Words × 5 dice × log₂6.
        rule.onNodeWithText("Strength: 77.5 bits").assertIsDisplayed()
        rule.onNodeWithText(rule.activity.getString(R.string.weak_warning)).assertDoesNotExist()
    }

    @Test
    fun choosingASeparatorRerollsWithItBetweenTheWords() {
        shownPassphrase()

        rule.onNodeWithText(rule.activity.getString(R.string.separator_hyphen)).performScrollTo().performClick()

        assertEquals(6, shownPassphrase().split("-").size)
    }

    @Test
    fun fewerWordsLowerTheStrengthAndWarn() {
        shownPassphrase()

        rule.onNodeWithTag(WORD_COUNT_TAG).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }

        assertEquals(4, shownPassphrase().split(" ").size)
        rule.onNodeWithText("Strength: 51.7 bits").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.weak_warning)).assertExists()
    }

    @Test
    fun capitaliseDigitAndSymbolTogglesShapeThePassphrase() {
        shownPassphrase()

        listOf(R.string.capitalise, R.string.append_digit, R.string.append_symbol).forEach {
            rule.onNodeWithText(rule.activity.getString(it)).performScrollTo().performClick()
        }

        val passphrase = shownPassphrase()
        assertTrue(passphrase, Regex("([A-Z][a-z-]* ){5}[A-Z][a-z-]*[0-9][!@#$%&*\\-_+=?]").matches(passphrase))
        // 77.5 + log₂10 + log₂12.
        rule.onNodeWithText("Strength: 84.5 bits").assertExists()
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
