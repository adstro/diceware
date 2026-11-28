package dev.adstro.diceware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun EffUpdateControls(
    checking: Boolean,
    anyUpdated: Boolean,
    onCheckForUpdates: () -> Unit,
    onRestoreBundledWordLists: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (checking) {
            Text(stringResource(R.string.eff_checking))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckForUpdates, enabled = !checking) {
                Text(stringResource(R.string.eff_check_for_updates))
            }
            TextButton(onClick = onRestoreBundledWordLists, enabled = anyUpdated && !checking) {
                Text(stringResource(R.string.eff_restore_bundled))
            }
        }
    }
}

/** The check's outcome: what changed, for the user to confirm, or why nothing did. */
@Composable
internal fun EffUpdateResult(state: EffUpdateState, onApply: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        is EffUpdateState.UpdateAvailable -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.eff_update_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.changes.forEach { EffChange(it) }
                }
            },
            confirmButton = { TextButton(onClick = onApply) { Text(stringResource(R.string.eff_update)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )

        EffUpdateState.UpToDate -> ImportResult(stringResource(R.string.eff_up_to_date), onDismiss)

        EffUpdateState.Updated -> ImportResult(stringResource(R.string.eff_updated), onDismiss)

        is EffUpdateState.Failed -> ImportResult(effUpdateErrorMessage(state.error), onDismiss)

        EffUpdateState.Idle, EffUpdateState.Checking -> {}
    }
}

@Composable
private fun EffChange(change: EffWordListChange) {
    val name = stringResource(wordListNames.getValue(change.wordList))
    Column {
        if (change.wordsAdded == 0L && change.wordsRemoved == 0L) {
            Text(stringResource(R.string.eff_change_reordered, name))
            return
        }
        Text(
            pluralStringResource(R.plurals.eff_change, change.wordsAdded, name, change.wordsAdded, change.wordsRemoved),
        )
        if (change.sampleAdded.isNotEmpty()) {
            Text(stringResource(R.string.eff_change_added, sample(change.sampleAdded, change.wordsAdded)))
        }
        if (change.sampleRemoved.isNotEmpty()) {
            Text(stringResource(R.string.eff_change_removed, sample(change.sampleRemoved, change.wordsRemoved)))
        }
    }
}

private fun sample(words: List<String>, total: Long) =
    (if (total > words.size) words + "…" else words).joinToString(", ")

@Composable
@ReadOnlyComposable
private fun effUpdateErrorMessage(error: EffUpdateError): String = when (error) {
    EffUpdateError.Offline -> stringResource(R.string.eff_offline)

    is EffUpdateError.HttpStatus -> stringResource(R.string.eff_http_status, error.code)

    EffUpdateError.TooLarge -> stringResource(R.string.eff_too_large)

    EffUpdateError.Redirected -> stringResource(R.string.eff_redirected)

    is EffUpdateError.Invalid -> stringResource(
        R.string.eff_invalid,
        stringResource(wordListNames.getValue(error.wordList)),
        importErrorMessage(error.error),
    )

    EffUpdateError.StorageFailed -> stringResource(R.string.eff_storage_failed)
}
