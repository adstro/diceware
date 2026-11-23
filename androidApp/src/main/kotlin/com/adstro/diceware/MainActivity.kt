package com.adstro.diceware

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

@Serializable
data object GeneratorRoute : NavKey

@Serializable
data object AboutRoute : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DicewareTheme {
                val generator = viewModel<GeneratorViewModel>().generator
                val backStack = rememberNavBackStack(GeneratorRoute)
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        entry<GeneratorRoute> {
                            val state by generator.state.collectAsStateWithLifecycle()
                            val options by generator.options.collectAsStateWithLifecycle()
                            GeneratorScreen(
                                state,
                                onRegenerate = generator::regenerate,
                                onAbout = dropUnlessResumed { backStack.add(AboutRoute) },
                            ) { modifier ->
                                OptionsControls(
                                    options,
                                    onWordCount = generator::setWordCount,
                                    onSeparator = generator::setSeparator,
                                    onCapitalise = generator::setCapitalise,
                                    onAppendDigit = generator::setAppendDigit,
                                    onAppendSymbol = generator::setAppendSymbol,
                                    modifier = modifier,
                                )
                            }
                        }
                        entry<AboutRoute> { AboutScreen(onNavigateUp = { backStack.removeLastOrNull() }) }
                    },
                )
            }
        }
    }
}

/** Keeps the core's Generator, and so the shown Passphrase, across configuration changes. */
class GeneratorViewModel(application: Application) : AndroidViewModel(application) {
    // shortcut: reads the ~100 KB bundled list on the main thread at first launch; move off-main when lists get large.
    val generator: Generator = createGenerator(
        application.assets.open("eff_large_wordlist.txt").bufferedReader().use { it.readText() },
        application.filesDir.resolve("diceware.preferences_pb").absolutePath,
        viewModelScope,
    )
}
