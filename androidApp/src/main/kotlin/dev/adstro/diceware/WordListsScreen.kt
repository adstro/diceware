package dev.adstro.diceware

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Display names for the core's EFF Word Lists. */
val wordListNames = mapOf(
    EffWordList.LARGE to R.string.word_list_eff_large,
    EffWordList.SHORT_1 to R.string.word_list_eff_short_1,
    EffWordList.SHORT_2 to R.string.word_list_eff_short_2,
)

/** A Custom Word List's own name, or the EFF Word List's display name. */
@Composable
@ReadOnlyComposable
fun nameOf(info: WordListInfo): String = info.name ?: stringResource(wordListNames.getValue(info.id as EffWordList))

/** What the user does on the Word Lists screen; the caller forwards each to the core or to navigation. */
sealed interface WordListsAction {
    data object NavigateUp : WordListsAction
    data object Import : WordListsAction
    data object CancelImport : WordListsAction
    data object DismissImportResult : WordListsAction
    data class Rename(val id: CustomWordListId, val name: String) : WordListsAction
    data class Delete(val id: CustomWordListId) : WordListsAction
    data object CheckForUpdates : WordListsAction
    data object ApplyUpdate : WordListsAction
    data object DismissUpdate : WordListsAction
    data object RestoreBundledWordLists : WordListsAction
}

/**
 * Renders the core's Word Lists state: every Word List with its Word count and Strength per Word, the
 * running import, and rename and delete for Custom Word Lists. Every action is forwarded to the core.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListsScreen(
    wordLists: List<WordListInfo>,
    importState: ImportState,
    effUpdateState: EffUpdateState,
    onAction: (WordListsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { WordListsTopBar(importState, onAction) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                // Inside the scroll, so rows scroll behind the navigation bar instead of being clipped above it.
                .padding(innerPadding)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val readable = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            if (importState is ImportState.Importing) {
                ImportProgress(importState.progress, { onAction(WordListsAction.CancelImport) }, readable)
            }
            wordLists.forEach { info -> WordListRow(info, onAction, readable) }
            EffUpdateControls(
                checking = effUpdateState == EffUpdateState.Checking,
                anyUpdated = wordLists.any { it.updated },
                onCheckForUpdates = { onAction(WordListsAction.CheckForUpdates) },
                onRestoreBundledWordLists = { onAction(WordListsAction.RestoreBundledWordLists) },
                modifier = readable,
            )
        }
    }
    EffUpdateResult(
        effUpdateState,
        { onAction(WordListsAction.ApplyUpdate) },
        { onAction(WordListsAction.DismissUpdate) },
    )
    when (importState) {
        is ImportState.Imported -> ImportResult(stringResource(R.string.import_done, importState.name)) {
            onAction(WordListsAction.DismissImportResult)
        }

        is ImportState.Failed -> ImportResult(importErrorMessage(importState.error)) {
            onAction(WordListsAction.DismissImportResult)
        }

        else -> {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordListsTopBar(
    importState: ImportState,
    onAction: (WordListsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.word_lists)) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = { onAction(WordListsAction.NavigateUp) }) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.navigate_up),
                )
            }
        },
        actions = {
            TextButton(
                onClick = { onAction(WordListsAction.Import) },
                enabled = importState !is ImportState.Importing,
            ) {
                Text(stringResource(R.string.import_word_list))
            }
        },
    )
}

/** One Word List with its Word count and Strength per Word, and rename and delete if it is a Custom Word List. */
@Composable
private fun WordListRow(info: WordListInfo, onAction: (WordListsAction) -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        supportingContent = {
            val details = pluralStringResource(
                R.plurals.word_list_details,
                info.wordCount,
                info.wordCount,
                info.strengthPerWordBits,
            )
            Text(if (info.updated) stringResource(R.string.word_list_updated, details) else details)
        },
        trailingContent = (info.id as? CustomWordListId)?.let { id ->
            {
                CustomWordListMenu(
                    nameOf(info),
                    onRename = { onAction(WordListsAction.Rename(id, it)) },
                    onDelete = { onAction(WordListsAction.Delete(id)) },
                )
            }
        },
    ) { Text(nameOf(info)) }
}

@Composable
private fun ImportProgress(progress: Double, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.importing))
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
internal fun ImportResult(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        text = { Text(message) },
    )
}

/** Why an import was rejected, naming the offending line. */
@Composable
@ReadOnlyComposable
fun importErrorMessage(error: ImportError): String = when (error) {
    is ImportError.WrongWordCount ->
        pluralStringResource(R.plurals.import_wrong_word_count, error.wordCount, error.wordCount)

    is ImportError.DuplicateWord ->
        stringResource(R.string.import_duplicate_word, error.line, error.word, error.firstLine)

    is ImportError.BadLine -> stringResource(messageOf(error.problem), error.line)

    is ImportError.RollNotFound -> stringResource(R.string.import_roll_not_found, error.roll)

    is ImportError.RepeatedRoll -> stringResource(R.string.import_repeated_roll, error.line, error.firstLine)

    is ImportError.AlreadyImported -> stringResource(R.string.import_already_imported, error.name)

    is ImportError.AlreadyImportedAsEff ->
        stringResource(R.string.import_already_imported, stringResource(wordListNames.getValue(error.wordList)))

    ImportError.Unreadable -> stringResource(R.string.import_unreadable)

    ImportError.StorageFailed -> stringResource(R.string.import_storage_failed)
}

/** The message for a [LineProblem]; each takes the line number. */
@StringRes
private fun messageOf(problem: LineProblem): Int = when (problem) {
    LineProblem.WORD_HAS_WHITESPACE -> R.string.import_word_has_whitespace
    LineProblem.INVALID_ROLL -> R.string.import_invalid_roll
    LineProblem.MIXED_ROLL_LENGTH -> R.string.import_mixed_roll_length
    LineProblem.MISSING_ROLL -> R.string.import_missing_roll
    LineProblem.LINE_TOO_LONG -> R.string.import_line_too_long
    LineProblem.NOT_TEXT -> R.string.import_not_text
}

/** The core counts in Long; a count too large for Int takes the form for Int.MAX_VALUE. */
@Composable
@ReadOnlyComposable
internal fun pluralStringResource(@PluralsRes id: Int, count: Long, vararg formatArgs: Any): String =
    pluralStringResource(id, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), *formatArgs)

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Large font", device = "spec:width=411dp,height=891dp", fontScale = 2f, showBackground = true)
@Composable
internal fun WordListsScreenPreview() {
    DicewareTheme {
        WordListsScreen(
            listOf(
                WordListInfo(EffWordList.LARGE, null, wordCount = 7776, strengthPerWordBits = 12.92),
                WordListInfo(EffWordList.SHORT_1, null, wordCount = 1296, strengthPerWordBits = 10.34),
                WordListInfo(EffWordList.SHORT_2, null, wordCount = 1296, strengthPerWordBits = 10.34),
                WordListInfo(CustomWordListId("0"), "Animals", wordCount = 279_936, strengthPerWordBits = 18.09),
            ),
            ImportState.Importing(progress = 0.4),
            EffUpdateState.Idle,
            onAction = {},
        )
    }
}
