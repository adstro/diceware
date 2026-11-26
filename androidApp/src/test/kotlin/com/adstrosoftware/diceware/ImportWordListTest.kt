package com.adstrosoftware.diceware

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ImportWordListTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var library: WordListLibrary

    private val shared = Uri.parse("content://com.example.files/animals.txt")

    /** A 6⁴-Word plain-format list, as another app would serve it. */
    private fun serveList(uri: Uri) {
        val text = (0 until 1296).joinToString("\n") { "animal$it" }
        shadowOf(app.contentResolver).registerInputStream(uri, text.byteInputStream())
    }

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClass(app, MainActivity::class.java))
        scenario.onActivity { library = ViewModelProvider(it)[GeneratorViewModel::class.java].generator.library }
        rule.waitUntil { library.wordLists.value.isNotEmpty() }
    }

    /** The library outlives each test's activity, like the app process does, so remove what a test imported. */
    @After
    fun deleteCustomWordLists() {
        library.wordLists.value.mapNotNull { it.id as? CustomWordListId }
            .forEach { id -> scenario.onActivity { library.delete(id) } }
        rule.waitUntil { library.wordLists.value.size == 3 }
        scenario.close()
    }

    @Test
    fun aTextFileSharedFromAnotherAppIsImportedAndShownOnWordLists() {
        serveList(shared)

        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, shared))

        rule.waitUntil(5_000) { library.importState.value is ImportState.Imported }
        rule.onNodeWithText("Imported Word List.").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(3, rule.onAllNodesWithText("1,296 Words · 10.3 bits per Word").fetchSemanticsNodes().size)
    }

    @Test
    fun theEffLargeFileSharedFromAnotherAppIsAlreadyThere() {
        shadowOf(app.contentResolver).registerInputStream(shared, app.assets.open(EffWordList.LARGE.fileName))

        launch(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, shared))

        rule.waitUntil(5_000) { library.importState.value is ImportState.Failed }
        rule.onNodeWithText("Not imported: already imported as EFF Large.").assertIsDisplayed()
        assertEquals(3, library.wordLists.value.size)
        rule.onNodeWithText("OK").performClick()
    }

    @Test
    fun aFileUriFromAnotherAppIsNeverRead() {
        // A file:// URI could name this app's own private files.
        launch(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.fromFile(app.filesDir.resolve("diceware.preferences_pb")), "text/plain"),
        )

        rule.waitForIdle()
        assertEquals(ImportState.Idle, library.importState.value)
        assertEquals(3, library.wordLists.value.size)
        rule.onNodeWithText("Roll new passphrase").assertExists()
    }

    @Test
    fun aCustomWordListCanBeRenamedAndDeleted() {
        serveList(shared)
        launch(Intent(Intent.ACTION_VIEW).setDataAndType(shared, "text/plain"))
        rule.waitUntil(5_000) { library.importState.value is ImportState.Imported }
        rule.onNodeWithText("OK").performClick()

        rule.onNodeWithContentDescription("Word List actions").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Animals")
        rule.onAllNodesWithText("Rename").onLast().performClick()

        rule.waitUntil { library.wordLists.value.last().name == "Animals" }
        rule.onNodeWithText("Animals").assertIsDisplayed()

        rule.onNodeWithContentDescription("Animals actions").performClick()
        rule.onNodeWithText("Delete").performClick()
        rule.onNodeWithText("Delete").performClick()

        rule.waitUntil { library.wordLists.value.size == 3 }
        rule.onNodeWithText("Animals").assertDoesNotExist()
    }
}
