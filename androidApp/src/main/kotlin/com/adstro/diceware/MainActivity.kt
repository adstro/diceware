package com.adstro.diceware

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DicewareTheme {
                val generator = viewModel<GeneratorViewModel>().generator
                val state by generator.state.collectAsStateWithLifecycle()
                GeneratorScreen(state, onRegenerate = generator::regenerate)
            }
        }
    }
}

/** Keeps the core's Generator, and so the shown Passphrase, across configuration changes. */
class GeneratorViewModel(application: Application) : AndroidViewModel(application) {
    // shortcut: reads the ~100 KB bundled list on the main thread at first launch; move off-main when lists get large.
    val generator: Generator = createGenerator(
        application.assets.open("eff_large_wordlist.txt").bufferedReader().use { it.readText() },
    )
}
