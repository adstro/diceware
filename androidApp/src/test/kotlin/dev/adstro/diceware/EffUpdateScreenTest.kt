package dev.adstro.diceware

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EffUpdateScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<String>()
    private var state by mutableStateOf<EffUpdateState>(EffUpdateState.Idle)
    private var lists by mutableStateOf(
        listOf(
            WordListInfo(EffWordList.LARGE, null, 7776, 12.92),
            WordListInfo(EffWordList.SHORT_1, null, 1296, 10.34),
            WordListInfo(EffWordList.SHORT_2, null, 1296, 10.34),
        ),
    )

    private fun show() = rule.setContent {
        DicewareTheme {
            WordListsScreen(
                lists,
                ImportState.Idle,
                state,
                onAction = { action ->
                    when (action) {
                        WordListsAction.CheckForUpdates -> actions += "check"
                        WordListsAction.ApplyUpdate -> actions += "apply"
                        WordListsAction.DismissUpdate -> actions += "dismiss"
                        WordListsAction.RestoreBundledWordLists -> actions += "restore"
                        else -> {}
                    }
                },
            )
        }
    }

    @Test
    fun checkForUpdatesIsForwardedAndUpToDateIsShown() {
        show()
        rule.onNodeWithText("Restore bundled lists").performScrollTo().assertIsNotEnabled()

        rule.onNodeWithText("Check for updates").performScrollTo().performClick()
        state = EffUpdateState.Checking
        rule.onNodeWithText("Checking eff.org…").assertExists()
        rule.onNodeWithText("Check for updates").assertIsNotEnabled()
        state = EffUpdateState.UpToDate
        rule.onNodeWithText("The EFF Word Lists are up to date.").assertExists()
        rule.onNodeWithText("OK").performClick()

        assertEquals(listOf("check", "dismiss"), actions)
    }

    @Test
    fun anUpdateShowsWhatChangedAndAppliesOnlyWhenConfirmed() {
        state = EffUpdateState.UpdateAvailable(
            listOf(
                EffWordListChange(
                    EffWordList.SHORT_1,
                    7,
                    2,
                    listOf("kiwi", "lime", "fig", "date", "plum"),
                    listOf("acid", "zoom"),
                ),
            ),
        )
        show()

        rule.onNodeWithText("EFF Short #1: 7 Words added, 2 removed").assertExists()
        rule.onNodeWithText("Added: kiwi, lime, fig, date, plum, …").assertExists()
        rule.onNodeWithText("Removed: acid, zoom").assertExists()
        rule.onNodeWithText("Update").performClick()

        assertEquals(listOf("apply"), actions)
    }

    @Test
    fun anUpdatedListIsMarkedAndCanBeRestored() {
        lists = lists.map { if (it.id == EffWordList.LARGE) it.copy(updated = true) else it }
        show()

        rule.onNodeWithText("7,776 Words · 12.9 bits per Word · Updated from eff.org").assertExists()
        rule.onNodeWithText("Restore bundled lists").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf("restore"), actions)
    }

    @Test
    fun aFailedCheckSaysWhyAndThatNothingChanged() {
        state = EffUpdateState.Failed(EffUpdateError.Redirected)
        show()

        rule.onNodeWithText(
            "eff.org redirected the download to another site, so it was refused. Nothing was changed.",
        ).assertExists()
    }
}
