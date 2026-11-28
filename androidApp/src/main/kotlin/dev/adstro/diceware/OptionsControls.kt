package dev.adstro.diceware

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

const val WORD_COUNT_TAG = "word_count"

private val separatorLabels = mapOf(
    Separator.SPACE to R.string.separator_space,
    Separator.HYPHEN to R.string.separator_hyphen,
    Separator.PERIOD to R.string.separator_period,
    Separator.UNDERSCORE to R.string.separator_underscore,
    Separator.NONE to R.string.separator_none,
)

/** Renders the core's [PassphraseOptions], with every Word List to pick from, and forwards each change to the core. */
@Composable
fun OptionsControls(
    options: PassphraseOptions,
    wordLists: List<WordListInfo>,
    onChange: (OptionChange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mode), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Mode.RANDOM to R.string.mode_random, Mode.DICE to R.string.mode_dice).forEach { (mode, label) ->
                FilterChip(
                    selected = options.mode == mode,
                    onClick = { onChange(OptionChange.SetMode(mode)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Text(stringResource(R.string.word_list), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wordLists.forEach { info ->
                FilterChip(
                    selected = options.wordList == info.id,
                    onClick = { onChange(OptionChange.SetWordList(info.id)) },
                    label = { Text(nameOf(info)) },
                )
            }
        }

        val wordCountLabel = stringResource(R.string.word_count, options.wordCount)
        Text(wordCountLabel, style = MaterialTheme.typography.titleMedium)
        val range = PassphraseOptions.WORD_COUNTS
        val sliderState = rememberSliderState(
            options.wordCount.toFloat(),
            range.last - range.first - 1,
            range.first.toFloat()..range.last.toFloat(),
        )
        // The core owns the Word count: the slider shows it and forwards each change.
        SideEffect { sliderState.value = options.wordCount.toFloat() }
        Slider(
            state = sliderState,
            onValueChange = { onChange(OptionChange.SetWordCount(it.roundToInt())) },
            modifier = Modifier.testTag(WORD_COUNT_TAG).semantics { contentDescription = wordCountLabel },
        )

        Text(stringResource(R.string.separator), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            separatorLabels.forEach { (separator, label) ->
                FilterChip(
                    selected = options.separator == separator,
                    onClick = { onChange(OptionChange.SetSeparator(separator)) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        ToggleRow(R.string.capitalize, options.capitalize) { onChange(OptionChange.SetCapitalize(it)) }
        ToggleRow(R.string.append_digit, options.appendDigit) { onChange(OptionChange.SetAppendDigit(it)) }
        ToggleRow(R.string.append_symbol, options.appendSymbol) { onChange(OptionChange.SetAppendSymbol(it)) }
    }
}

/** The whole row is one switch, so the label is read with it and is a large touch target. */
@Composable
private fun ToggleRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
