package com.adstro.diceware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

/** Renders the core's Word Lists state: every Word List with its Word count and Strength per Word. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListsScreen(wordLists: List<WordListInfo>, onNavigateUp: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.word_lists)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.navigate_up))
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
            wordLists.forEach { info ->
                ListItem(
                    headlineContent = { Text(stringResource(wordListNames.getValue(info.wordList))) },
                    supportingContent = {
                        Text(stringResource(R.string.word_list_details, info.wordCount, info.strengthPerWordBits))
                    },
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                )
            }
        }
    }
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Large font", device = "spec:width=411dp,height=891dp", fontScale = 2f, showBackground = true)
@Composable
private fun WordListsScreenPreview() {
    DicewareTheme {
        WordListsScreen(
            listOf(
                WordListInfo(EffWordList.LARGE, 7776, 12.92),
                WordListInfo(EffWordList.SHORT_1, 1296, 10.34),
                WordListInfo(EffWordList.SHORT_2, 1296, 10.34),
            ),
            onNavigateUp = {},
        )
    }
}
