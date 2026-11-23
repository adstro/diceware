package com.adstro.diceware

import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.dp

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
        // waitUntil alone may not run the main looper the Generator resumes on, so idle it each time.
        rule.waitUntil(5_000) { rule.waitForIdle(); generator.state.value != GeneratorState.Loading }
        rule.runOnUiThread {
            val defaults = PassphraseOptions()
            generator.setWordCount(defaults.wordCount)
            generator.setSeparator(defaults.separator)
            generator.setCapitalise(defaults.capitalise)
            generator.setAppendDigit(defaults.appendDigit)
            generator.setAppendSymbol(defaults.appendSymbol)
            generator.setWordList(defaults.wordList)
            generator.setMode(defaults.mode)
        }
    }

    /** Waits out the first read of saved settings, then returns the Passphrase on screen. */
    private fun shownPassphrase(): String {
        rule.waitUntil { rule.onAllNodesWithTag(PASSPHRASE_TAG).fetchSemanticsNodes().isNotEmpty() }
        return rule.onNodeWithTag(PASSPHRASE_TAG).fetchSemanticsNode().config[PassphraseText]
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
    fun eachWordIsAnnouncedOnItsOwnAndSpellOutReadsEveryCharacter() {
        rule.runOnUiThread {
            rule.activity.setContent {
                DicewareTheme {
                    GeneratorScreen(GeneratorState.Ready("Ab-zoom7!", 20.0, listOf("Ab", "zoom")), onRegenerate = {}, onCopy = {}, onWordLists = {}, onAbout = {})
                }
            }
        }

        val parts = rule.onAllNodesWithTag(PASSPHRASE_PART_TAG)
        assertEquals(
            listOf("Ab", "zoom", "7", "!"),
            parts.fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() },
        )
        val spellOut = parts[1].fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == rule.activity.getString(R.string.spell_out) }
        rule.runOnUiThread { spellOut.action() }

        rule.onNodeWithTag(SPELLED_OUT_TAG)
            .assertContentDescriptionEquals("capital A, b, hyphen, z, o, o, m, 7, exclamation mark")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun onAnExpandedWidthWindowThePassphraseAndOptionsAreSideBySide() {
        shownPassphrase()

        val passphrase = rule.onNodeWithTag(PASSPHRASE_TAG).getBoundsInRoot()
        val mode = rule.onNodeWithText(rule.activity.getString(R.string.mode)).getBoundsInRoot()
        assertTrue("options at $mode, passphrase at $passphrase", mode.left >= passphrase.right)
    }

    @Test
    fun onAPhoneTheOptionsAreBelowThePassphrase() {
        shownPassphrase()

        val passphrase = rule.onNodeWithTag(PASSPHRASE_TAG).getBoundsInRoot()
        val mode = rule.onNodeWithText(rule.activity.getString(R.string.mode)).getBoundsInRoot()
        assertTrue("options at $mode, passphrase at $passphrase", mode.top >= passphrase.bottom)
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
            rule.activity.setContent {
                DicewareTheme { GeneratorScreen(GeneratorState.RandomnessUnavailable, onRegenerate = {}, onCopy = {}, onWordLists = {}, onAbout = {}) }
            }
        }

        rule.onNodeWithText(rule.activity.getString(R.string.randomness_unavailable)).assertIsDisplayed()
        rule.onNodeWithTag(PASSPHRASE_TAG).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.copy)).assertDoesNotExist()
    }

    @Test
    fun copyPutsThePassphraseOnTheClipboardMarkedSensitive() {
        val shown = shownPassphrase()

        rule.onNodeWithText(rule.activity.getString(R.string.copy)).performClick()

        val clip = rule.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals(shown, clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test
    fun windowIsSecureSoItIsKeptOutOfScreenshotsAndRecents() {
        assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun diceModeBuildsThePassphraseFromEnteredDiceOneWordPerRoll() {
        shownPassphrase()
        rule.onNodeWithText(rule.activity.getString(R.string.mode_dice)).performScrollTo().performClick()
        rule.onNodeWithTag(PASSPHRASE_TAG).assertDoesNotExist()

        rule.onNodeWithText(rule.activity.getString(R.string.enter_dice)).performScrollTo().performClick()
        // EFF Large: 5 dice per Roll. 11111 is "abacus", 66666 is "zoom".
        rule.onNodeWithText(rule.activity.getString(R.string.dice_step_word, 1, 6, 5)).assertExists()
        repeat(5) { tapDie(1) }
        rule.onNodeWithText("abacus").assertExists()
        repeat(5) { tapDie(6) }
        rule.onNodeWithText("zoom").assertExists()

        // A mistyped die is taken back and entered again.
        tapDie(2)
        rule.onNodeWithText(rule.activity.getString(R.string.remove_last_die)).performScrollTo().performClick()
        repeat(20) { tapDie(1) }
        rule.onNodeWithText(rule.activity.getString(R.string.dice_done)).performScrollTo().performClick()

        assertEquals(listOf("abacus", "zoom") + List(4) { "abacus" }, shownPassphrase().split(" "))
    }

    @Test
    fun dieFaceButtonsAreLabelledAndLargeEnoughToTap() {
        shownPassphrase()
        rule.onNodeWithText(rule.activity.getString(R.string.mode_dice)).performScrollTo().performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.enter_dice)).performScrollTo().performClick()

        for (face in 1..6) {
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.die_face, face))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
    }

    private fun tapDie(face: Int) {
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.die_face, face)).performScrollTo().performClick()
    }
}
