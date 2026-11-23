package com.adstro.diceware

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

const val PASSPHRASE_TAG = "passphrase"

@Composable
fun DicewareTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * Renders the core's Generator state; all logic lives in the shared core. [options] holds the setting controls.
 * In Dice Mode [onEnterDice] is set and replaces regenerating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(
    state: GeneratorState, onRegenerate: () -> Unit, onCopy: (String) -> Unit, onWordLists: () -> Unit, onAbout: () -> Unit,
    onEnterDice: (() -> Unit)? = null,
    options: @Composable (Modifier) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = { OverflowMenu(onWordLists, onAbout) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            val readable = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            when (state) {
                // Saved settings arrive within milliseconds; showing controls before then would let them be overwritten.
                GeneratorState.Loading -> return@Column
                is GeneratorState.Ready -> {
                    Text(
                        text = state.passphrase,
                        // Monospace keeps l, 1 and I distinguishable.
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                        textAlign = TextAlign.Center,
                        modifier = readable.testTag(PASSPHRASE_TAG),
                    )
                    Text(stringResource(R.string.strength_bits, state.strengthBits), textAlign = TextAlign.Center, modifier = readable)
                    if (state.isWeak) {
                        Text(
                            stringResource(R.string.weak_warning),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = readable,
                        )
                    }
                }
                GeneratorState.AwaitingDice -> Text(
                    stringResource(R.string.awaiting_dice),
                    textAlign = TextAlign.Center,
                    modifier = readable,
                )
                GeneratorState.RandomnessUnavailable -> Text(
                    text = stringResource(R.string.randomness_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = readable,
                )
            }
            if (onEnterDice != null) {
                Button(onClick = onEnterDice) { Text(stringResource(R.string.enter_dice)) }
            } else {
                Button(onClick = onRegenerate) { Text(stringResource(R.string.regenerate)) }
            }
            if (state is GeneratorState.Ready) {
                OutlinedButton(onClick = { onCopy(state.passphrase) }) { Text(stringResource(R.string.copy)) }
            }
            options(readable)
        }
    }
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp,dpi=240", showBackground = true)
@Composable
private fun GeneratorScreenPreview() {
    DicewareTheme {
        GeneratorScreen(
            GeneratorState.Ready("abacus zoom kettle lilac boned tribute", 77.5),
            onRegenerate = {},
            onCopy = {},
            onWordLists = {},
            onAbout = {},
        ) { OptionsControls(PassphraseOptions(), listOf(WordListInfo(EffWordList.LARGE, null, 7776, 12.92)), {}, {}, {}, {}, {}, {}, {}, it) }
    }
}
