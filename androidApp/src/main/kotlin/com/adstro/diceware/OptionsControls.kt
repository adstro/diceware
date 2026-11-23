package com.adstro.diceware

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
import androidx.compose.runtime.Composable
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

/** Renders the core's [PassphraseOptions] and forwards each change to the core. */
@Composable
fun OptionsControls(
    options: PassphraseOptions,
    onWordCount: (Int) -> Unit,
    onSeparator: (Separator) -> Unit,
    onCapitalise: (Boolean) -> Unit,
    onAppendDigit: (Boolean) -> Unit,
    onAppendSymbol: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wordCountLabel = stringResource(R.string.word_count, options.wordCount)
        Text(wordCountLabel, style = MaterialTheme.typography.titleMedium)
        val range = PassphraseOptions.WORD_COUNTS
        Slider(
            value = options.wordCount.toFloat(),
            onValueChange = { onWordCount(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.testTag(WORD_COUNT_TAG).semantics { contentDescription = wordCountLabel },
        )

        Text(stringResource(R.string.separator), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            separatorLabels.forEach { (separator, label) ->
                FilterChip(
                    selected = options.separator == separator,
                    onClick = { onSeparator(separator) },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        ToggleRow(R.string.capitalise, options.capitalise, onCapitalise)
        ToggleRow(R.string.append_digit, options.appendDigit, onAppendDigit)
        ToggleRow(R.string.append_symbol, options.appendSymbol, onAppendSymbol)
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
