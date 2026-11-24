package com.adstrosoftware.diceware

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

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
 * Renders the core's Generator state; all logic lives in the shared core. [options] holds the setting controls,
 * beside the Passphrase on an expanded-width window and below it otherwise.
 * In Dice Mode [onEnterDice] is set and replaces regenerating.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorScreen(
    state: GeneratorState, onRegenerate: () -> Unit, onCopy: (String) -> Unit, onWordLists: () -> Unit, onAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onEnterDice: (() -> Unit)? = null,
    options: @Composable (Modifier) -> Unit = {},
) {
    // One movable instance, so the controls keep their state when the window crosses the expanded-width breakpoint.
    val currentOptions by rememberUpdatedState(options)
    val movableOptions = remember { movableContentOf { slotModifier: Modifier -> currentOptions(slotModifier) } }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = { OverflowMenu(onWordLists, onAbout) })
        },
    ) { innerPadding ->
        // Saved settings arrive within milliseconds; showing controls before then would let them be overwritten.
        if (state == GeneratorState.Loading) return@Scaffold
        val content = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
        val readable = Modifier.widthIn(max = 600.dp).fillMaxWidth()
        if (isWidthExpanded()) {
            Row(content) {
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                ) { PassphrasePane(state, onRegenerate, onCopy, onEnterDice, readable) }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { movableOptions(readable) }
            }
        } else {
            Column(
                content.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                PassphrasePane(state, onRegenerate, onCopy, onEnterDice, readable)
                movableOptions(readable)
            }
        }
    }
}

/** Whether the window is in the expanded width size class (840dp and wider): tablets, unfolded foldables, desktop. */
@Composable
@ReadOnlyComposable
private fun isWidthExpanded(): Boolean {
    val size = LocalWindowInfo.current.containerDpSize
    return WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(size.width.value, size.height.value)
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
}

/** The Passphrase (or why there is none), its Strength and the actions on it. */
@Composable
private fun PassphrasePane(
    state: GeneratorState, onRegenerate: () -> Unit, onCopy: (String) -> Unit, onEnterDice: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val fill = Modifier.fillMaxWidth()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when (state) {
            GeneratorState.Loading -> {}
            is GeneratorState.Ready -> {
                Passphrase(state, fill)
                Text(stringResource(R.string.strength_bits, state.strengthBits), textAlign = TextAlign.Center, modifier = fill)
                if (state.isWeak) {
                    Text(
                        stringResource(R.string.weak_warning),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = fill,
                    )
                }
            }
            GeneratorState.AwaitingDice -> Text(
                stringResource(R.string.awaiting_dice),
                textAlign = TextAlign.Center,
                modifier = fill,
            )
            GeneratorState.RandomnessUnavailable -> Text(
                text = stringResource(R.string.randomness_unavailable),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = fill,
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
    }
}

/** The whole Passphrase shown, for tests: screen readers read its parts instead. */
val PassphraseText = SemanticsPropertyKey<String>("PassphraseText")
private var SemanticsPropertyReceiver.passphraseText by PassphraseText

/**
 * The Passphrase as one line of text to the eye, but to a screen reader one part per Word (then per appended
 * character), each with a "Spell out" action that reads the whole Passphrase character by character.
 */
@Composable
private fun Passphrase(state: GeneratorState.Ready, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    // Changing text in a polite live region is how Compose announces; the count lets the same text be announced again.
    var spellOuts by remember(state.passphrase) { mutableIntStateOf(0) }
    val spellOut = listOf(CustomAccessibilityAction(stringResource(R.string.spell_out)) { spellOuts++; true })
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().testTag(PASSPHRASE_TAG).semantics { passphraseText = state.passphrase },
            horizontalArrangement = Arrangement.Center,
        ) {
            state.parts.forEach { (shown, spoken) ->
                Text(
                    text = shown,
                    // Monospace keeps l, 1 and I distinguishable.
                    style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.testTag(PASSPHRASE_PART_TAG).semantics {
                        contentDescription = spoken
                        customActions = spellOut
                    },
                )
            }
        }
        Box(
            Modifier.size(1.dp).testTag(SPELLED_OUT_TAG).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = if (spellOuts == 0) "" else spelledOut(state.passphrase, resources) + " ".repeat((spellOuts + 1) % 2)
            },
        )
    }
}

const val PASSPHRASE_PART_TAG = "passphrase_part"
const val SPELLED_OUT_TAG = "spelled_out"

private val characterNames = mapOf(
    ' ' to R.string.char_space,
    '-' to R.string.char_hyphen,
    '.' to R.string.char_period,
    '_' to R.string.char_underscore,
    '!' to R.string.char_exclamation,
    '@' to R.string.char_at,
    '#' to R.string.char_hash,
    '$' to R.string.char_dollar,
    '%' to R.string.char_percent,
    '&' to R.string.char_ampersand,
    '*' to R.string.char_asterisk,
    '+' to R.string.char_plus,
    '=' to R.string.char_equals,
    '?' to R.string.char_question,
)

/** Every character named, so speech can't skip punctuation or hide capitals. */
private fun spelledOut(passphrase: String, resources: Resources): String = passphrase.map { c ->
    characterNames[c]?.let(resources::getString)
        ?: if (c.isUpperCase()) resources.getString(R.string.char_capital, c.toString()) else c.toString()
}.joinToString(", ")

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp,dpi=240", showBackground = true)
@Composable
private fun GeneratorScreenPreview() {
    DicewareTheme {
        GeneratorScreen(
            GeneratorState.Ready("abacus zoom kettle lilac boned tribute", 77.5, listOf("abacus", "zoom", "kettle", "lilac", "boned", "tribute")),
            onRegenerate = {},
            onCopy = {},
            onWordLists = {},
            onAbout = {},
        ) { OptionsControls(PassphraseOptions(), listOf(WordListInfo(EffWordList.LARGE, null, 7776, 12.92)), {}, {}, {}, {}, {}, {}, {}, it) }
    }
}
