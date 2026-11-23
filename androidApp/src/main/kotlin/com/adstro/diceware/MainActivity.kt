package com.adstro.diceware

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
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
data object WordListsRoute : NavKey

@Serializable
data object AboutRoute : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keeps the Passphrase out of screenshots, screen recordings and the recent-apps thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                                onCopy = ::copySensitive,
                                onWordLists = dropUnlessResumed { backStack.add(WordListsRoute) },
                                onAbout = dropUnlessResumed { backStack.add(AboutRoute) },
                            ) { modifier ->
                                OptionsControls(
                                    options,
                                    onWordList = generator::setWordList,
                                    onWordCount = generator::setWordCount,
                                    onSeparator = generator::setSeparator,
                                    onCapitalise = generator::setCapitalise,
                                    onAppendDigit = generator::setAppendDigit,
                                    onAppendSymbol = generator::setAppendSymbol,
                                    modifier = modifier,
                                )
                            }
                        }
                        entry<WordListsRoute> {
                            val wordLists by generator.wordLists.collectAsStateWithLifecycle()
                            WordListsScreen(wordLists, onNavigateUp = { backStack.removeLastOrNull() })
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
    // shortcut: reads the ~140 KB of bundled lists on the main thread at first launch; move off-main when lists get large.
    val generator: Generator = createGenerator(
        { application.assets.open(it.fileName).bufferedReader().use { reader -> reader.readText() } },
        application.filesDir.resolve("diceware.preferences_pb").absolutePath,
        viewModelScope,
    )
}

/** Copies [passphrase] marked sensitive, so Android 13+ hides it from the clipboard preview. */
private fun Context.copySensitive(passphrase: String) {
    val clip = ClipData.newPlainText(getString(R.string.passphrase_clip_label), passphrase)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    } else {
        // Android 13+ confirms copies itself; older versions show nothing.
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
