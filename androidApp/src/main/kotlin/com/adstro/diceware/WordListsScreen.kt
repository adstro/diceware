package com.adstro.diceware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
fun nameOf(info: WordListInfo): String = info.name ?: stringResource(wordListNames.getValue(info.id as EffWordList))

/**
 * Renders the core's Word Lists state: every Word List with its Word count and Strength per Word, the
 * running import, and rename and delete for Custom Word Lists. Every action is forwarded to the core.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListsScreen(
    wordLists: List<WordListInfo>,
    importState: ImportState,
    onNavigateUp: () -> Unit,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onDismissImportResult: () -> Unit,
    onRename: (CustomWordListId, String) -> Unit,
    onDelete: (CustomWordListId) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.word_lists)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    TextButton(onClick = onImport, enabled = importState !is ImportState.Importing) {
                        Text(stringResource(R.string.import_word_list))
                    }
                },
            )
        },
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
            if (importState is ImportState.Importing) ImportProgress(importState.progress, onCancelImport, readable)
            wordLists.forEach { info ->
                ListItem(
                    headlineContent = { Text(nameOf(info)) },
                    supportingContent = {
                        Text(stringResource(R.string.word_list_details, info.wordCount, info.strengthPerWordBits))
                    },
                    trailingContent = (info.id as? CustomWordListId)?.let { id ->
                        { CustomWordListMenu(nameOf(info), onRename = { onRename(id, it) }, onDelete = { onDelete(id) }) }
                    },
                    modifier = readable,
                )
            }
        }
    }
    when (importState) {
        is ImportState.Imported -> ImportResult(stringResource(R.string.import_done, importState.name), onDismissImportResult)
        is ImportState.Failed -> ImportResult(importErrorMessage(importState.error), onDismissImportResult)
        else -> {}
    }
}

@Composable
private fun ImportProgress(progress: Double, onCancel: () -> Unit, modifier: Modifier) {
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.importing))
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun ImportResult(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        text = { Text(message) },
    )
}

@Composable
private fun CustomWordListMenu(name: String, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.word_list_actions, name))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleting = true })
        }
    }
    if (renaming) {
        var newName by rememberSaveable { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_word_list)) },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { renaming = false; onRename(newName) }, enabled = newName.isNotBlank()) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_word_list, name)) },
            confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Why an import was rejected, naming the offending line. */
@Composable
fun importErrorMessage(error: ImportError): String = when (error) {
    is ImportError.WrongWordCount -> stringResource(R.string.import_wrong_word_count, error.wordCount)
    is ImportError.DuplicateWord -> stringResource(R.string.import_duplicate_word, error.line, error.word, error.firstLine)
    is ImportError.WordHasWhitespace -> stringResource(R.string.import_word_has_whitespace, error.line)
    is ImportError.InvalidRoll -> stringResource(R.string.import_invalid_roll, error.line)
    is ImportError.MixedRollLength -> stringResource(R.string.import_mixed_roll_length, error.line)
    is ImportError.MissingRoll -> stringResource(R.string.import_missing_roll, error.line)
    is ImportError.RepeatedRoll -> stringResource(R.string.import_repeated_roll, error.line, error.firstLine)
    is ImportError.LineTooLong -> stringResource(R.string.import_line_too_long, error.line)
    is ImportError.NotText -> stringResource(R.string.import_not_text, error.line)
    is ImportError.AlreadyImported -> stringResource(R.string.import_already_imported, error.name)
    ImportError.Unreadable -> stringResource(R.string.import_unreadable)
    ImportError.StorageFailed -> stringResource(R.string.import_storage_failed)
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Large font", device = "spec:width=411dp,height=891dp", fontScale = 2f, showBackground = true)
@Composable
private fun WordListsScreenPreview() {
    DicewareTheme {
        WordListsScreen(
            listOf(
                WordListInfo(EffWordList.LARGE, null, 7776, 12.92),
                WordListInfo(EffWordList.SHORT_1, null, 1296, 10.34),
                WordListInfo(EffWordList.SHORT_2, null, 1296, 10.34),
                WordListInfo(CustomWordListId("0"), "Animals", 279_936, 18.09),
            ),
            ImportState.Importing(0.4),
            onNavigateUp = {}, onImport = {}, onCancelImport = {}, onDismissImportResult = {}, onRename = { _, _ -> }, onDelete = {},
        )
    }
}
