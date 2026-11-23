package com.adstro.diceware

import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun string(@StringRes id: Int) = rule.activity.getString(id)

    private fun openAbout() {
        rule.onNodeWithContentDescription(string(R.string.more_options)).performClick()
        rule.onNodeWithText(string(R.string.about)).performClick()
    }

    private fun shownPassphrase(): String =
        rule.onNodeWithTag(PASSPHRASE_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    private fun assertShown(@StringRes id: Int) {
        rule.onNodeWithText(string(id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aboutFromTheTopAppBarExplainsDicewareCreditsTheEffAndStatesPrivacy() {
        openAbout()

        assertShown(R.string.about_diceware_body)
        assertShown(R.string.about_word_lists_body)
        assertShown(R.string.about_privacy_body)
    }

    @Test
    @Config(fontScale = 2f)
    fun everyAboutSectionCanBeReadAtTheLargestFontSize() {
        openAbout()

        assertShown(R.string.about_diceware_body)
        assertShown(R.string.about_word_lists_body)
        assertShown(R.string.about_privacy_body)
    }

    @Test
    fun navigatingUpFromAboutReturnsToTheSamePassphrase() {
        val passphrase = shownPassphrase()
        openAbout()

        rule.onNodeWithContentDescription(string(R.string.navigate_up)).performClick()

        assertEquals(passphrase, shownPassphrase())
    }

    @Test
    fun systemBackFromAboutReturnsToTheGenerator() {
        openAbout()
        // Let NavDisplay enable its back handler for the new entry before pressing back.
        rule.waitForIdle()

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag(PASSPHRASE_TAG).assertIsDisplayed()
    }
}
