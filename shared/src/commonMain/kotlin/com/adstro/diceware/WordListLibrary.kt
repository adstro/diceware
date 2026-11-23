package com.adstro.diceware

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.use
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Word Lists screen state for an import. */
sealed interface ImportState {
    data object Idle : ImportState

    /** [progress] runs from 0 to 1. */
    data class Importing(val progress: Double) : ImportState

    data class Imported(val name: String) : ImportState

    data class Failed(val error: ImportError) : ImportState
}

private const val MAX_NAME_LENGTH = 100

/**
 * Every Word List: the bundled EFF Word Lists plus Custom Word Lists imported into [directory], which the apps
 * keep app-private and out of cloud backup. Disk work runs on [ioContext], never the caller's thread.
 */
class WordListLibrary(
    private val bundled: () -> Map<EffWordList, WordList>,
    private val fileSystem: FileSystem,
    private val directory: Path,
    private val scope: CoroutineScope,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    httpEngine: () -> HttpClientEngine = ::platformHttpEngine,
) {
    private val index = WordListIndex(fileSystem, directory)
    private val eff = EffWordLists(index, fileSystem, directory, scope, ioContext, httpEngine, ::awaitLoaded, ::publish)
    private val custom = MutableStateFlow<Map<CustomWordListId, DiskWordList>>(emptyMap())
    private val loaded = CompletableDeferred<Unit>()
    private var importJob: Job? = null

    private val mutableWordLists = MutableStateFlow<List<WordListInfo>>(emptyList())

    /** Word Lists screen state: every Word List in the order shown, EFF first; empty until loaded. */
    val wordLists: StateFlow<List<WordListInfo>> = mutableWordLists.asStateFlow()

    private val mutableImportState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = mutableImportState.asStateFlow()

    val effUpdateState: StateFlow<EffUpdateState> = eff.state

    init {
        scope.launch {
            withContext(ioContext) {
                val bundledLists = bundled()
                val saved = index.load()
                eff.load(bundledLists, saved)
                custom.value = saved.filterIsInstance<IndexEntry.Custom>().associate { CustomWordListId(it.sha256) to index.open(it) }
            }
            publish()
            loaded.complete(Unit)
        }
    }

    internal suspend fun awaitLoaded() = loaded.await()

    /** The Word List to resolve Rolls against, or null if there is no such list (any more). */
    fun wordList(id: WordListId): WordList? = when (id) {
        is EffWordList -> eff.inUse(id)
        is CustomWordListId -> custom.value[id]
    }

    /**
     * Imports a Custom Word List named after [fileName] from the untrusted [open]ed source of [sizeBytes]
     * (≤ 0 if unknown), updating [importState]; returns once it is imported, rejected or cancelled.
     * Ignored while another import is running.
     */
    suspend fun import(fileName: String, sizeBytes: Long, open: () -> Source) {
        if (importJob?.isActive == true) return
        mutableImportState.value = ImportState.Importing(0.0)
        val job = scope.launch(ioContext) {
            val staging = directory / STAGING_DIR
            try {
                awaitLoaded()
                fileSystem.deleteRecursively(staging)
                fileSystem.createDirectories(staging)
                val input = try {
                    open()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    throw WordListRejected(ImportError.Unreadable)
                }
                val staged = UnreadableSource(input).buffer().use { source ->
                    stageWordList(source, sizeBytes, fileSystem, staging) { mutableImportState.value = ImportState.Importing(it) }
                }
                val name = nameOf(fileName)
                index.lock.withLock {
                    EffWordList.entries.firstOrNull { eff.sha256InUse(it) == staged.sha256 }?.let {
                        throw WordListRejected(ImportError.AlreadyImportedAsEff(it))
                    }
                    customEntries().firstOrNull { it.sha256 == staged.sha256 }?.let {
                        throw WordListRejected(ImportError.AlreadyImported(it.name))
                    }
                    for (file in WORD_LIST_FILES) index.moveIn(staging / file, wordListFileName(staged.sha256, file))
                    val entry = IndexEntry.Custom(staged.sha256, name, staged.dicePerRoll)
                    index.save(index.entries.value + entry)
                    custom.update { it + (CustomWordListId(entry.sha256) to index.open(entry)) }
                }
                publish()
                mutableImportState.value = ImportState.Imported(name)
            } catch (e: WordListRejected) {
                mutableImportState.value = ImportState.Failed(e.error)
            } catch (e: IOException) {
                mutableImportState.value = ImportState.Failed(ImportError.StorageFailed)
            } catch (e: CancellationException) {
                mutableImportState.value = ImportState.Idle
                throw e
            } finally {
                withContext(NonCancellable) { runCatching { fileSystem.deleteRecursively(staging) } }
            }
        }
        importJob = job
        job.join()
    }

    /** [import] of the file at [path], for iOS, which hands the app file URLs. */
    suspend fun importFile(path: String, fileName: String) {
        val file = path.toPath()
        import(fileName, fileSystem.metadataOrNull(file)?.size ?: -1) { fileSystem.source(file) }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** Back to [ImportState.Idle] once the user has seen an import's result. */
    fun dismissImportResult() {
        mutableImportState.update { if (it is ImportState.Importing) it else ImportState.Idle }
    }

    /** Renames a Custom Word List; a blank name is ignored. EFF Word Lists can't be renamed. */
    fun rename(id: CustomWordListId, name: String) {
        val newName = name.trim().take(MAX_NAME_LENGTH)
        if (newName.isEmpty()) return
        changeIndex { list -> list.map { if (it is IndexEntry.Custom && it.sha256 == id.sha256) it.copy(name = newName) else it } }
    }

    /** Deletes a Custom Word List; if it was the Selected Word List the Generator falls back to EFF Large. */
    fun delete(id: CustomWordListId) {
        // Closed on the caller's thread, the one that rolls, so no Roll can be reading it.
        custom.getAndUpdate { it - id }[id]?.close()
        changeIndex { list -> list.filterNot { it is IndexEntry.Custom && it.sha256 == id.sha256 } }
    }

    /**
     * Downloads the EFF Word Lists from eff.org, validating each like an import, and compares them with the
     * ones in use, updating [effUpdateState]; returns once done. Only ever called when the user asks.
     */
    suspend fun checkForEffUpdates() = eff.check()

    /** Replaces the EFF Word Lists the last check found changed with the downloaded ones. */
    fun applyEffUpdate() = eff.apply()

    /** Back to [EffUpdateState.Idle], dropping an update the user did not apply. */
    fun dismissEffUpdate() = eff.dismiss()

    /** Goes back to the EFF Word Lists bundled with the app, removing any updated copies. */
    fun restoreBundledEffWordLists() {
        eff.closeCopies()
        changeIndex { list -> list.filterIsInstance<IndexEntry.Custom>() }
    }

    private fun customEntries() = index.entries.value.filterIsInstance<IndexEntry.Custom>()

    private fun changeIndex(change: (List<IndexEntry>) -> List<IndexEntry>) {
        scope.launch {
            withContext(ioContext) {
                awaitLoaded()
                index.lock.withLock { index.save(change(index.entries.value)) }
            }
            publish()
        }
    }

    private fun publish() {
        mutableWordLists.value = eff.infos() + customEntries().map { wordListInfo(CustomWordListId(it.sha256), it.name, it.dicePerRoll) }
    }
}

/** The file name without its extension, as typed by whoever made the file. */
private fun nameOf(fileName: String): String {
    val base = fileName.substringAfterLast('/').trim()
    val name = (if ('.' in base.drop(1)) base.substringBeforeLast('.') else base).trim()
    return name.ifEmpty { base }.ifEmpty { "Word List" }.take(MAX_NAME_LENGTH)
}

/** Read failures of the imported file are the file's fault, not a lack of storage space. */
private class UnreadableSource(delegate: Source) : ForwardingSource(delegate) {
    override fun read(sink: okio.Buffer, byteCount: Long): Long = try {
        super.read(sink, byteCount)
    } catch (e: IOException) {
        throw WordListRejected(ImportError.Unreadable)
    }
}
