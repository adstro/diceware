package com.adstro.diceware

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** The top app bar's overflow menu; the way to reach the app's secondary screens. */
@Composable
fun OverflowMenu(onAbout: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.about)) },
            onClick = {
                expanded = false
                onAbout()
            },
        )
    }
}

/** Explains Diceware, credits the EFF Word Lists and states the privacy guarantees. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateUp: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
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
                // Inside the scroll, so text scrolls behind the navigation bar instead of being clipped above it.
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section(R.string.about_diceware_title, R.string.about_diceware_body)
                Section(R.string.about_word_lists_title, R.string.about_word_lists_body)
                Section(R.string.about_privacy_title, R.string.about_privacy_body)
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, @StringRes body: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp).semantics { heading() },
    )
    Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true)
@Preview(name = "Large font", device = "spec:width=411dp,height=891dp", fontScale = 2f, showBackground = true)
@Composable
private fun AboutScreenPreview() {
    DicewareTheme { AboutScreen(onNavigateUp = {}) }
}
