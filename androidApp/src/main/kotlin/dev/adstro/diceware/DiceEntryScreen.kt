package dev.adstro.diceware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Renders the core's Dice entry state and forwards each die to the core. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiceEntryScreen(
    entry: DiceEntryState,
    onDie: (Int) -> Unit,
    onRemoveLastDie: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { DiceEntryTopBar(onNavigateUp) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val readable = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            Text(
                dicePrompt(entry),
                style = MaterialTheme.typography.titleMedium,
                modifier = readable.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (entry.rerollDigit) {
                Text(
                    stringResource(R.string.reroll_digit),
                    color = MaterialTheme.colorScheme.error,
                    modifier = readable,
                )
            }
            if (entry.step != DiceStep.DONE) {
                val entered = stringResource(R.string.dice_entered, entry.roll.joinToString(", "))
                Text(
                    List(entry.diceInRoll) { entry.roll.getOrNull(it)?.toString() ?: "–" }.joinToString(" "),
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.semantics { contentDescription = entered },
                )
                DieFaces(onDie)
            }
            OutlinedButton(onClick = onRemoveLastDie, enabled = entry.words.isNotEmpty() || entry.roll.isNotEmpty()) {
                Text(stringResource(R.string.remove_last_die))
            }
            if (entry.step == DiceStep.DONE) Button(onClick = onNavigateUp) { Text(stringResource(R.string.dice_done)) }
            // Below the keypad, so the dice stay in reach as Words pile up.
            entry.words.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = readable,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiceEntryTopBar(onNavigateUp: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        title = { Text(stringResource(R.string.enter_dice)) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.navigate_up),
                )
            }
        },
    )
}

/** What to roll next, or that the Passphrase is complete. */
@Composable
@ReadOnlyComposable
private fun dicePrompt(entry: DiceEntryState): String = when (entry.step) {
    DiceStep.WORD -> pluralStringResource(
        R.plurals.dice_step_word,
        entry.diceInRoll,
        entry.words.size + 1,
        entry.wordCount,
        entry.diceInRoll,
    )

    DiceStep.DIGIT -> stringResource(R.string.dice_step_digit)

    DiceStep.SYMBOL -> stringResource(R.string.dice_step_symbol)

    DiceStep.DONE -> stringResource(R.string.dice_complete)
}

/** One button per die face, in two rows of three. */
@Composable
private fun DieFaces(onDie: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        for (face in 1..DIE_FACES) {
            val label = stringResource(R.string.die_face, face)
            FilledTonalButton(
                onClick = { onDie(face) },
                modifier = Modifier
                    .sizeIn(minWidth = 64.dp, minHeight = 64.dp)
                    .semantics { contentDescription = label },
            ) { Text(face.toString(), style = MaterialTheme.typography.headlineSmall) }
        }
    }
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
internal fun DiceEntryScreenPreview() {
    DicewareTheme {
        DiceEntryScreen(
            DiceEntryState(listOf("abacus"), wordCount = 6, DiceStep.WORD, diceInRoll = 5, roll = listOf(1, 2)),
            {},
            {},
            {},
        )
    }
}
