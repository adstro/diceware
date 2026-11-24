package com.adstrosoftware.diceware

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okio.IOException
import okio.source

@Serializable
data object GeneratorRoute : NavKey

@Serializable
data object WordListsRoute : NavKey

@Serializable
data object AboutRoute : NavKey

@Serializable
data object DiceEntryRoute : NavKey

class MainActivity : ComponentActivity() {
    private val viewModel: GeneratorViewModel by viewModels()
    private var showWordLists by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFrom(intent)
    }

    /**
     * Imports a file shared to the app ("Open with" or the share sheet). The intent is untrusted: only a
     * content:// URI is read, as a stream, and the file goes through the core's full validation.
     */
    private fun importFrom(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            // A malformed extra from another app must not crash us.
            Intent.ACTION_SEND -> runCatching { IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) }.getOrNull()
            else -> null
        } ?: return
        if (viewModel.import(uri)) showWordLists = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keeps the Passphrase out of screenshots, screen recordings and the recent-apps thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // A restored activity already handled its intent before it was recreated.
        if (savedInstanceState == null) importFrom(intent)
        setContent { DicewareTheme { Navigation() } }
    }

    @Composable
    private fun Navigation() {
        val generator = viewModel.generator
        val backStack = rememberNavBackStack(GeneratorRoute)
        if (showWordLists) {
            LaunchedEffect(Unit) {
                if (backStack.lastOrNull() != WordListsRoute) backStack.add(WordListsRoute)
                showWordLists = false
            }
        }
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<GeneratorRoute> { GeneratorEntry(generator, backStack) }
                entry<WordListsRoute> {
                    val library = generator.library
                    val wordLists by library.wordLists.collectAsStateWithLifecycle()
                    val importState by library.importState.collectAsStateWithLifecycle()
                    val effUpdateState by library.effUpdateState.collectAsStateWithLifecycle()
                    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let(viewModel::import)
                    }
                    WordListsScreen(
                        wordLists,
                        importState,
                        effUpdateState,
                        onNavigateUp = { backStack.removeLastOrNull() },
                        onImport = { pickFile.launch(arrayOf("text/*")) },
                        onCancelImport = library::cancelImport,
                        onDismissImportResult = library::dismissImportResult,
                        onRename = library::rename,
                        onDelete = library::delete,
                        onCheckForUpdates = viewModel::checkForEffUpdates,
                        onApplyUpdate = library::applyEffUpdate,
                        onDismissUpdate = library::dismissEffUpdate,
                        onRestoreBundledWordLists = library::restoreBundledEffWordLists,
                    )
                }
                entry<DiceEntryRoute> {
                    val entry by generator.diceEntry.collectAsStateWithLifecycle()
                    DiceEntryScreen(
                        entry,
                        onDie = generator::enterDie,
                        onRemoveLastDie = generator::removeLastDie,
                        onNavigateUp = { backStack.removeLastOrNull() },
                    )
                }
                entry<AboutRoute> { AboutScreen(onNavigateUp = { backStack.removeLastOrNull() }) }
            },
        )
    }

    @Composable
    private fun GeneratorEntry(generator: Generator, backStack: NavBackStack<NavKey>) {
        val state by generator.state.collectAsStateWithLifecycle()
        val options by generator.options.collectAsStateWithLifecycle()
        val wordLists by generator.library.wordLists.collectAsStateWithLifecycle()
        GeneratorScreen(
            state,
            onRegenerate = generator::regenerate,
            onCopy = ::copySensitive,
            onWordLists = dropUnlessResumed { backStack.add(WordListsRoute) },
            onAbout = dropUnlessResumed { backStack.add(AboutRoute) },
            onEnterDice = dropUnlessResumed {
                generator.startDiceEntry()
                backStack.add(DiceEntryRoute)
            }.takeIf { options.mode == Mode.DICE },
        ) { modifier ->
            OptionsControls(
                options,
                wordLists,
                onMode = generator::setMode,
                onWordList = generator::setWordList,
                onWordCount = generator::setWordCount,
                onSeparator = generator::setSeparator,
                onCapitalize = generator::setCapitalize,
                onAppendDigit = generator::setAppendDigit,
                onAppendSymbol = generator::setAppendSymbol,
                modifier = modifier,
            )
        }
    }
}

/** Keeps the core's Generator, and so the shown Passphrase, across configuration changes. */
class GeneratorViewModel(application: Application) : AndroidViewModel(application) {
    val generator: Generator = createGenerator(
        // Called by the core off the main thread.
        { application.assets.open(it.fileName).bufferedReader().use { reader -> reader.readText() } },
        application.filesDir.resolve("diceware.preferences_pb").absolutePath,
        // App-private, and excluded from backup and device transfer by res/xml/data_extraction_rules.xml.
        application.filesDir.resolve(WORD_LISTS_DIR).absolutePath,
        viewModelScope,
    )

    /**
     * Imports the document at [uri] as a Custom Word List; false if it was refused unread. Only content://
     * URIs are read: a file:// URI from another app could point at this app's own private files.
     */
    fun import(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val (name, size) = withContext(Dispatchers.IO) { nameAndSize(resolver, uri) }
            generator.library.import(name, size) {
                resolver.openInputStream(uri)?.source() ?: throw IOException("No stream for $uri")
            }
        }
        return true
    }

    /** Only ever called from the user's tap on "Check for updates". */
    fun checkForEffUpdates() {
        viewModelScope.launch { generator.library.checkForEffUpdates() }
    }

    private fun nameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = if (it.isNull(0)) "" else it.getString(0)
            val size = if (it.isNull(1)) -1L else it.getLong(1)
            name to size
        }
    }.getOrNull() ?: ("" to -1L)
}

const val WORD_LISTS_DIR = "wordlists"

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
