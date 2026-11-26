package com.adstrosoftware.diceware

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

@Composable
internal fun CustomWordListMenu(name: String, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.word_list_actions, name),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                onClick = {
                    menu = false
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete)) },
                onClick = {
                    menu = false
                    deleting = true
                },
            )
        }
    }
    if (renaming) {
        RenameDialog(
            name,
            onRename = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.delete_word_list, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun RenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by rememberSaveable { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_word_list)) },
        text = {
            OutlinedTextField(
                newName,
                { newName = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(newName) }, enabled = newName.isNotBlank()) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
