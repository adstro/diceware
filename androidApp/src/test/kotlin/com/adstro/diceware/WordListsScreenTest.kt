package com.adstro.diceware

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class WordListsScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun string(@StringRes id: Int) = rule.activity.getString(id)

    /** Settings are saved process-wide and Robolectric reuses the files dir, so each test starts on EFF Large. */
    @Before
    fun resetSelectedWordList() {
        val generator = ViewModelProvider(rule.activity)[GeneratorViewModel::class.java].generator
        rule.waitUntil { generator.state.value != GeneratorState.Loading }
        rule.runOnUiThread { generator.setWordList(EffWordList.LARGE) }
    }

    private fun shownPassphrase(): String {
        rule.waitUntil { rule.onAllNodesWithTag(PASSPHRASE_TAG).fetchSemanticsNodes().isNotEmpty() }
        return rule.onNodeWithTag(PASSPHRASE_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
    }

    private fun wordsOf(fileName: String): Set<String> =
        RuntimeEnvironment.getApplication().assets.open(fileName).bufferedReader()
            .readLines().map { it.substringAfter('\t') }.toSet()

    private fun openWordLists() {
        rule.onNodeWithContentDescription(string(R.string.more_options)).performClick()
        rule.onNodeWithText(string(R.string.word_lists)).performClick()
    }

    @Test
    fun choosingEffShort1RerollsWithItsWordsAndFourDicePerWord() {
        shownPassphrase()

        rule.onNodeWithText(string(R.string.word_list_eff_short_1)).performScrollTo().performClick()

        val words = shownPassphrase().split(" ")
        assertEquals(6, words.size)
        assertTrue("$words not all in EFF Short #1", wordsOf("eff_short_wordlist_1.txt").containsAll(words))
        // 6 Words × 4 dice × log₂6.
        rule.onNodeWithText("Strength: 62.0 bits").assertExists()
    }

    @Test
    fun wordListsScreenShowsEachEffWordListWithItsWordCountAndStrengthPerWord() {
        shownPassphrase()

        openWordLists()

        rule.onNodeWithText(string(R.string.word_list_eff_large)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.word_list_eff_short_1)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.word_list_eff_short_2)).assertIsDisplayed()
        rule.onNodeWithText("7,776 Words · 12.9 bits per Word").assertIsDisplayed()
        assertEquals(2, rule.onAllNodesWithText("1,296 Words · 10.3 bits per Word").fetchSemanticsNodes().size)
    }

    @Test
    fun systemBackFromWordListsReturnsToTheSamePassphrase() {
        val passphrase = shownPassphrase()
        openWordLists()
        // Let NavDisplay enable its back handler for the new entry before pressing back.
        rule.waitForIdle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(passphrase, shownPassphrase())
    }
}
